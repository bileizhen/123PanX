package io.github.bileizhen.pan123x.data.transfer

import android.content.Context
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.DownloadSegmentDao
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskDao
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.database.UploadPartDao
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.transfer.background.TransferBackgroundLauncher
import io.github.bileizhen.pan123x.core.transfer.download.DownloadCoordinator
import io.github.bileizhen.pan123x.core.transfer.upload.UploadCoordinator
import io.github.bileizhen.pan123x.core.transfer.upload.UploadPartPlan
import io.github.bileizhen.pan123x.core.transfer.upload.UploadSource
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 分片点阵的统一视图（下载分段与上传分片共用）。
 *
 * 下载分段表里有**全部**分段及部分进度（字节级断点）；上传分片表里只有**已传**分片
 * （分片粒度断点）。本视图把两种粒度拉平成同一形状，UI 无需关心方向差异。
 */
data class TransferPartView(val index: Int, val size: Long, val transferred: Long, val done: Boolean)

/**
 * 统一传输任务视图（上传/下载共用工作台）。UI 只依赖本类，
 * 不直接接触协调器、DAO 或 OkHttp。
 *
 * 职责边界：
 * - 只做**方向分派**与两种分片表到 [TransferPartView] 的拉平，状态机全部在协调器内（M4/M5）；
 * - 账户切换由 [AccountManager.state] 驱动：未登录发空表，UI 呈现空态（多账户）；
 * - 后台执行接缝 [backgroundStart] 是构造注入的回调（默认转发 [TransferBackgroundLauncher.start]）：
 *   launcher 是 object 且持有 Context，直接调用会让仓库在 JVM 单测里无法构造。
 *   `stop` 不在这里做——由 ViewModel 在观察到任务离开活跃态时触发（同一"离开"只触发一次）。
 *
 * 任务身份（accountId + taskId）与短期 CDN/预签名 URL 解耦：恢复上传时从
 * `targetUri` 里的 SAF uri 重建 [UploadSource]，重建失败保持任务现状（通常 WAITING_USER），
 * 绝不自动重启。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferRepository(
    /**
     * 仅默认 [backgroundStart] 需要。类型可空，便于不依赖 Android 的 JVM 单测：测试无法构造
     * android Context，测试传 null 并注入自己的 [backgroundStart] 收集器。
     */
    private val context: Context?,
    private val taskDao: TransferTaskDao,
    private val segmentDao: DownloadSegmentDao,
    private val partDao: UploadPartDao,
    private val download: DownloadCoordinator,
    private val upload: UploadCoordinator,
    private val accountManager: AccountManager,
    /**
     * uri 字符串 → 上传来源；null = 不可恢复。使用 suspend，让来源解析在后台执行：
     * [io.github.bileizhen.pan123x.core.transfer.upload.ContentUriUploadSource.create] 按计划在
     * Dispatchers.IO 上执行 SAF query，非 suspend lambda 会迫使接线处
     * runBlocking——UI 线程 runBlocking 被  明确禁止。
     */
    private val uploadSourceFromUri: suspend (String) -> UploadSource?,
    private val logger: AppLogger,
    private val backgroundStart: (String, Long, String) -> Unit = { taskId, estimatedBytes, title ->
        val appContext = context
        if (appContext != null) TransferBackgroundLauncher.start(appContext, taskId, estimatedBytes, title)
    },
) : io.github.bileizhen.pan123x.feature.transfer.TransferTasksSource {

    /** 当前账户的任务流；未登录（LoggedOut/Restoring）发空表，账户切换自动重订阅。 */
    override fun observeTasks(): Flow<List<TransferTaskEntity>> = accountManager.state
        .flatMapLatest { state ->
            when (state) {
                is SessionState.Ready -> taskDao.observeTasks(state.accountId)
                else -> flowOf(emptyList())
            }
        }

    /**
     * 按 direction 分派到对应分片表：DOWNLOAD 映射 download_segments
     * （transferred = downloaded，done = 已到段尾）；UPLOAD 用任务行的 size 与 blockSize 推出
     * 完整分片计划，再按 upload_parts 里的已传集合标记——未传分片不在表里。
     */
    override fun observeParts(accountId: String, taskId: String): Flow<List<TransferPartView>> = flow {
        val row = taskDao.get(accountId, taskId)
        when {
            row == null -> emit(emptyList())
            row.direction == TransferDirection.DOWNLOAD -> emitAll(
                segmentDao.observe(accountId, taskId).map { segments ->
                    segments.map { segment ->
                        val total = (segment.end - segment.start).coerceAtLeast(0L)
                        TransferPartView(
                            index = segment.segmentIndex,
                            size = total,
                            transferred = segment.downloaded.coerceIn(0L, total),
                            done = segment.downloaded >= total,
                        )
                    }
                },
            )

            else -> emitAll(
                partDao.observe(accountId, taskId).map { uploaded ->
                    val blockSize = if (row.blockSize > 0) row.blockSize else UploadPartPlan.BLOCK_SIZE
                    val totalParts = UploadPartPlan.totalParts(row.size, blockSize)
                    val uploadedParts = uploaded.mapTo(HashSet()) { it.partNumber }
                    (1..totalParts).map { partNumber ->
                        val length = UploadPartPlan.lengthOf(partNumber, row.size, blockSize).toLong()
                        val done = partNumber in uploadedParts
                        TransferPartView(
                            index = partNumber,
                            size = length,
                            transferred = if (done) length else 0L,
                            done = done,
                        )
                    }
                },
            )
        }
    }

    override suspend fun pause(accountId: String, taskId: String) {
        when (directionOf(accountId, taskId)) {
            TransferDirection.DOWNLOAD -> download.pause(taskId)
            TransferDirection.UPLOAD -> upload.pause(taskId)
            null -> logger.w(LogSource.APP, "暂停失败：任务不存在或不属于当前账户：$taskId")
        }
    }

    override suspend fun resume(accountId: String, taskId: String) {
        val row = taskDao.get(accountId, taskId)
        if (row == null) {
            logger.w(LogSource.APP, "恢复失败：任务不存在或不属于当前账户：$taskId")
            return
        }
        // 已在跑或已完成的行不重复申请后台执行，避免后台 job 泄漏（协调器内部会安全忽略，但
        // launcher 的 start 不去重）。
        val dormant = row.state !in ACTIVE_STATES && row.state != TransferState.COMPLETED
        when (row.direction) {
            TransferDirection.DOWNLOAD -> {
                download.resume(taskId)
                if (dormant) backgroundStart(taskId, row.size, row.fileName)
            }

            TransferDirection.UPLOAD -> {
                // 上传来源是 SAF uri，进程重启后内存来源丢失，必须从 targetUri 重建。
                val source = row.targetUri.takeIf { it.isNotBlank() }?.let { uploadSourceFromUri(it) }
                if (source == null) {
                    logger.w(
                        LogSource.UPLOAD,
                        "恢复失败：无法从 uri 重建上传来源，任务保持等待用户：${row.fileName}",
                    )
                    return
                }
                upload.resume(taskId, source)
                if (dormant) backgroundStart(taskId, source.size, source.displayName)
            }
        }
    }

    override suspend fun cancel(accountId: String, taskId: String) {
        when (directionOf(accountId, taskId)) {
            TransferDirection.DOWNLOAD -> download.cancel(taskId)
            TransferDirection.UPLOAD -> upload.cancel(taskId)
            null -> logger.w(LogSource.APP, "取消失败：任务不存在或不属于当前账户：$taskId")
        }
    }

    /** 同名冲突只存在于上传（code==5060）；下载行请求处理冲突视为编程错误，记录后忽略。 */
    override suspend fun resolveConflict(accountId: String, taskId: String, policy: ConflictPolicy) {
        when (directionOf(accountId, taskId)) {
            TransferDirection.UPLOAD -> upload.resolveConflict(taskId, policy)
            TransferDirection.DOWNLOAD -> logger.w(LogSource.APP, "下载任务不存在同名冲突，忽略：$taskId")
            null -> logger.w(LogSource.APP, "处理冲突失败：任务不存在或不属于当前账户：$taskId")
        }
    }

    /** 清理当前账户的已完成/已取消终态行（分片/分段行随复合外键 CASCADE 清理）。 */
    override suspend fun clearFinished(accountId: String) {
        taskDao.deleteFinished(accountId)
    }

    /**
     * 加入上传队列并申请后台执行：先经 [UploadCoordinator.enqueue]
     * 落库（先落库再执行），成功后用来源大小估算后台传输量。
     */
    suspend fun enqueueUpload(
        source: UploadSource,
        parentFileId: Long,
        policy: ConflictPolicy = ConflictPolicy.ASK,
    ): String {
        val taskId = upload.enqueue(source, parentFileId, policy)
        backgroundStart(taskId, source.size, source.displayName)
        return taskId
    }

    private suspend fun directionOf(accountId: String, taskId: String): TransferDirection? =
        taskDao.get(accountId, taskId)?.direction

    private companion object {
        val ACTIVE_STATES = setOf(
            TransferState.QUEUED,
            TransferState.RESOLVING,
            TransferState.RUNNING,
            TransferState.COMPLETING,
        )
    }
}
