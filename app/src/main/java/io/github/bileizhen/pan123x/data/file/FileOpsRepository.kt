package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateDao
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanFileOpsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 文件操作结果：Success 可携带提示性 message（null 表示无附加文案）。 */
sealed interface OpsOutcome {
    data class Success(val message: String? = null) : OpsOutcome
    data class Failure(val userMessage: String) : OpsOutcome
}

/**
 * 文件操作仓库（数据层）：新建 / 重命名 / 删除恢复 /
 * 永久删除 / 移动 / 复制。
 *
 * 通用约定：
 * - 所有请求为非幂等 POST（复制轮询除外），协议层不自动重试；仅在 code==2 会话过期时
 *   经注入的 [relogin] 重登一次并重试当次调用一次；
 * - 操作成功后先同步本地缓存（能立即反映的先行），再删除目录状态行（dirty 语义）并
 *   调用 [refresher] 后台刷新服务端状态——不允许 UI 假成功；
 * - [refresher] 由接线方（AppContainer）注入 FileRepository::refreshDirectory 的忽略
 *   结果包装；为 null 时仅做 dirty 失效：UI 的 observeDirectory 会因状态行缺失回到
 *   "未完整加载"展示，ViewModel 下次刷新拉新（可接受降级）。
 */
class FileOpsRepository(
    private val api: PanFileOpsApi,
    private val cloudFileDao: CloudFileDao,
    private val directoryStateDao: DirectoryStateDao,
    private val manager: AccountManager,
    private val relogin: suspend () -> Boolean,
    private val logger: AppLogger,
) {

    /** 由 AppContainer 注入（见类注释）；测试可注入记录器。 */
    var refresher: (suspend (Long) -> Unit)? = null

    private val currentAccountId: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    /**
     * 新建文件夹：先做同名预查（参考源 mkdir 行为：当前目录缓存已有同名条目直接按成功
     * 返回，不发请求）；否则走 upload_request，成功后失效父目录缓存并刷新。
     */
    suspend fun createFolder(parentId: Long, name: String): OpsOutcome {
        val accountId = currentAccountId ?: return OpsOutcome.Failure(FileOpsMessages.NOT_LOGGED_IN)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.EMPTY_NAME)

        val existing = cloudFileDao.observeDirectory(accountId, parentId).first()
        if (existing.any { it.fileName == trimmed }) {
            logger.i(LogSource.FILE, "目录 $parentId 已存在同名条目，跳过新建请求")
            return OpsOutcome.Success(FileOpsMessages.NAME_EXISTS)
        }

        val result = withReauth { api.createFolder(parentId, trimmed) }
        if (result !is ApiResult.Success) return OpsOutcome.Failure(FileOpsMessages.opFailure(result))
        logger.i(LogSource.FILE, "目录 $parentId 新建文件夹成功，新目录 id=${result.data}")
        invalidateAndRefresh(accountId, parentId)
        return OpsOutcome.Success()
    }

    /** 重命名：成功后先本地改名（UI 立即反映），再失效父目录并后台刷新。 */
    suspend fun rename(file: CloudFileEntity, newName: String): OpsOutcome {
        val accountId = currentAccountId ?: return OpsOutcome.Failure(FileOpsMessages.NOT_LOGGED_IN)
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.EMPTY_NAME)

        val result = withReauth { api.renameFile(file.fileId, trimmed) }
        if (result !is ApiResult.Success) return OpsOutcome.Failure(FileOpsMessages.opFailure(result))
        cloudFileDao.upsert(listOf(file.copy(fileName = trimmed)))
        logger.i(LogSource.FILE, "文件 ${file.fileId} 重命名成功")
        invalidateAndRefresh(accountId, file.parentFileId)
        return OpsOutcome.Success()
    }

    /** 删除（进回收站）：逐个循环，聚合成 "成功 N 个，失败 M 个"。 */
    suspend fun trash(files: List<CloudFileEntity>): OpsOutcome = trashInternal(files, restore = false)

    /** 从回收站恢复：逐个循环，聚合成 "成功 N 个，失败 M 个"。 */
    suspend fun restore(files: List<CloudFileEntity>): OpsOutcome = trashInternal(files, restore = true)

    /** 回收站永久删除：整列表一次提交；成功后失效来源目录（M3 UI 仅在确认后调用）。 */
    suspend fun deleteForever(files: List<CloudFileEntity>): OpsOutcome {
        val accountId = currentAccountId ?: return OpsOutcome.Failure(FileOpsMessages.NOT_LOGGED_IN)
        if (files.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.EMPTY_SELECTION)

        val result = withReauth { api.deleteForever(files.map { it.fileId }) }
        if (result !is ApiResult.Success) return OpsOutcome.Failure(FileOpsMessages.opFailure(result))
        logger.i(LogSource.FILE, "永久删除 ${files.size} 个文件成功")
        files.map { it.parentFileId }.distinct().forEach { invalidateAndRefresh(accountId, it) }
        return OpsOutcome.Success()
    }

    /**
     * 移动到目标目录：过滤"已在目标目录"与"把目录移动进自身"的项；整列表一次提交；
     * 成功后源目录与目标目录都要失效（修正参考实现 move_files 只标目标目录的疏漏）。
     */
    suspend fun move(files: List<CloudFileEntity>, targetDirId: Long): OpsOutcome {
        val accountId = currentAccountId ?: return OpsOutcome.Failure(FileOpsMessages.NOT_LOGGED_IN)
        if (files.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.EMPTY_SELECTION)

        val movable = files.filter { it.fileId != targetDirId && it.parentFileId != targetDirId }
        if (movable.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.ALREADY_IN_TARGET)

        val result = withReauth { api.moveFiles(movable.map { it.fileId }, targetDirId) }
        if (result !is ApiResult.Success) return OpsOutcome.Failure(FileOpsMessages.opFailure(result))
        logger.i(LogSource.FILE, "移动 ${movable.size} 个文件到目录 $targetDirId 成功")
        (movable.map { it.parentFileId } + targetDirId).distinct()
            .forEach { invalidateAndRefresh(accountId, it) }
        return OpsOutcome.Success()
    }

    /**
     * 复制到目标目录：提交异步任务 + 1s 间隔轮询（参考源 copy_files 节奏，最多 60 次）。
     * fileList 优先用当前目录缓存的完整对象（PascalCase 13 字段 + DriveId:0），缓存缺失
     * 的项降级 `{"FileId":x}`，不静默丢弃。轮询挂起可取消（CancellationException 直接传播）。
     */
    suspend fun copy(files: List<CloudFileEntity>, targetDirId: Long): OpsOutcome {
        val accountId = currentAccountId ?: return OpsOutcome.Failure(FileOpsMessages.NOT_LOGGED_IN)
        if (files.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.EMPTY_SELECTION)

        val sourceDirId = files.first().parentFileId
        val cacheById = cloudFileDao.observeDirectory(accountId, sourceDirId).first().associateBy { it.fileId }
        val fileList = files.map { file -> cacheById[file.fileId]?.toCopyJsonObject() ?: fileIdOnly(file.fileId) }

        val submitted = withReauth { api.submitCopy(fileList, targetDirId) }
        if (submitted !is ApiResult.Success) return OpsOutcome.Failure(FileOpsMessages.opFailure(submitted))
        val taskId = submitted.data.taskId ?: return OpsOutcome.Failure(FileOpsMessages.NO_TASK_ID)

        repeat(POLL_MAX_ATTEMPTS) {
            when (val result = withReauth { api.pollCopyTask(taskId) }) {
                is ApiResult.Success -> {
                    val status = result.data.status
                    when {
                        // 防御兜底（参考源注释）：响应不含 status 时视为成功，用户可刷新核对
                        status == null -> {
                            logger.w(LogSource.FILE, "复制任务响应缺少 status，按成功处理")
                            return finishCopy(accountId, targetDirId)
                        }
                        status == COPY_STATUS_SUCCESS -> return finishCopy(accountId, targetDirId)
                        status == COPY_STATUS_FAILED -> return OpsOutcome.Failure(
                            result.data.failMsg?.takeIf { it.isNotBlank() } ?: FileOpsMessages.COPY_TASK_FAILED,
                        )
                        // status 1（进行中）/ 4（等待）：继续轮询
                    }
                }
                else -> return OpsOutcome.Failure(FileOpsMessages.opFailure(result))
            }
            delay(POLL_INTERVAL_MS)
        }
        return OpsOutcome.Failure(FileOpsMessages.COPY_TIMEOUT)
    }

    /** 删除 / 恢复逐个循环；已生效项先同步本地缓存再失效目录，最后聚合结果。 */
    private suspend fun trashInternal(files: List<CloudFileEntity>, restore: Boolean): OpsOutcome {
        val accountId = currentAccountId ?: return OpsOutcome.Failure(FileOpsMessages.NOT_LOGGED_IN)
        if (files.isEmpty()) return OpsOutcome.Failure(FileOpsMessages.EMPTY_SELECTION)

        val succeeded = mutableListOf<CloudFileEntity>()
        var firstFailure: String? = null
        for (file in files) {
            val result = withReauth { api.trashFile(file.fileId, restore) }
            if (result is ApiResult.Success) {
                if (result.data.abnormalFileIds.isNotEmpty()) {
                    logger.w(LogSource.FILE, "文件 ${file.fileId} ${opName(restore)}返回异常列表，n=${result.data.abnormalFileIds.size}")
                }
                succeeded += file
            } else if (firstFailure == null) {
                firstFailure = FileOpsMessages.opFailure(result)
            }
        }

        if (succeeded.isNotEmpty()) {
            // 删除的文件必须立刻从所在目录缓存消失；恢复不改普通目录缓存
            if (!restore) removeRowsFromParentCaches(accountId, succeeded)
            succeeded.map { it.parentFileId }.distinct().forEach { invalidateAndRefresh(accountId, it) }
            logger.i(LogSource.FILE, "${opName(restore)}文件成功 ${succeeded.size} 个")
        }

        return when {
            firstFailure == null -> OpsOutcome.Success()
            succeeded.isEmpty() -> OpsOutcome.Failure(firstFailure)
            else -> OpsOutcome.Failure(
                FileOpsMessages.aggregate(succeeded.size, files.size - succeeded.size, firstFailure),
            )
        }
    }

    /**
     * 把已删除项从其父目录缓存中剔除：无按 id 删除的 DAO 方法（不能改 core/database），
     * 读目录当前值过滤后经 replaceDirectoryWithState 原子回写，total / allLoaded 沿用
     * 原状态行（total 扣减已删数量），随后 invalidateAndRefresh 会再刷新校准。
     */
    private suspend fun removeRowsFromParentCaches(accountId: String, removed: List<CloudFileEntity>) {
        removed.groupBy { it.parentFileId }.forEach { (parentId, group) ->
            val snapshot = cloudFileDao.observeDirectory(accountId, parentId).first()
            val removedIds = group.mapTo(mutableSetOf()) { it.fileId }
            val remaining = snapshot.filterNot { it.fileId in removedIds }
            val state = directoryStateDao.get(accountId, parentId)
            cloudFileDao.replaceDirectoryWithState(
                accountId = accountId,
                parentFileId = parentId,
                files = remaining,
                total = state?.total?.minus(group.size) ?: remaining.size,
                allLoaded = state?.allLoaded ?: false,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun finishCopy(accountId: String, targetDirId: Long): OpsOutcome {
        invalidateAndRefresh(accountId, targetDirId)
        logger.i(LogSource.FILE, "复制任务完成，目标目录 $targetDirId 已失效待刷新")
        return OpsOutcome.Success()
    }

    /**
     * 会话过期统一处理：code==2 且 relogin 成功时重试当次调用一次；其余结果原样返回，
     * 由调用方映射文案。重试结果不再做第二次重登。
     */
    private suspend fun <T> withReauth(block: suspend () -> ApiResult<T>): ApiResult<T> {
        val first = block()
        if (first !is ApiResult.SessionExpired) return first
        if (!relogin()) return first
        return block()
    }

    /** 目录状态行删除即 dirty 语义；随后尽力触发后台刷新，失败不影响操作结果。 */
    private suspend fun invalidateAndRefresh(accountId: String, dirId: Long) {
        directoryStateDao.delete(accountId, dirId)
        val refresh = refresher ?: return
        try {
            refresh(dirId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.w(LogSource.FILE, "目录 $dirId 操作后刷新失败：${failure.message.orEmpty()}")
        }
    }

    /**
     * 复制任务的完整文件对象：PascalCase 13 字段 + DriveId:0，与参考源"源目录完整信息 +
     * setdefault(DriveId, 0)"一致。ContentType / Hidden / StarredStatus / PinYin 未入
     * Room 缓存，按参考对象形状给默认值（服务端以 FileId + targetFileId 为准）。
     */
    private fun CloudFileEntity.toCopyJsonObject(): JsonObject = buildJsonObject {
        put("FileId", fileId)
        put("ParentFileId", parentFileId)
        put("FileName", fileName)
        put("Type", if (isFolder) 1 else 0)
        put("Size", size)
        put("Etag", etag)
        put("S3KeyFlag", s3KeyFlag)
        put("ContentType", "")
        put("CreateAt", createAt)
        put("UpdateAt", updateAt)
        put("Hidden", false)
        put("StarredStatus", false)
        put("PinYin", "")
        put("DriveId", 0)
    }

    private fun fileIdOnly(fileId: Long): JsonObject = buildJsonObject { put("FileId", fileId) }

    private fun opName(restore: Boolean) = if (restore) "恢复" else "删除"

    private companion object {
        const val COPY_STATUS_SUCCESS = 2
        const val COPY_STATUS_FAILED = 3
        const val POLL_MAX_ATTEMPTS = 60
        const val POLL_INTERVAL_MS = 1_000L
    }
}
