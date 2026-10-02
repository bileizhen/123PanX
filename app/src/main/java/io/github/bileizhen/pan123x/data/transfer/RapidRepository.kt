package io.github.bileizhen.pan123x.data.transfer

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanUploadApi
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidFile
import io.github.bileizhen.pan123x.data.file.FileRepository
import io.github.bileizhen.pan123x.data.file.FileOpsRepository
import io.github.bileizhen.pan123x.data.file.OpsOutcome
import io.github.bileizhen.pan123x.data.file.RefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** 秒传导入进度（null = 空闲）。done 为已处理完的文件数，currentPath 为刚处理完的路径。 */
data class RapidProgress(val done: Int, val total: Int, val currentPath: String)

/** 秒传导入结果：success 为成功路径；failed 为 (路径, 用户可读失败原因)。 */
data class RapidReport(val success: List<String>, val failed: List<Pair<String, String>>)

/** 秒传域失败文案（对齐 offline_service.py:285 的 ValueError 语义与）。 */
private object RapidMessages {
    const val NOT_LOGGED_IN = "请先登录"
    const val NOT_SAME_FILE = "网盘中不存在相同文件，无法秒传"
    const val CREATE_DIR_FAILED = "创建目录失败"
    const val REQUEST_FAILED = "秒传请求失败，请稍后重试"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    private const val NETWORK = "网络连接失败，请检查网络后重试"
    private const val MALFORMED = "服务器响应异常，请稍后重试"

    /** requestUpload 失败映射：ApiError 透传服务端 message（空白回退错误码），其余固定文案。 */
    fun requestFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError ->
            if (result.message.isBlank()) "秒传失败（错误码 ${result.code}）" else result.message
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：Success 由调用方先按 Reuse 判定处理，落到这里按秒传未命中兜底。
        is ApiResult.Success -> NOT_SAME_FILE
    }
}

/**
 * 秒传导入仓库（M7， / ；编排真源 offline_service.py:216-298
 * rapid_transfer）。
 *
 * 编排（逐文件串行）：建目录链（任务内 `(parentId, name) -> id` 缓存；先列目录复用同名
 * Type==1 文件夹——FileRepository.refreshDirectory 拉服务端全量进缓存后经
 * observeDirectory 查找，对齐参考源 get_dir_by_id 语义——没有再 FileOpsRepository
 * .createFolder）→ `requestUpload(fileName, size, etag, parentId, duplicate=1)` →
 * `Reuse=true` 且 fileId 非零即成功；非 Reuse 记失败"网盘中不存在相同文件，无法秒传"，
 * **绝不**读取本地文件、绝不走 S3 真传（offline_service.py:280-290 fast_upload 语义，
 * 含 reuse 但 FileId 缺失按未命中，参考源 :649-660）。
 *
 * 会话与并发：本仓库不处理 relogin，requestUpload 会话过期按单文件失败；
 * import 是逐文件串行网络调用，放调用方调度器上（不切 IO，风格对齐 UploadEngine 决策）。
 * 取消为协作式：逐文件调用 [cancel]，命中即中断循环，已完成项照常返回。
 * 目录/文件状态联动：导入成功后调用 [FileRepository.refreshDirectory](parentDirId) 一次
 * （失败仅记录，不影响导入结果）。
 */
/**
 * 秒传导入页 ViewModel 的仓库接缝（feature.offline.RapidImportViewModel 消费；接口住在
 * 被实现方一侧，同 OfflineRepositoryApi 的理由）。
 */
interface RapidImportApi {
    val progress: StateFlow<RapidProgress?>

    /** [cancel] 协作取消：逐文件检查，中止后按已成功部分正常返回报告。 */
    suspend fun import(files: List<RapidFile>, parentDirId: Long, cancel: () -> Boolean = { false }): RapidReport
}

