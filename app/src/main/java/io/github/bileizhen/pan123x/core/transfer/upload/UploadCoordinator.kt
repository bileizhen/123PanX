package io.github.bileizhen.pan123x.core.transfer.upload

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskDao
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.database.UploadPartDao
import io.github.bileizhen.pan123x.core.database.UploadPartEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.PanUploadApi
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadEngine
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadPartSnapshot
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadRequest
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 上传执行接缝：[UploadEngine] 的窄接口。签名与引擎 `upload(request, progress, session, parts)`
 * 完全一致，使协调器可在纯 JVM 单测中替换为脚本化替身，无需 MockWebServer，
 * 也不强迫 [UploadEngine] 反向依赖协调器。
 */
fun interface UploadExecutor {
    suspend fun upload(
        request: UploadRequest,
        progress: (uploaded: Long, total: Long) -> Unit,
        session: (UploadSession) -> Unit,
        parts: (List<UploadPartSnapshot>) -> Unit,
    ): UploadOutcome
}

/**
 * 可选能力：来源可提供稳定的 uri 标识（例如 SAF `ContentUriUploadSource`）。
 *
 * 为什么需要它：[UploadSource] 的冻结接口不暴露底层 uri，而上传任务需要把来源 uri
 * 持久化到 `transfer_tasks.targetUri` 供诊断 / 展示。让 SAF 实现额外实现本接口即可写入；
 * 未实现时 `targetUri` 留空，不影响上传本身（续传时来源由调用方经 [UploadCoordinator.resume]
 * 重新注入，见该方法 KDoc）。
 */
interface UploadSourceLocator {
    val sourceUri: String
}

/**
 * 上传协调器：把 S3 multipart 上传引擎（[UploadExecutor]）串成可恢复的
 * 状态机，并把任务状态与分片计划持久化到 Room。与 [io.github.bileizhen.pan123x.core.transfer.download.DownloadCoordinator]
 * 对称设计。
 *
 * 为什么这样设计：
 * - **先落库再执行**：enqueue 先写入 QUEUED 行，再在应用级 scope 启动任务协程；进程被杀也不丢任务，
 *   重启后 recoverOnStart 把遗留活跃行转 WAITING_USER 等用户显式继续，**绝不自动重启**；
 * - **会话字段立即落库**：引擎获得 S3 会话后回调，协调器在回调内立即把 bucket/storageNode/uploadKey/
 *   uploadId/fileId/etag/blockSize 写入任务行（先落库再执行），随后才进行分片 PUT；
 * - **任务身份与短期预签名 URL 解耦**：持久化的只有 S3 会话字段与分片计划，恢复时按  校验后复用；
 * - **暂停/取消 = 取消协程 + NonCancellable 内落库终态**：取消 CANCELED 清空 upload_parts（对齐下载
 *   取消清 download_segments），暂停 PAUSED 保留分片表以便续传；
 * - **进度写 Room 节流**到 [progressThrottleMs]，分片计划在计划确定时整体替换、此后
 *   单分片完成增量标记（分片粒度、低频，不节流）。
 *
 * 单测接线：主构造函数接收窄接口 [UploadExecutor]，可在纯 JVM 下注入替身；生产接线用次构造函数传入
 * 真实现 [UploadEngine]。任务协程运行在 [attach] 注入的应用级 scope 上，应用应使用后台调度器
 * （推荐 Dispatchers.IO），本类不额外切换调度器，以保证单测在 TestDispatcher 下完全确定。
 */
