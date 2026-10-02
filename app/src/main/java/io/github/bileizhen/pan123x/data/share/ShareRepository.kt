package io.github.bileizhen.pan123x.data.share

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanShareApi
import io.github.bileizhen.pan123x.core.network.SHARE_NEXT_NO_MORE
import io.github.bileizhen.pan123x.core.network.ShareItemDto
import io.github.bileizhen.pan123x.core.network.SharePageDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 创建分享的结果（接口约定）：成功带可复制链接，失败带用户可读文案。 */
sealed interface ShareOutcome {
    data class Created(val url: String) : ShareOutcome
    data class Failed(val userMessage: String) : ShareOutcome
}

/** 分享列表页状态（显式五态；ERROR 时旧列表仍保留在 [ShareRepository.shares]）。 */
enum class ShareListStatus { LOADING, CONTENT, EMPTY, ERROR, REFRESHING }

/**
 * 分享仓库（M6）：列表数据**只存内存、绝不写 Room**。
 *
 * 原因同 [io.github.bileizhen.pan123x.data.file.RecycleRepository] 先例：分享行与文件缓存
 * 无外键关系，入 Room 反而要按 accountId 做级联清理，收益为零；进入页面时重新拉取即可。
 * 所有状态不跨账户：账户切换 / 登出由接线处调用 [reset] 清空。
 *
 * 重试语义：协议层对非幂等 POST 不自动重试；code==2 会话过期时经注入的
 * [relogin] 重登一次并仅重试当次调用一次（对齐 FileRepository / FileOpsRepository 的
 * withReauth 先例——重登产生新 token 后的重试是新的授权调用，不是盲目网络重试）。
 *
 * 并发：[refresh] / [loadMore] 共用锁；刷新排队等待，加载更多在占用时为 no-op；
 * 创建 / 撤销不占该标记。请求返回时会话若已切换 / 登出，结果按旧账户数据丢弃。
 */
/**
 * 分享页 ViewModel 的仓库接缝（feature.share.TransferTasksSource 同款放法，但接口必须住在
 * data 层：Kotlin 没有 Java 的结构化类型，feature 层自声明接口无法让 data 层的仓库"碰巧"满足，
 * 反向依赖又违反  分层）。仓库显式实现本接口，PanXApp 直接传仓库实例。
 */
interface ShareActions {
    /** 当前账户的内存分享列表（仓库只存内存，账户切换/登出即清）。 */
    val shares: StateFlow<List<ShareItemDto>>
    val status: StateFlow<ShareListStatus>
    /** 最近一次失败的用户可读文案（ERROR 态展示 + 重试用）。 */
    val error: StateFlow<String?>
    suspend fun refresh()
    suspend fun loadMore()
    suspend fun revoke(shareId: Long): Boolean
}

