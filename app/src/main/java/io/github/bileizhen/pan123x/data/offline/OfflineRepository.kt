package io.github.bileizhen.pan123x.data.offline

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.OfflineResolvedItem
import io.github.bileizhen.pan123x.core.network.OfflineResource
import io.github.bileizhen.pan123x.core.network.OfflineSubmittedTask
import io.github.bileizhen.pan123x.core.network.PanOfflineApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 离线下载页状态机（StateFlow 单值，UI 直接 when 渲染）。
 *
 * 相位推进：Idle → Resolving → Resolved(items) → Submitting(items) → Done(summary, items)；
 * 任一失败落 Error(userMessage, items=当前已解析列表，供 UI 保留资源卡)。Submitted 相位
 * 与 Done/Error 均携带 items，UI 无需自行缓存中间列表。
 */
sealed interface OfflineState {

    /** 初始态；[OfflineRepository.reset] 回到此态。 */
    data object Idle : OfflineState

    /** resolve 请求进行中。 */
    data object Resolving : OfflineState

    /** 解析完成：逐条资源（含 result!=0 的失败项，UI 按需展示 errMessage）。 */
    data class Resolved(val items: List<OfflineResolvedItem>) : OfflineState

    /** 提交进行中（保留解析列表渲染）。 */
    data class Submitting(val items: List<OfflineResolvedItem>) : OfflineState

    /**
     * 提交完成：[OfflineSubmitSummary.succeeded] 为服务端确认成功的任务数，
     * [items] 为提交前的解析列表。
     */
    data class Done(
        val summary: OfflineSubmitSummary,
        val items: List<OfflineResolvedItem>,
    ) : OfflineState

    /** 失败：[userMessage] 用户可读，[items] 为当前已解析列表（可能为空）。 */
    data class Error(
        val userMessage: String,
        val items: List<OfflineResolvedItem> = emptyList(),
    ) : OfflineState
}

/**
 * 提交结果汇总（OfflineState.Done 携带）。[firstFailure] 取第一条失败任务的
 * errMessage（服务端原文，可能为 null）；data.task_list 整体缺失时 total/failed 为 0、
 * firstFailure 为"服务器未返回任务结果"，UI 据此提示用户稍后确认。
 */
data class OfflineSubmitSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val firstFailure: String?,
)

/** 离线域失败到用户可读文案的映射，风格对齐 ShareMessages。 */
private object OfflineMessages {
    const val NOT_LOGGED_IN = "请先登录"
    const val EMPTY_INPUT = "请输入下载链接"
    const val EMPTY_SELECTION = "请先勾选要离线下载的资源"
    const val RESOLVE_FAILED = "离线解析失败，请稍后重试"
    const val SUBMIT_FAILED = "离线任务提交失败，请稍后重试"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    const val NO_TASKS = "服务器未返回任务结果"
    private const val NETWORK = "网络连接失败，请检查网络后重试"
    private const val MALFORMED = "服务器响应异常，请稍后重试"

    /** ApiError 透传服务端中文 message（空白回退 [fallback]），其余各给固定文案。 */
    fun failure(result: ApiResult<*>, fallback: String): String = when (result) {
        is ApiResult.ApiError -> result.message.ifBlank { fallback }
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> fallback
    }
}

/**
 * 离线下载仓库（M7， / ；协议真源 offline_service.py:51-98）。
 *
 * 与 ShareRepository 同款内存仓库：状态只存内存、绝不写 Room（参考源无离线任务列表
 * 端点，无法回填），所有状态不跨账户：账户切换 / 登出由接线处调用
 * [reset] 清空。
 *
 * 重试语义：resolve / submit 均为非幂等 POST，协议层不自动重试；
 * code==2 会话过期时经注入的 [relogin] 重登一次并仅重试当次调用一次（对齐
 * ShareRepository.withReauth 先例）。并发：resolve / submit 共用一把 in-flight 标记，
 * 任一进行中时另者为 no-op；请求返回时会话若已切换，结果按旧账户丢弃。
 */
/**
 * 离线页 ViewModel 的仓库接缝（feature.offline.OfflineViewModel 消费；M6 ShareActions 教训：
 * 接口必须住在被实现方一侧——data 层，否则 Kotlin 无结构化类型接不上，反向依赖又违反）。
 */
interface OfflineRepositoryApi {
    val state: StateFlow<OfflineState>

    suspend fun resolve(urlsText: String)

    /** resourceId → selectFileIds；空列表 = 整个资源。 */
    suspend fun submit(selections: Map<Long, List<Long>>)

    fun reset()
}

