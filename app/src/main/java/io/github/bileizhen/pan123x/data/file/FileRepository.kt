@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateDao
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.FileListDto
import io.github.bileizhen.pan123x.core.network.PanFileApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/** 目录快照：Room 缓存的文件 + 目录元状态；无状态行时按已知缓存文件数展示。 */
data class DirectorySnapshot(
    val files: List<CloudFileEntity>,
    val total: Int,
    val allLoaded: Boolean,
    val updatedAt: Long?,
    val cacheRevision: Long = 0,
)

sealed interface RefreshOutcome {
    data object Success : RefreshOutcome
    data class Failure(val userMessage: String) : RefreshOutcome
}

/**
 * 云盘文件仓库：目录数据的唯一进出关口。
 *
 * 加载策略：进入目录先读 Room 缓存（[observeDirectory]），后台 [refreshDirectory] 分页
 * 拉全量；只有完整拿到全部页才原子替换缓存（[CloudFileDao.replaceDirectoryWithState]），
 * 部分失败一律保留旧缓存，不清空列表。
 *
 * 分页与节流按协议真源（file_service.py）：page 从 1 起，累计条数 >= Total 或某页为空
 * 即停；全量加载每累计 5 页等待 500ms（挂起等待，协程取消直接传播）。
 *
 * 会话过期：code==2 时经注入的 [relogin]（AuthRepository 的重登能力）重登一次并仅
 * 重试当页一次（非幂等 POST 不盲目重试）。
 */
class FileRepository(
    private val api: PanFileApi,
    private val cloudFileDao: CloudFileDao,
    private val directoryStateDao: DirectoryStateDao,
    private val manager: AccountManager,
    private val relogin: suspend () -> Boolean,
    private val logger: AppLogger,
) {
    private val cacheRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun notifyCacheInvalidated() { cacheRevision.update { it + 1 } }

    /** 当前会话的 accountId；未登录 / 恢复中为 null，供 UI 与测试读取。 */
    val accountIdOfSession: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    /**
     * 观察目录缓存：combine(会话状态, 文件流, 目录状态流)。未登录 / 恢复中返回空快照；
     * 会话按 accountId 去重，避免元数据更新（如昵称补全）导致 Room 查询反复重订阅。
     */
    fun observeDirectory(dirId: Long): Flow<DirectorySnapshot> = manager.state
        .distinctUntilChangedBy { (it as? SessionState.Ready)?.accountId }
        .flatMapLatest { state ->
            val ready = state as? SessionState.Ready
            if (ready == null) {
                flowOf(DirectorySnapshot(files = emptyList(), total = 0, allLoaded = false, updatedAt = null))
            } else {
                combine(
                    cloudFileDao.observeDirectory(ready.accountId, dirId),
                    directoryStateDao.observe(ready.accountId, dirId),
                    cacheRevision,
                ) { files, directory, revision ->
                    DirectorySnapshot(
                        files = files,
                        total = directory?.total ?: files.size,
                        allLoaded = directory?.allLoaded ?: false,
                        updatedAt = directory?.updatedAt,
                        cacheRevision = revision,
                    )
                }
            }
        }

    /**
     * 全量刷新一个目录。成功时原子替换缓存与目录状态；任何失败保留旧缓存并返回
     * 用户可读文案（[FileMessages]）。取消（CancellationException）原样向上传播。
     */
    suspend fun refreshDirectory(dirId: Long, trashed: Boolean = false): RefreshOutcome {
        val accountId = accountIdOfSession
            ?: return RefreshOutcome.Failure(FileMessages.NOT_LOGGED_IN)
        val startedAt = System.currentTimeMillis()

        val collected = ArrayList<FileItemDto>()
        var total = 0
        var page = 1
        while (true) {
            if (accountIdOfSession != accountId) return RefreshOutcome.Failure(FileMessages.ACCOUNT_CHANGED)
            val result = fetchPage(dirId, page, trashed)
            if (accountIdOfSession != accountId) return RefreshOutcome.Failure(FileMessages.ACCOUNT_CHANGED)
            val data = when (result) {
                is ApiResult.Success -> result.data
                else -> {
                    val userMessage = FileMessages.refreshFailure(result)
                    logger.w(LogSource.FILE, "目录 $dirId 刷新失败（第 $page 页）：$userMessage")
                    return RefreshOutcome.Failure(userMessage)
                }
            }
            collected += data.infoList
            total = data.total
            val hasMore = data.infoList.isNotEmpty() && collected.size < total
            if (!hasMore) break
            page++
            // 全量加载每累计 5 页节流一次（挂起可取消；取消时 CancellationException 直接传播）
            if ((page - 1) % THROTTLE_EVERY_PAGES == 0) {
                delay(THROTTLE_DELAY_MS)
            }
        }

        if (accountIdOfSession != accountId) return RefreshOutcome.Failure(FileMessages.ACCOUNT_CHANGED)
        cloudFileDao.replaceDirectoryWithState(
            accountId = accountId,
            parentFileId = dirId,
            files = collected.map { it.toEntity(accountId) },
            total = total,
            allLoaded = true,
            updatedAt = System.currentTimeMillis(),
        )
        logger.i(
            LogSource.FILE,
            "目录 $dirId 刷新成功：${collected.size} 个文件（total=$total），耗时 ${System.currentTimeMillis() - startedAt}ms",
        )
        return RefreshOutcome.Success
    }

    /**
     * 取一页并对 SessionExpired 做一次性重登重试：relogin 成功则重发当页一次，
     * 重试结果（无论何种）原样返回，由调用方统一映射。
     */
    private suspend fun fetchPage(dirId: Long, page: Int, trashed: Boolean): ApiResult<FileListDto> {
        val first = api.getFileList(dirId, page, PAGE_LIMIT, trashed)
        if (first !is ApiResult.SessionExpired) return first
        if (!relogin()) return first
        return api.getFileList(dirId, page, PAGE_LIMIT, trashed)
    }

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
        const val PAGE_LIMIT = 100
        const val THROTTLE_EVERY_PAGES = 5
        const val THROTTLE_DELAY_MS = 500L
    }
}