class ShareRepository(
    private val api: PanShareApi,
    private val manager: AccountManager,
    // suspend 与 FileRepository/FileOpsRepository 的 relogin 同型（AuthRepository.relogin 是挂起
    // 函数）；Kotlin 允许把非挂起 lambda 传给 suspend 函数类型，测试替身无需改动。
    private val relogin: suspend () -> Boolean,
    private val logger: AppLogger,
) : ShareActions {

    /** 当前会话的 accountId；未登录 / 恢复中为 null，供测试与接线处读取。 */
    val accountIdOfSession: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    private val mutableShares = MutableStateFlow<List<ShareItemDto>>(emptyList())

    /** 当前账户的内存分享列表（不跨账户；切换 / 登出即清）。 */
    override val shares: StateFlow<List<ShareItemDto>> = mutableShares.asStateFlow()

    private val mutableStatus = MutableStateFlow(ShareListStatus.LOADING)
    override val status: StateFlow<ShareListStatus> = mutableStatus.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)

    /** 最近一次失败的用户可读文案（ERROR 态展示 + 重试用）；任一成功路径清空。 */
    override val error: StateFlow<String?> = mutableError.asStateFlow()

    /**
     * 是否还有下一页可加载（已成功加载过首页且游标非 "-1"）。
     * 供 ViewModel 推导 canLoadMore；与 [loadMore] 的 no-op 条件同源。
     */
    val canLoadMore: Boolean
        get() = nextCursor != null && nextCursor != SHARE_NEXT_NO_MORE

    /** 分页游标：null = 尚未成功加载过首页；[SHARE_NEXT_NO_MORE] = 无更多。 */
    @Volatile
    private var nextCursor: String? = null

    /** Serialize refreshes so a new account can load after an old manual request finishes. */
    private val inFlight = Mutex()
    private val mutableSession = MutableStateFlow<SessionState>(SessionState.Restoring)
    val sessionState: StateFlow<SessionState> = mutableSession.asStateFlow()

    /** Publish the session only after clearing the previous account's list. Load on page entry. */
    fun bindSession(scope: CoroutineScope) = scope.launch {
        manager.state.distinctUntilChangedBy { (it as? SessionState.Ready)?.accountId ?: it }.collectLatest { state ->
            reset()
            mutableSession.value = state
        }
    }

    /**
     * 拉首页（next="0"）整体替换。成功推导 CONTENT / EMPTY；失败保留旧列表与旧游标，
     * 置 ERROR + [error]（网络失败不清数据）。取消原样向上传播。
     */
    override suspend fun refresh() {
        // Wait for a previous account's manual request to leave before loading the new account.
        inFlight.lock()
        try {
            val accountId = accountIdOfSession ?: return
            mutableStatus.value =
                if (mutableShares.value.isEmpty()) ShareListStatus.LOADING else ShareListStatus.REFRESHING
            mutableError.value = null
            val result = withReauth { api.listShares(limit = LIST_LIMIT, next = FIRST_PAGE_CURSOR) }
            if (accountIdOfSession != accountId) return
            when (result) {
                is ApiResult.Success -> applyPage(replace = true, page = result.data)
                else -> failList(result)
            }
        } finally {
            inFlight.unlock()
        }
    }

    /**
     * 追加下一页。未加载过首页、无更多（next=="-1"）或已在加载时为 no-op；失败保留旧列表
     * 与旧游标（可重试），置 ERROR + [error]。
     */
    override suspend fun loadMore() {
        val accountId = accountIdOfSession ?: return
        val cursor = nextCursor ?: return
        if (cursor == SHARE_NEXT_NO_MORE) return
        if (!inFlight.tryLock()) return
        try {
            val result = withReauth { api.listShares(limit = LIST_LIMIT, next = cursor) }
            if (accountIdOfSession != accountId) return
            when (result) {
                is ApiResult.Success -> applyPage(replace = false, page = result.data)
                else -> failList(result)
            }
        } finally {
            inFlight.unlock()
        }
    }

    /** 账户切换 / 登出时由接线处（AppContainer）调用：清空内存态回到初始 LOADING。 */
    fun reset() {
        nextCursor = null
        mutableShares.value = emptyList()
        mutableStatus.value = ShareListStatus.LOADING
        mutableError.value = null
    }

    /**
     * 创建分享：[fileIds] 去重后传给 API（对应参考源 `str(int(fid))` 归一化语义，
     * file_service.py:390）。成功返回 [ShareOutcome.Created]（链接即服务端确认结果，
     * 列表交由下一次 [refresh] 同步，不做 UI 假刷新）；失败返回 [ShareOutcome.Failed]。
     */
    suspend fun create(fileIds: List<Long>, sharePwd: String): ShareOutcome =
        create(fileIds, io.github.bileizhen.pan123x.core.share.ShareCreateOptions(password = sharePwd))

    suspend fun create(fileIds: List<Long>, options: io.github.bileizhen.pan123x.core.share.ShareCreateOptions): ShareOutcome {
        if (accountIdOfSession == null) return ShareOutcome.Failed(ShareMessages.NOT_LOGGED_IN)
        options.validationError()?.let { return ShareOutcome.Failed(it) }
        val normalized = fileIds.distinct()
        if (normalized.isEmpty()) return ShareOutcome.Failed(ShareMessages.CREATE_EMPTY_SELECTION)
        val result = withReauth { api.createShare(normalized, options) }
        return when (result) {
            is ApiResult.Success -> {
                logger.i(LogSource.FILE, "创建分享成功（${normalized.size} 个文件）")
                ShareOutcome.Created(result.data.url)
            }
            else -> {
                val message = ShareMessages.createFailure(result)
                logger.w(LogSource.FILE, "创建分享失败（${normalized.size} 个文件）：$message")
                ShareOutcome.Failed(message)
            }
        }
    }

    /**
     * 撤销分享。成功返回 true 并从内存列表移除该行；失败写 [error] 返回 false，
     * 不做 UI 假成功。成功后列表为空则推导 EMPTY 态。
     */
    override suspend fun revoke(shareId: Long): Boolean {
        if (accountIdOfSession == null) {
            mutableError.value = ShareMessages.NOT_LOGGED_IN
            return false
        }
        val result = withReauth { api.deleteShare(shareId) }
        if (result !is ApiResult.Success) {
            val message = ShareMessages.revokeFailure(result)
            mutableError.value = message
            logger.w(LogSource.FILE, "撤销分享失败 shareId=$shareId：$message")
            return false
        }
        mutableShares.value = mutableShares.value.filterNot { it.shareId == shareId }
        mutableError.value = null
        if (mutableShares.value.isEmpty() && mutableStatus.value == ShareListStatus.CONTENT) {
            mutableStatus.value = ShareListStatus.EMPTY
        }
        logger.i(LogSource.FILE, "撤销分享成功 shareId=$shareId")
        return true
    }

    /** 成功页落地：refresh 整体替换、loadMore 追加；游标与状态按服务端 Next 推进。 */
    private fun applyPage(replace: Boolean, page: SharePageDto) {
        nextCursor = page.next
        mutableShares.value = if (replace) page.items else mutableShares.value + page.items
        mutableError.value = null
        mutableStatus.value =
            if (mutableShares.value.isEmpty()) ShareListStatus.EMPTY else ShareListStatus.CONTENT
    }

    /** 列表失败落地：保留旧列表与旧游标，置 ERROR + 用户可读文案。 */
    private fun failList(result: ApiResult<*>) {
        val message = ShareMessages.listFailure(result)
        mutableError.value = message
        mutableStatus.value = ShareListStatus.ERROR
        logger.w(LogSource.FILE, "分享列表加载失败：$message")
    }

    /**
     * 会话过期统一处理：code==2 且 relogin 成功时重试当次调用一次；其余结果原样返回，
     * 由调用方映射文案。重试结果不再做第二次重登（对齐 FileOpsRepository.withReauth）。
     */
    private suspend fun <T> withReauth(block: suspend () -> ApiResult<T>): ApiResult<T> {
        val first = block()
        if (first !is ApiResult.SessionExpired) return first
        if (!relogin()) return first
        return block()
    }

    private companion object {
        /** 参考源 share_service.py:34 默认 limit=500、首包游标 next=0。 */
        const val LIST_LIMIT = 500
        const val FIRST_PAGE_CURSOR = "0"
    }
}