class OfflineRepository(
    private val api: PanOfflineApi,
    private val manager: AccountManager,
    private val relogin: suspend () -> Boolean,
    private val logger: AppLogger,
) : OfflineRepositoryApi {

    /** 当前会话的 accountId；未登录 / 恢复中为 null，供测试与接线处读取。 */
    val accountIdOfSession: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    private val mutableState = MutableStateFlow<OfflineState>(OfflineState.Idle)

    /** 离线页唯一状态源；UI 消费后按相位 when 渲染。 */
    override val state: StateFlow<OfflineState> = mutableState.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    /** 当前状态中携带的解析列表（Resolved/Submitting/Done/Error 取 items，其余为空）。 */
    private val currentItems: List<OfflineResolvedItem>
        get() = when (val value = mutableState.value) {
            is OfflineState.Resolved -> value.items
            is OfflineState.Submitting -> value.items
            is OfflineState.Done -> value.items
            is OfflineState.Error -> value.items
            OfflineState.Idle, OfflineState.Resolving -> emptyList()
        }

    /**
     * 解析离线链接：[urlsText] 多行原文 trim 后逐字发送。空白输入置 Error 不发请求；
     * 成功落 Resolved（部分失败项保留在 items，`ok=false`）；接口失败置 Error。
     * 取消（CancellationException）原样向上传播。
     */
    override suspend fun resolve(urlsText: String) {
        val accountId = accountIdOfSession ?: run {
            mutableState.value = OfflineState.Error(OfflineMessages.NOT_LOGGED_IN)
            return
        }
        val trimmed = urlsText.trim()
        if (trimmed.isEmpty()) {
            mutableState.value = OfflineState.Error(OfflineMessages.EMPTY_INPUT, currentItems)
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        try {
            mutableState.value = OfflineState.Resolving
            val result = withReauth { api.resolve(trimmed) }
            if (accountIdOfSession != accountId) return
            when (result) {
                is ApiResult.Success -> {
                    mutableState.value = OfflineState.Resolved(result.data)
                    logger.i(
                        LogSource.DOWNLOAD,
                        "离线解析成功：${result.data.size} 条（可下载 ${result.data.count { it.ok }} 条）",
                    )
                }
                else -> {
                    val message = OfflineMessages.failure(result, OfflineMessages.RESOLVE_FAILED)
                    mutableState.value = OfflineState.Error(message)
                    logger.w(LogSource.DOWNLOAD, "离线解析失败：$message")
                }
            }
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * 提交离线任务：[selections] 为 resourceId → selectFileIds（空列表 = 整个资源）。
     * 空选择置 Error 不发请求；成功按服务端 task_list 聚合 Done 汇总；接口失败置 Error
     * 并保留当前解析列表。
     */
    override suspend fun submit(selections: Map<Long, List<Long>>) {
        val accountId = accountIdOfSession ?: run {
            mutableState.value = OfflineState.Error(OfflineMessages.NOT_LOGGED_IN, currentItems)
            return
        }
        val items = currentItems
        if (selections.isEmpty()) {
            mutableState.value = OfflineState.Error(OfflineMessages.EMPTY_SELECTION, items)
            return
        }
        if (!inFlight.compareAndSet(false, true)) return
        try {
            mutableState.value = OfflineState.Submitting(items)
            val resources = selections.map { (resourceId, selectFileIds) ->
                OfflineResource(resourceId, selectFileIds)
            }
            val result = withReauth { api.submit(resources) }
            if (accountIdOfSession != accountId) return
            when (result) {
                is ApiResult.Success -> {
                    val summary = summarize(result.data)
                    mutableState.value = OfflineState.Done(summary, items)
                    logger.i(LogSource.DOWNLOAD, "离线任务提交：${summary.succeeded}/${summary.total} 成功")
                }
                else -> {
                    val message = OfflineMessages.failure(result, OfflineMessages.SUBMIT_FAILED)
                    mutableState.value = OfflineState.Error(message, items)
                    logger.w(LogSource.DOWNLOAD, "离线任务提交失败：$message")
                }
            }
        } finally {
            inFlight.set(false)
        }
    }

    /** 账户切换 / 登出时由接线处（AppContainer）调用：回到 Idle。 */
    override fun reset() {
        mutableState.value = OfflineState.Idle
    }

    /**
     * 聚合 task_list：ok 计成功；task_list 为空（data 缺失）不算提交成功，
     * firstFailure 给出固定提示供 UI 展示（offline_download_dialog.py:554-565 同语义）。
     */
    private fun summarize(tasks: List<OfflineSubmittedTask>): OfflineSubmitSummary {
        if (tasks.isEmpty()) {
            return OfflineSubmitSummary(
                total = 0,
                succeeded = 0,
                failed = 0,
                firstFailure = OfflineMessages.NO_TASKS,
            )
        }
        val failedTasks = tasks.filterNot { it.ok }
        return OfflineSubmitSummary(
            total = tasks.size,
            succeeded = tasks.size - failedTasks.size,
            failed = failedTasks.size,
            firstFailure = failedTasks.firstOrNull()?.errMessage?.takeIf { it.isNotBlank() },
        )
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
}