class RapidRepository(
    upload: PanUploadApi,
    files: FileRepository,
    ops: FileOpsRepository,
    private val manager: AccountManager,
    private val logger: AppLogger,
) : RapidImportApi {

    // [import] 的 files 参数会遮蔽同名属性，因此构造入参在此取别名。
    private val uploadApi: PanUploadApi = upload
    private val fileRepository: FileRepository = files
    private val opsRepository: FileOpsRepository = ops

    /** 当前会话的 accountId；未登录 / 恢复中为 null，供测试与接线处读取。 */
    val accountIdOfSession: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    private val mutableProgress = MutableStateFlow<RapidProgress?>(null)

    /** 导入进度（null = 空闲）；每处理完一个文件更新一次，不回写 Room。 */
    override val progress: StateFlow<RapidProgress?> = mutableProgress.asStateFlow()

    /**
     * 逐文件导入秒传数据到 [parentDirId] 目录。[cancel] 在每个文件处理前轮询，命中即中断
     * （对齐参考源 offline_service.py:270-271）；空列表为 no-op。返回逐项成败，
     * 不做 UI 假成功（失败原因均为用户可读文案）。
     */
    override suspend fun import(
        files: List<RapidFile>,
        parentDirId: Long,
        // 覆写接口方法不得带默认值（默认值由 RapidImportApi 声明侧提供）。
        cancel: () -> Boolean,
    ): RapidReport {
        if (files.isEmpty()) return RapidReport(emptyList(), emptyList())
        if (accountIdOfSession == null) {
            return RapidReport(emptyList(), files.map { it.path to RapidMessages.NOT_LOGGED_IN })
        }
        // 任务内已知目录缓存（offline_service.py:230）：仅本次导入内复用，不跨任务
        val knownDirs = HashMap<Pair<Long, String>, Long>()
        val success = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val total = files.size
        mutableProgress.value = RapidProgress(done = 0, total = total, currentPath = "")

        for ((index, file) in files.withIndex()) {
            if (cancel()) break
            val path = file.path
            val fileName = if ('/' in path) path.substringAfterLast('/') else path
            val parentId = ensureParentDirs(knownDirs, path, parentDirId)
            if (parentId == null) {
                failed += path to RapidMessages.CREATE_DIR_FAILED
            } else {
                rapidTransferOne(path, fileName, file, parentId, success, failed)
            }
            mutableProgress.value = RapidProgress(done = index + 1, total = total, currentPath = path)
        }

        if (success.isNotEmpty()) {
            // 秒传改变了云端结构：目标目录缓存联动刷新一次（
            // 对齐参考源 :296-297 mark_all_dirs_dirty 的联动语义）
            when (val outcome = fileRepository.refreshDirectory(parentDirId)) {
                is RefreshOutcome.Failure ->
                    logger.w(LogSource.UPLOAD, "导入后目录 $parentDirId 刷新失败：${outcome.userMessage}")
                RefreshOutcome.Success -> Unit
            }
        }
        logger.i(LogSource.UPLOAD, "秒传导入完成：成功 ${success.size} 个，失败 ${failed.size} 个")
        return RapidReport(success.toList(), failed.toList())
    }

    /** 单文件秒传：requestUpload（duplicate=1）→ Reuse 且 fileId 非零记成功，否则记失败。 */
    private suspend fun rapidTransferOne(
        path: String,
        fileName: String,
        file: RapidFile,
        parentId: Long,
        success: MutableList<String>,
        failed: MutableList<Pair<String, String>>,
    ) {
        try {
            // 七字段 body 由 PanApi.requestUpload 构造（逐字对齐 upload_service.py:609-617）
            val result = uploadApi.requestUpload(
                fileName = fileName,
                size = file.size,
                etag = file.etag,
                parentFileId = parentId,
                duplicate = RAPID_DUPLICATE_KEEP_BOTH,
            )
            when (result) {
                is ApiResult.Success ->
                    if (result.data.reuse && result.data.fileId != 0L) {
                        success += path
                    } else {
                        // 5060 冲突与 Reuse=false 同样无法秒传，文案一致
                        failed += path to RapidMessages.NOT_SAME_FILE
                    }
                else -> failed += path to RapidMessages.requestFailure(result)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.w(LogSource.UPLOAD, "秒传失败：$path（${failure.message.orEmpty()}）")
            failed += path to (failure.message?.takeIf { it.isNotBlank() } ?: RapidMessages.REQUEST_FAILED)
        }
    }

    /**
     * 逐级创建 / 复用文件的父目录链，返回父目录 ID；任一级失败返回 null
     * （参考源 offline_service.py:257-267 `_make_parent_dirs`）。
     */
    private suspend fun ensureParentDirs(
        knownDirs: MutableMap<Pair<Long, String>, Long>,
        path: String,
        parentDirId: Long,
    ): Long? {
        if ('/' !in path) return parentDirId
        var parent = parentDirId
        for (part in path.substringBeforeLast('/').split('/')) {
            parent = ensureFolder(knownDirs, parent, part) ?: return null
        }
        return parent
    }

    /**
     * 确保 (parentId, name) 目录存在并返回其 id（参考源 :235-255 `_ensure_folder`）：
     * 缓存命中直接返回；否则列目录复用同名 Type==1 文件夹（合并导入），仍无才新建。
     * 新目录 id 经缓存间接读取（FileOpsRepository 的 OpsOutcome 不携带 id）：createFolder
     * 自带的操作后刷新若失败或未接线，兜底再拉一次列表。
     */
    private suspend fun ensureFolder(
        knownDirs: MutableMap<Pair<Long, String>, Long>,
        parentId: Long,
        name: String,
    ): Long? {
        val key = parentId to name
        knownDirs[key]?.let { return it }

        refreshDirectoryQuietly(parentId)
        reuseFolderFromCache(parentId, name)?.let { id ->
            knownDirs[key] = id
            return id
        }
        when (val outcome = opsRepository.createFolder(parentId, name)) {
            is OpsOutcome.Failure -> {
                logger.w(LogSource.UPLOAD, "秒传建目录失败：$name（${outcome.userMessage}）")
                return null
            }
            is OpsOutcome.Success -> Unit
        }
        reuseFolderFromCache(parentId, name)?.let { id ->
            knownDirs[key] = id
            return id
        }
        refreshDirectoryQuietly(parentId)
        return reuseFolderFromCache(parentId, name)?.also { knownDirs[key] = it }
    }

    /** 从 Room 缓存找同名 Type==1 文件夹（isFolder 行），对齐参考源 :240-248 的复用判定。 */
    private suspend fun reuseFolderFromCache(parentId: Long, name: String): Long? =
        fileRepository.observeDirectory(parentId).first().files
            .firstOrNull { it.isFolder && it.fileName == name }?.fileId

    /** 列目录失败保留旧缓存继续（对齐参考源 :243 `if code == 0` 才复用的容错），仅记录日志。 */
    private suspend fun refreshDirectoryQuietly(dirId: Long) {
        when (val outcome = fileRepository.refreshDirectory(dirId)) {
            is RefreshOutcome.Failure ->
                logger.w(LogSource.UPLOAD, "秒传列目录 $dirId 失败：${outcome.userMessage}")
            RefreshOutcome.Success -> Unit
        }
    }

    private companion object {
        /** duplicate=1 = 同名保留两者（参考源 upload_service.py:616 fast_upload 固定值）。 */
        const val RAPID_DUPLICATE_KEEP_BOTH = 1
    }
}