class UploadCoordinator(
    private val executor: UploadExecutor,
    private val taskDao: TransferTaskDao,
    private val partDao: UploadPartDao,
    private val manager: AccountManager,
    private val logger: AppLogger,
    private val progressThrottleMs: Long = 400,
    private val taskLimit: () -> Int = { 3 },
) {
    /** Published only after the server-confirmed result and COMPLETED row have been persisted. */
    var onUploaded: (accountId: String) -> Unit = {}

    /**
     * 生产接线：注入引擎与来源解析器。`api` 参数保留在
     * 接口中；元数据请求细节封装在 [UploadEngine] 内，协调器自身不需要它。
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        api: PanUploadApi,
        engine: UploadEngine,
        taskDao: TransferTaskDao,
        partDao: UploadPartDao,
        manager: AccountManager,
        logger: AppLogger,
        progressThrottleMs: Long = 400,
        taskLimit: () -> Int = { 3 },
    ) : this(
        executor = UploadExecutor { request, progress, session, parts ->
            engine.upload(request, progress, session, parts)
        },
        taskDao = taskDao,
        partDao = partDao,
        manager = manager,
        logger = logger,
        progressThrottleMs = progressThrottleMs,
        taskLimit = taskLimit,
    )

    /** 应用级 scope：任务协程在此启动，从而脱离 ViewModel/UI 生命周期。 */
    @Volatile
    private var appScope: CoroutineScope? = null

    private val jobs = ConcurrentHashMap<String, Job>()
    private val slots = io.github.bileizhen.pan123x.core.transfer.DynamicTaskGate(taskLimit)

    /** 终态意图：requestTerminal 写入后取消协程，由 NonCancellable 分支读取（对应下载的 requested）。 */
    private val requested = ConcurrentHashMap<String, TransferState>()

    /** enqueue 时捕获的完整来源（SAF uri 无法从 DB 还原），供同一进程内的暂停/继续/冲突重跑复用。 */
    private val sources = ConcurrentHashMap<String, UploadSource>()

    private val currentAccountId: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    /** 由 AppContainer 注入应用级 scope；必须在 enqueue 之前调用。 */
    fun attach(scope: CoroutineScope) {
        appScope = scope
    }

    /** 观察某账户的全部传输任务（按创建时间倒序）；工作台统一视图。 */
    fun observeTasks(accountId: String): Flow<List<TransferTaskEntity>> = taskDao.observeTasks(accountId)

    /** 观察某任务的分片计划（按分片号升序），供工作台分片点阵使用。 */
    fun observeParts(accountId: String, taskId: String): Flow<List<UploadPartEntity>> =
        partDao.observe(accountId, taskId)

    /**
     * 入队一个上传任务并返回 taskId。
     *
     * 先落库（QUEUED）再启动任务协程：即便进程随即被杀，任务行仍然存在，
     * recoverOnStart 会把它转成 WAITING_USER。未登录时无法确定 accountId，抛 IllegalStateException。
     *
     * 落库字段：`etag` 先留空（本地 MD5 由引擎算出后经 session 回调写入，）；`fileId` 先留空
     * （上传时它是 up_file_id，会话建立后才有）；`targetUri` 写入来源 uri（若来源实现
     * [UploadSourceLocator]，否则留空）；`sourceMtime` 记录来源修改时间供  续传校验。
     */
    suspend fun enqueue(
        source: UploadSource,
        parentFileId: Long,
        policy: ConflictPolicy = ConflictPolicy.ASK,
    ): String {
        val accountId = currentAccountId
            ?: throw IllegalStateException(UploadMessages.NOT_LOGGED_IN)
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        taskDao.upsert(
            TransferTaskEntity(
                accountId = accountId,
                taskId = taskId,
                fileId = null,
                fileName = source.displayName,
                direction = TransferDirection.UPLOAD,
                state = TransferState.QUEUED,
                size = source.size,
                etag = "",
                targetUri = (source as? UploadSourceLocator)?.sourceUri.orEmpty(),
                parentFileId = parentFileId,
                sourceMtime = source.lastModified,
                blockSize = UploadPartPlan.BLOCK_SIZE,
                downloadedBytes = 0,
                createTime = now,
                updateTime = now,
                error = null,
            ),
        )
        sources[taskId] = source
        logger.i(LogSource.UPLOAD, "加入上传队列：${source.displayName}")
        startTask(RunState(accountId, taskId, source, parentFileId, policy, resumeSession = null))
        return taskId
    }

    /**
     * 恢复一个上传任务：读任务行 → 用行里的 S3 会话字段重建 [UploadSession] 作为 `resumeSession`
     * 传给引擎（五字段不齐或来源已变化则传 null 让它重走完整流程）→ 重新执行。
     *
     * **为什么需要 [source] 参数**：上传来源是 SAF uri，[UploadSource] 的冻结接口不暴露 uri，协调器
     * 无法仅凭任务行重建来源；进程重启后内存来源已丢失，因此由调用方（UI/Repository）重新选择或
     * 持有 uri 后注入。这与 `DownloadCoordinator.resume(taskId, source)` 的设计一致。
     *
     * 续传校验：会话五字段（bucket/storageNode/uploadKey/uploadId/fileId）全部非空才复用；
     * 且 `size` 与 `sourceMtime` 必须与当前来源一致（mtime 取不到时按不校验处理）。任一不满足即
     * 清空分片表与 S3 会话，从头走完整流程。
     */
    suspend fun resume(taskId: String, source: UploadSource? = null) {
        val accountId = currentAccountId
            ?: throw IllegalStateException(UploadMessages.NOT_LOGGED_IN)
        if (jobs.containsKey(taskId)) {
            logger.i(LogSource.UPLOAD, "任务已在运行，忽略重复恢复：$taskId")
            return
        }
        val row = taskDao.get(accountId, taskId)
        if (row == null) {
            logger.w(LogSource.UPLOAD, "恢复失败：任务不存在或不属于当前账户：$taskId")
            return
        }
        if (row.state == TransferState.COMPLETED) {
            logger.i(LogSource.UPLOAD, "任务已完成，无需恢复：${row.fileName}")
            return
        }
        val effective = source ?: sources[taskId]
        if (effective == null) {
            logger.w(LogSource.UPLOAD, "恢复失败：缺少上传来源，需调用方注入 UploadSource：${row.fileName}")
            return
        }

        val storedSession = row.toResumeSession()
        val changed = row.size != effective.size || mtimeChanged(row.sourceMtime, effective.lastModified)
        val now = System.currentTimeMillis()
        if (changed) {
            logger.i(LogSource.UPLOAD, "${row.fileName} 来源已变化，重置断点后重新上传")
            partDao.clear(accountId, taskId)
            taskDao.upsert(
                row.copy(
                    size = effective.size,
                    sourceMtime = effective.lastModified,
                    etag = "",
                    fileId = null,
                    bucket = "",
                    storageNode = "",
                    uploadKey = "",
                    uploadId = "",
                    downloadedBytes = 0,
                    state = TransferState.QUEUED,
                    error = null,
                    updateTime = now,
                ),
            )
        } else {
            taskDao.upsert(row.copy(state = TransferState.QUEUED, error = null, updateTime = now))
        }
        sources[taskId] = effective
        val resumeSession = if (changed) null else storedSession
        startTask(RunState(accountId, taskId, effective, row.parentFileId, ConflictPolicy.ASK, resumeSession))
    }

    /** 暂停：取消任务协程，由 NonCancellable 分支落库 PAUSED，保留分片表与 S3 会话以便续传。 */
    suspend fun pause(taskId: String) = requestTerminal(taskId, TransferState.PAUSED)

    /** 取消：取消任务协程，落库 CANCELED，并清空分片表（对齐下载取消清 download_segments）。 */
    suspend fun cancel(taskId: String) = requestTerminal(taskId, TransferState.CANCELED)

    /**
     * 同名冲突已由用户决定：写入策略后复用同一行重新执行 [perform]，此时引擎以策略值作为
     * `duplicate` 重发 upload_request。
     *
     * 需要内存中仍持有该任务的 [UploadSource]（同进程冲突必然满足）；若来源已丢失（如进程重启后
     * 才处理冲突），记录日志并保持 WAITING_USER，由上层改用 [resume] 重新注入来源。
     */
    suspend fun resolveConflict(taskId: String, policy: ConflictPolicy) {
        val accountId = currentAccountId
            ?: throw IllegalStateException(UploadMessages.NOT_LOGGED_IN)
        val row = taskDao.get(accountId, taskId)
        if (row == null) {
            logger.w(LogSource.UPLOAD, "处理冲突失败：任务不存在或不属于当前账户：$taskId")
            return
        }
        val source = sources[taskId]
        if (source == null) {
            logger.w(LogSource.UPLOAD, "处理冲突失败：缺少上传来源，请改用 resume 重新注入：${row.fileName}")
            return
        }
        taskDao.upsert(
            row.copy(state = TransferState.QUEUED, error = null, updateTime = System.currentTimeMillis()),
        )
        logger.i(LogSource.UPLOAD, "已按用户选择重试上传：${row.fileName}")
        startTask(RunState(accountId, taskId, source, row.parentFileId, policy, resumeSession = null))
    }

    /**
     * 进程重启后的恢复：把遗留的 QUEUED/RESOLVING/RUNNING/COMPLETING **上传**行统一转为
     * WAITING_USER，绝不自动重启——是否继续由用户显式决定。只处理 UPLOAD 方向，避免与下载协调器互相
     * 改写状态；跨账户处理。
     */
    suspend fun recoverOnStart() {
        val leftovers = taskDao.activeTasksByDirection(TransferDirection.UPLOAD.name)
        if (leftovers.isEmpty()) return
        val now = System.currentTimeMillis()
        leftovers.forEach { row ->
            taskDao.upsert(row.copy(state = TransferState.WAITING_USER, updateTime = now))
        }
        logger.i(LogSource.UPLOAD, "启动恢复：${leftovers.size} 个未完成上传转为等待用户继续（不自动重启）")
    }

    // ---- 任务执行 ----

    /** 在应用级 scope 启动任务协程，并把整个执行的异常/取消收敛为持久终态。 */
    private fun startTask(run: RunState) {
        val scope = appScope
            ?: error("UploadCoordinator.attach(scope) 必须在 enqueue/resume 之前调用")
        // LAZY + 先登记再 start：确保任务体执行时 jobs 已包含该 taskId（避免竞态漏记）。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                slots.withPermit { perform(run) }
            } catch (cancellation: CancellationException) {
                // 暂停/取消：从 IO 返回已取消的上下文会跳过持久化，因此整段清理必须 NonCancellable。
                withContext(NonCancellable) {
                    settle(run, requested[run.taskId] ?: TransferState.PAUSED)
                }
                throw cancellation
            } catch (error: Exception) {
                failTask(run, UploadMessages.transferFailure(error))
            } finally {
                jobs.remove(run.taskId)
                requested.remove(run.taskId)
            }
        }
        jobs[run.taskId] = job
        job.start()
    }

    /**
     * 单个任务的主流程：RESOLVING（引擎算 MD5 + upload_request）→ 会话回调落库并转 RUNNING →
     * 分片上传 → COMPLETING → 完成落库。冲突（code==5060 且策略为 ASK）转 WAITING_USER；
     * 引擎自取消转 CANCELED；未预期异常由 [startTask] 的 catch 统一映射为 FAILED。
     *
     * 说明：MD5 校验在引擎内部完成，协调器无法观测其进度，因此 RESOLVING 覆盖"引擎启动到 S3 会话
     * 建立"这一段；秒传命中（[UploadOutcome.Done] 且 reused=true）不会触发会话回调，直接由 RESOLVING
     * 进入 COMPLETING。
     */
    private suspend fun perform(run: RunState) {
        val accountId = run.accountId
        val taskId = run.taskId
        val source = run.source

        persistState(accountId, taskId, TransferState.RESOLVING)
        logger.i(LogSource.UPLOAD, "开始上传：${source.displayName}")

        val progress = MutableStateFlow(0L)
        val sessionFlow = MutableStateFlow<UploadSession?>(null)
        // 首次 parts 回调是完整计划（整体替换）；此后每次是刚完成的分片（增量标记），避免 O(N²) 写库。
        val planInitialized = AtomicBoolean(false)

        val outcome = coroutineScope {
            val sidecar = launch { sidecar(accountId, taskId, progress, sessionFlow) }
            try {
                executor.upload(
                    request = UploadRequest(
                        key = taskId,
                        source = source,
                        parentFileId = run.parentFileId,
                        policy = run.policy,
                        resumeSession = run.resumeSession,
                    ),
                    progress = { uploaded, _ -> progress.value = uploaded },
                    session = { session ->
                        // 会话建立：立即落库并转 RUNNING（先落库再执行）。非挂起回调无法直接
                        // await DAO，故在本 coroutineScope 内起一次性写入协程——它会被 coroutineScope
                        // 等待，保证在 perform 继续前完成；引擎随后的 list parts 是网络请求，本地写库
                        // 必定远早于任何分片 PUT 完成。
                        sessionFlow.value = session
                        launch { persistSession(accountId, taskId, session) }
                    },
                    parts = { snapshots ->
                        val entities = snapshots.map { it.toEntity(accountId, taskId) }
                        if (planInitialized.compareAndSet(false, true)) {
                            launch { partDao.replaceFor(accountId, taskId, entities) }
                        } else {
                            launch { entities.forEach { partDao.markUploaded(it) } }
                        }
                    },
                )
            } finally {
                sidecar.cancel()
            }
        }

        when (outcome) {
            is UploadOutcome.Done -> complete(run, outcome)
            is UploadOutcome.Conflict -> {
                updateTask(accountId, taskId) {
                    it.copy(
                        state = TransferState.WAITING_USER,
                        error = UploadMessages.CONFLICT_TITLE,
                        updateTime = System.currentTimeMillis(),
                    )
                }
                logger.w(LogSource.UPLOAD, "${source.displayName} 存在同名文件，等待用户选择")
            }
            UploadOutcome.Canceled -> settle(run, requested[taskId] ?: TransferState.CANCELED)
        }
    }

    /** 完成：COMPLETING → COMPLETED，落最终 fileId（上传的 up_file_id）与"已上传字节"= 文件大小。 */
    private suspend fun complete(run: RunState, outcome: UploadOutcome.Done) {
        persistState(run.accountId, run.taskId, TransferState.COMPLETING)
        updateTask(run.accountId, run.taskId) {
            it.copy(
                state = TransferState.COMPLETED,
                // up_file_id 的权威来源是会话回调（data.FileId），已在 persistSession 落库；
                // 这里只在无会话路径（秒传命中，引擎不发会话回调）时用结果值兜底，绝不反向覆盖
                // 已落库的会话值——两处 id 来源不同，覆盖会让"会话立即落库"变成不可验证的约定。
                fileId = it.fileId ?: outcome.fileId.takeIf { id -> id != 0L },
                downloadedBytes = run.source.size,
                error = null,
                updateTime = System.currentTimeMillis(),
            )
        }
        val label = if (outcome.reused) "秒传完成" else "上传完成"
        logger.i(LogSource.UPLOAD, "${run.source.displayName} $label")
        onUploaded(run.accountId)
    }

    /**
     * 进度旁路协程：按 [progressThrottleMs] 周期落库最新"已上传字节"（节流）。引擎的
     * progress 回调是非挂起的，无法直接调用 DAO，因此回调只更新内存 StateFlow，由本协程负责落库。
     * state 按会话是否建立推导：会话未建立仍属 RESOLVING（MD5/upload_request），建立后为 RUNNING。
     */
    private suspend fun sidecar(
        accountId: String,
        taskId: String,
        progress: MutableStateFlow<Long>,
        session: MutableStateFlow<UploadSession?>,
    ) {
        var lastBytes = -1L
        while (true) {
            delay(progressThrottleMs)
            val done = progress.value
            if (done != lastBytes) {
                lastBytes = done
                val state = if (session.value != null) TransferState.RUNNING else TransferState.RESOLVING
                taskDao.updateProgress(accountId, taskId, done, state.name, System.currentTimeMillis())
            }
        }
    }

    /** 预期内失败：落库 FAILED 与用户可读文案（分片表保留，供下次续传或诊断）。 */
    private suspend fun failTask(run: RunState, message: String) {
        updateTask(run.accountId, run.taskId) {
            it.copy(state = TransferState.FAILED, error = message, updateTime = System.currentTimeMillis())
        }
        logger.e(LogSource.UPLOAD, "${run.source.displayName} 上传失败：$message")
    }

    /**
     * 取消协程后的持久终态（NonCancellable 内调用）。CANCELED 清空分片表（取消即放弃
     * 断点，对齐下载）；PAUSED 保留分片表与 S3 会话字段以便续传。
     */
    private suspend fun settle(run: RunState, terminal: TransferState) {
        if (terminal == TransferState.CANCELED) {
            partDao.clear(run.accountId, run.taskId)
        }
        updateTask(run.accountId, run.taskId) {
            it.copy(state = terminal, error = null, updateTime = System.currentTimeMillis())
        }
        val label = if (terminal == TransferState.CANCELED) "已取消" else "已暂停"
        logger.i(LogSource.UPLOAD, "${run.source.displayName} $label")
    }

    /**
     * 记录终态意图后取消任务协程，并等待 NonCancellable 清理完成。若协程在启动前即被取消
     * （协程体未执行，NonCancellable 分支不会运行），在 join 后补写一次终态，保证意图不丢失。
     */
    private suspend fun requestTerminal(taskId: String, terminal: TransferState) {
        val job = jobs[taskId]
        if (job == null) {
            val accountId = currentAccountId
            val row = accountId?.let { taskDao.get(it, taskId) }
            if (row == null || row.state == TransferState.COMPLETED) {
                logger.w(LogSource.UPLOAD, "请求 $terminal 的任务不可操作：$taskId")
                return
            }
            updateTask(row.accountId, row.taskId) {
                it.copy(state = terminal, updateTime = System.currentTimeMillis())
            }
            return
        }
        requested[taskId] = terminal
        job.cancel()
        job.join()
        val accountId = currentAccountId
        val row = accountId?.let { taskDao.get(it, taskId) }
        if (row != null && row.state != terminal && row.state != TransferState.COMPLETED) {
            updateTask(row.accountId, row.taskId) {
                it.copy(state = terminal, updateTime = System.currentTimeMillis())
            }
        }
    }

    // ---- 持久化辅助 ----

    /** 读改写整行（用于状态迁移、错误与 S3 会话字段落库）；行不存在时安全跳过。 */
    private suspend fun updateTask(
        accountId: String,
        taskId: String,
        transform: (TransferTaskEntity) -> TransferTaskEntity,
    ) {
        val current = taskDao.get(accountId, taskId) ?: return
        taskDao.upsert(transform(current))
    }

    /** 状态迁移只改 state/updateTime；高频进度走 updateProgress，不经过这里。 */
    private suspend fun persistState(accountId: String, taskId: String, state: TransferState) {
        updateTask(accountId, taskId) { it.copy(state = state, updateTime = System.currentTimeMillis()) }
    }

    /** 会话回调落库：把 S3 会话七字段写入任务行，并转 RUNNING。 */
    private suspend fun persistSession(accountId: String, taskId: String, session: UploadSession) {
        updateTask(accountId, taskId) {
            it.copy(
                bucket = session.bucket,
                storageNode = session.storageNode,
                uploadKey = session.uploadKey,
                uploadId = session.uploadId,
                fileId = session.fileId,
                etag = session.etag,
                blockSize = session.blockSize,
                state = TransferState.RUNNING,
                updateTime = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 由任务行重建续传会话：五字段（bucket/storageNode/uploadKey/uploadId/up_file_id）任一
     * 为空即返回 null，让引擎重走完整流程。`blockSize` 为 0 时回退到 [UploadPartPlan.BLOCK_SIZE]。
     */
    private fun TransferTaskEntity.toResumeSession(): UploadSession? {
        val upFileId = fileId ?: return null
        if (bucket.isEmpty() || storageNode.isEmpty() || uploadKey.isEmpty() || uploadId.isEmpty() || upFileId == 0L) {
            return null
        }
        return UploadSession(
            bucket = bucket,
            storageNode = storageNode,
            uploadKey = uploadKey,
            uploadId = uploadId,
            fileId = upFileId,
            etag = etag,
            blockSize = if (blockSize > 0) blockSize else UploadPartPlan.BLOCK_SIZE,
        )
    }

    /** mtime 校验：任一侧取不到（0）时视为不校验；否则差 >1s 即视为变化。 */
    private fun mtimeChanged(stored: Long, current: Long): Boolean =
        stored != 0L && current != 0L && abs(stored - current) > 1

    private fun UploadPartSnapshot.toEntity(accountId: String, taskId: String): UploadPartEntity =
        UploadPartEntity(accountId = accountId, taskId = taskId, partNumber = partNumber, size = size)

    /** 一次任务执行的运行态：持有来源/目标目录/冲突策略/续传会话。 */
    private class RunState(
        val accountId: String,
        val taskId: String,
        val source: UploadSource,
        val parentFileId: Long,
        val policy: ConflictPolicy,
        val resumeSession: UploadSession?,
    )
}
