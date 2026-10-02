package io.github.bileizhen.pan123x.data.share

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.network.*
import io.github.bileizhen.pan123x.core.transfer.download.DownloadSource
import io.github.bileizhen.pan123x.core.transfer.download.LaunchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

interface SharedFilesActions {
    suspend fun info(key: String): ApiResult<SharedInfoDto?> = ApiResult.Success(null)
    suspend fun list(key: String, password: String, parentId: Long, page: Int, next: String): ApiResult<FileListDto>
    suspend fun save(key: String, password: String, files: List<FileItemDto>, targetId: Long): String?
    suspend fun download(key: String, password: String, files: List<FileItemDto>, tree: String?): SharedQueueResult
}

data class SharedQueueResult(val queued: Int, val error: String? = null)

class SharedFilesRepository(
    private val api: PanSharedFilesApi,
    private val manager: AccountManager,
    private val relogin: suspend () -> Boolean,
    private val enqueue: suspend (DownloadSource, String?) -> LaunchOutcome,
    private val onSaved: suspend (String, Long) -> Unit,
    private val pollDelay: suspend () -> Unit = { delay(1_000) },
) : SharedFilesActions {
    override suspend fun info(key: String) = api.sharedInfo(key)
    override suspend fun list(key: String, password: String, parentId: Long, page: Int, next: String) =
        api.sharedFiles(key, password, parentId, page, next)

    override suspend fun save(key: String, password: String, files: List<FileItemDto>, targetId: Long): String? {
        val accountId = (manager.state.value as? SessionState.Ready)?.accountId ?: return "请先登录后保存至云盘"
        if (files.isEmpty()) return "请先选择文件"
        if (targetId < 0) return "请选择有效的目标目录"
        suspend fun <T> authorized(call: suspend () -> ApiResult<T>): ApiResult<T> {
            if ((manager.state.value as? SessionState.Ready)?.accountId != accountId) return ApiResult.ApiError(-1, "账户已切换，请重新操作")
            val result = call()
            if (result != ApiResult.SessionExpired) return result
            return if (relogin() && (manager.state.value as? SessionState.Ready)?.accountId == accountId) call() else ApiResult.SessionExpired
        }
        val submitted = authorized { api.saveSharedFiles(key, password, files.distinctBy { it.fileId }, targetId) }
        if (submitted !is ApiResult.Success) return sharedError(submitted)
        var task = submitted.data
        repeat(120) {
            if ((manager.state.value as? SessionState.Ready)?.accountId != accountId) return "账户已切换，请在原账户检查转存结果"
            if (task.failed) return "转存失败，请稍后重试"
            if (task.complete) {
                try { onSaved(accountId, targetId) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A refresh failure cannot undo a completed save. */ }
                return null
            }
            pollDelay()
            when (val result = authorized { api.sharedSaveStatus(task.taskId) }) {
                is ApiResult.Success -> task = result.data
                else -> return sharedError(result)
            }
        }
        return "转存任务仍在处理，请稍后检查目标目录"
    }

    override suspend fun download(key: String, password: String, files: List<FileItemDto>, tree: String?): SharedQueueResult {
        val accountId = (manager.state.value as? SessionState.Ready)?.accountId ?: return SharedQueueResult(0, "请先登录后下载")
        val collected = linkedMapOf<Long, FileItemDto>()
        val visited = mutableSetOf<Long>()
        suspend fun collect(items: List<FileItemDto>, prefix: String, depth: Int): String? {
            if (depth > 32 || collected.size > 2_000) return "文件较多，请分批下载"
            for (file in items) {
                if (!file.isFolder) {
                    collected.putIfAbsent(file.fileId, file.copy(fileName = prefix + file.fileName))
                    continue
                }
                if (!visited.add(file.fileId)) continue
                var next = "0"
                var page = 1
                val markers = mutableSetOf<String>()
                do {
                    val result = list(key, password, file.fileId, page, next)
                    if (result !is ApiResult.Success) return sharedError(result)
                    collect(result.data.infoList, "$prefix${file.fileName} - ", depth + 1)?.let { return it }
                    next = result.data.next
                    page++
                    if (result.data.infoList.isEmpty() || next == "-1") break
                    if (!markers.add(next) || page > 100) return "分享列表分页异常，请重试"
                } while (true)
            }
            return null
        }
        collect(files, "", 0)?.let { return SharedQueueResult(0, it) }
        if (collected.isEmpty()) return SharedQueueResult(0, "所选文件夹为空")
        var queued = 0
        for (file in collected.values) {
            if ((manager.state.value as? SessionState.Ready)?.accountId != accountId) return SharedQueueResult(queued, "账户已切换，请重新操作")
            val source = DownloadSource(file.fileId, file.fileName, file.size, file.etag, file.s3KeyFlag, false, key, password)
            when (val outcome = enqueue(source, tree)) {
                is LaunchOutcome.Queued -> queued++
                is LaunchOutcome.Failed -> return SharedQueueResult(queued, outcome.userMessage)
            }
        }
        return SharedQueueResult(queued)
    }
}

internal fun sharedError(result: ApiResult<*>): String = when (result) {
    is ApiResult.ApiError -> result.message.ifBlank { "分享不可用，请检查链接和提取码" }
    is ApiResult.NetworkError -> "网络连接失败，请稍后重试"
    is ApiResult.ParseError -> result.message
    ApiResult.SessionExpired -> "登录已过期，请重新登录"
    is ApiResult.Success -> "操作未完成，请重试"
}
