package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.PanFileApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 回收站内存快照：loading / 条目 / 最近一次刷新的用户可读错误。 */
data class RecycleState(
    val loading: Boolean = false,
    val items: List<CloudFileEntity> = emptyList(),
    val error: String? = null,
)

/**
 * 回收站仓库（M3 配套）：列表数据**只存内存、绝不写 Room**。
 *
 * 原因：123pan 回收站与目录列表共用 `GET /api/file/list/new`（trashed=true），被删条目
 * 仍携带**原 parentFileId**（删除不改父 id）。若把 trashed 行写入正常目录缓存表，会与
 * 浏览缓存混写在同一 (accountId, parentFileId) 目录里，污染  的缓存语义，
 * 因此回收站整体走内存态，进入页面时重新拉取。
 *
 * 分页 / 节流 / 失败保留旧数据复用 [FileRepository.refreshDirectory] 同款规则
 * （协议真源 file_service.py）：parentFileId=0 + trashed=true，page 从 1 起，
 * 累计条数 >= Total 或某页为空即停，每累计 5 页 delay 500ms（挂起可取消）。
 *
 * 恢复 / 永久删除委托 [FileOpsRepository]（接口约定：trash 内层大写 FileId、
 * delete 内层小写 fileId），成功后仅从内存列表移除对应项，不触达 Room、不做 UI 假成功。
 */
class RecycleRepository(
    private val api: PanFileApi,
    private val ops: FileOpsRepository,
    private val manager: AccountManager,
    private val logger: AppLogger,
) {

    private val mutableState = MutableStateFlow(RecycleState())

    /** 回收站内存快照；进入页面时由 ViewModel 触发 [refresh] 重建。 */
    val state: StateFlow<RecycleState> = mutableState.asStateFlow()

    /** 当前会话状态：未登录 / 恢复中分支由 UI 按 SessionState 呈现。 */
    val sessionState: StateFlow<SessionState> get() = manager.state

    /** 当前会话的 accountId；未登录 / 恢复中为 null，供 ViewModel 做"每账户一次"自动刷新去重。 */
    val accountIdOfSession: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    /**
     * 全量拉取回收站到内存。未登录 / 恢复中直接清空内存态；任一页失败保留旧列表并置
     * error（网络失败不清数据），由 UI 呈现错误态与重试。
     * 取消（CancellationException）原样向上传播。
     */
    suspend fun refresh() {
        val accountId = accountIdOfSession
        if (accountId == null) {
            mutableState.value = RecycleState()
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        val collected = ArrayList<CloudFileEntity>()
        var total = 0
        var page = 1
        while (true) {
            val result = api.getFileList(RECYCLE_PARENT_ID, page, PAGE_LIMIT, trashed = true)
            val data = when (result) {
                is ApiResult.Success -> result.data
                else -> {
                    val userMessage = FileMessages.refreshFailure(result)
                    logger.w(LogSource.FILE, "回收站刷新失败（第 $page 页）：$userMessage")
                    mutableState.update { it.copy(loading = false, error = userMessage) }
                    return
                }
            }
            collected += data.infoList.map { it.toEntity(accountId) }
            total = data.total
            val hasMore = data.infoList.isNotEmpty() && collected.size < total
            if (!hasMore) break
            page++
            // 全量加载每累计 5 页节流一次（挂起等待，协程取消直接传播）
            if ((page - 1) % THROTTLE_EVERY_PAGES == 0) delay(THROTTLE_DELAY_MS)
        }
        mutableState.value = RecycleState(loading = false, items = collected)
        logger.i(LogSource.FILE, "回收站刷新成功：${collected.size} 个项目（total=$total）")
    }

    /** 恢复选中项：委托 [FileOpsRepository.restore]，成功后从内存列表移除。 */
    suspend fun restore(items: List<CloudFileEntity>): OpsOutcome {
        if (items.isEmpty()) return OpsOutcome.Success()
        val outcome = ops.restore(items)
        if (outcome is OpsOutcome.Success) {
            clearInMemory(items)
            logger.i(LogSource.FILE, "回收站恢复成功：${items.size} 个项目")
        }
        return outcome
    }

    /** 永久删除选中项：委托 [FileOpsRepository.deleteForever]，成功后从内存列表移除。 */
    suspend fun deleteForever(items: List<CloudFileEntity>): OpsOutcome {
        if (items.isEmpty()) return OpsOutcome.Success()
        val outcome = ops.deleteForever(items)
        if (outcome is OpsOutcome.Success) {
            clearInMemory(items)
            logger.i(LogSource.FILE, "回收站永久删除成功：${items.size} 个项目")
        }
        return outcome
    }

    /** 成功操作后的本地收尾：按 fileId 从内存列表移除，不触达 Room。 */
    fun clearInMemory(items: List<CloudFileEntity>) {
        if (items.isEmpty()) return
        val ids = items.mapTo(HashSet()) { it.fileId }
        mutableState.update { old -> old.copy(items = old.items.filterNot { it.fileId in ids }) }
    }

    /** trashed 条目照常转缓存行结构：parentFileId 保留原目录 id，供"原位置"展示。 */
    private fun FileItemDto.toEntity(accountId: String) = CloudFileEntity(
        accountId = accountId,
        fileId = fileId,
        parentFileId = parentFileId,
        fileName = fileName,
        isFolder = isFolder,
        size = size,
        etag = etag,
        s3KeyFlag = s3KeyFlag,
        createAt = createAt,
        updateAt = updateAt,
    )

    private companion object {
        const val RECYCLE_PARENT_ID = 0L
        const val PAGE_LIMIT = 100
        const val THROTTLE_EVERY_PAGES = 5
        const val THROTTLE_DELAY_MS = 500L
    }
}
