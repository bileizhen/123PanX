package io.github.bileizhen.pan123x.core.transfer.download

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.DownloadSegmentDao
import io.github.bileizhen.pan123x.core.database.DownloadSegmentEntity
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskDao
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.PanDownloadApi
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.EngineTelemetry
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxConfig
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxDownloadEngine
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxRequest
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxStorage
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SegmentSink
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SegmentSnapshot
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadDestination
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadStorage
import io.github.bileizhen.pan123x.core.transfer.storage.OpenedSink
import io.github.bileizhen.pan123x.core.transfer.storage.StorageCheck
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 取链接缝：真实现是 [PanDownloadResolver]。抽成接口是为了让协调器在 JVM 单测里注入替身，
 * 同时不强迫 [PanDownloadResolver] 反向依赖协调器。
 */
fun interface DownloadResolver {
    suspend fun resolve(source: DownloadSource): ResolveOutcome
}

/**
 * 存储接缝：[DownloadStorage] 的窄接口。真实现持有 Android Context，JVM 单测无法构造，
 * 因此协调器只依赖这四个必要动作；生产接线由 [StorageGatewayAdapter] 转发给真实现。
 */
interface DownloadStorageGateway {
    fun check(destination: DownloadDestination): StorageCheck

    /** existingUri 非空时优先复用以续写；返回 null 表示创建/打开失败。 */
    fun open(destination: DownloadDestination, totalSize: Long, existingUri: String? = null): OpenedSink?

    /** 完成后返回可分享的 uri 字符串。 */
    fun complete(opened: OpenedSink, destination: DownloadDestination, size: Long): String?

    fun discard(opened: OpenedSink, destination: DownloadDestination)
}

/**
 * 下载执行接缝：[NsfxDownloadEngine] 的窄接口。签名与引擎的 download 一致（含 progress /
 * telemetry 回调），使协调器可在单测中替换为脚本化替身。
 */
fun interface DownloadExecutor {
    suspend fun download(
        request: NsfxRequest,
        storage: NsfxStorage,
        sink: SegmentSink,
        progress: (done: Long, total: Long, speed: Long) -> Unit,
        telemetry: (EngineTelemetry) -> Unit,
    ): Long
}

/** 把真实现 [DownloadStorage] 适配到 [DownloadStorageGateway]，仅做方法转发，不含业务。 */
private class StorageGatewayAdapter(private val delegate: DownloadStorage) : DownloadStorageGateway {
    override fun check(destination: DownloadDestination): StorageCheck = delegate.check(destination)

    override fun open(destination: DownloadDestination, totalSize: Long, existingUri: String?): OpenedSink? =
        delegate.open(destination, totalSize, existingUri)

    override fun complete(opened: OpenedSink, destination: DownloadDestination, size: Long): String? =
        delegate.complete(opened, destination, size)

    override fun discard(opened: OpenedSink, destination: DownloadDestination) =
        delegate.discard(opened, destination)
}

/**
 * 下载协调器：把取链（[DownloadResolver]）、NSFX 引擎（[DownloadExecutor]）
 * 与存储（[DownloadStorageGateway]）串成可恢复的状态机，并把任务状态与分段进度持久化到 Room。
 *
 * 为什么这样设计：
 * - **先落库再执行**：enqueue 先写入 QUEUED 行，再在应用级 scope 启动任务协程；进程被杀也不丢任务，
 *   重启后 recoverOnStart 把遗留活跃行转 WAITING_USER 等用户显式继续；
 * - **任务身份与短期 CDN 签名地址解耦**：持久化的只有 fileId/size/etag 等稳定字段，恢复时重新取链；
 * - **暂停/取消 = 取消协程 + NonCancellable 内落库终态**，对应 LeiFetch NsfxKernel 的清理语义：
 *   取消 PAUSED 保留已下载数据与分段表以便续传，取消 CANCELED 则 discard 目标文件 + 清分段 + 删工作目录；
 * - **进度写 Room 节流**到 [progressThrottleMs]（250~500ms），分段表只在分段计划
 *   真正变化时整体替换；
 * - 并发上限由注入的设置供应器控制；降低上限保留已有任务，等待任务感知新上限。
 *
 * 单测接线：主构造函数接收三个窄接口，可在纯 JVM 下注入替身；生产接线用次构造函数传入真实现。
 * [workRoot] 由应用传 `context.filesDir/"transfers"`，本类因此不依赖 Android Context。
 * 注意：任务协程运行在 [attach] 注入的应用级 scope 上，应用应使用后台调度器（推荐 Dispatchers.IO），
 * 本类不额外切换调度器，以保证单测在 TestDispatcher 下完全确定。
 */
class DownloadCoordinator(
    private val resolver: DownloadResolver,
    private val storage: DownloadStorageGateway,
    private val executor: DownloadExecutor,
    private val taskDao: TransferTaskDao,
    private val segmentDao: DownloadSegmentDao,
    private val manager: AccountManager,
    private val logger: AppLogger,
    private val workRoot: File,
    private val engineConfig: NsfxConfig = NsfxConfig(),
    private val progressThrottleMs: Long = 400,
    private val connections: () -> Int = { engineConfig.threads },
    private val taskLimit: () -> Int = { engineConfig.maxConcurrentTasks },
) {

    /**
     * 生产接线：注入下载解析器、执行器和存储。`api` 参数保留在接口中
     * （取链细节封装在 [PanDownloadResolver] 内，协调器自身不需要它）。
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        api: PanDownloadApi,
        resolver: PanDownloadResolver,
        storage: DownloadStorage,
        taskDao: TransferTaskDao,
        segmentDao: DownloadSegmentDao,
        manager: AccountManager,
        logger: AppLogger,
        engine: NsfxDownloadEngine,
        workRoot: File,
        engineConfig: NsfxConfig = NsfxConfig(),
        progressThrottleMs: Long = 400,
        connections: () -> Int = { engineConfig.threads },
        taskLimit: () -> Int = { engineConfig.maxConcurrentTasks },
    ) : this(
        resolver = DownloadResolver { source -> resolver.resolve(source) },
        storage = StorageGatewayAdapter(storage),
        executor = DownloadExecutor { request, nsfxStorage, sink, progress, telemetry ->
            engine.download(request, nsfxStorage, sink, progress, telemetry)
        },
        taskDao = taskDao,
        segmentDao = segmentDao,
        manager = manager,
        logger = logger,
        workRoot = workRoot,
        engineConfig = engineConfig,
        progressThrottleMs = progressThrottleMs,
        connections = connections,
        taskLimit = taskLimit,
    )

    /** 应用级 scope：任务协程在此启动，从而脱离 ViewModel/UI 生命周期。 */
    @Volatile
    private var appScope: CoroutineScope? = null

    private val slots = io.github.bileizhen.pan123x.core.transfer.DynamicTaskGate(taskLimit)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val requested = ConcurrentHashMap<String, TransferState>()

    /** enqueue 时捕获的完整来源（含 s3KeyFlag），供同一进程内的暂停/继续复用。 */
    private val sources = ConcurrentHashMap<String, DownloadSource>()

    /** 分段计划去重：只在 (index,start,end) 变化时整体替换分段表。 */
    private val segmentPlans = ConcurrentHashMap<String, SegmentPlanTracker>()

    private val currentAccountId: String?
        get() = (manager.state.value as? SessionState.Ready)?.accountId

    /** 由 AppContainer 注入应用级 scope；必须在 enqueue 之前调用。 */
    fun attach(scope: CoroutineScope) {
        appScope = scope
    }

    /** 观察某账户的全部传输任务（按创建时间倒序）。 */
    fun observeTasks(accountId: String): Flow<List<TransferTaskEntity>> = taskDao.observeTasks(accountId)

    /** 观察某任务的分段进度（按段序升序），供工作台分段点阵使用。 */
    fun observeSegments(accountId: String, taskId: String): Flow<List<DownloadSegmentEntity>> =
        segmentDao.observe(accountId, taskId)

    /**
     * 某任务的 NSFX 断点工作目录（`workRoot/accountId/taskId`）。
     *
     * 暴露出来是为了让「断点是否真的落盘」可被外部检验（诊断 / instrumented 测试）：
     * 该目录里的 `segments.json` 与 `<index>.offset` 才是续传状态的权威来源，
     * `download_segments` 表只是展示镜像。
     */
    fun workDirOf(accountId: String, taskId: String): File = workDirFor(accountId, taskId)

    /**
     * 入队一个下载任务并返回 taskId。
     *
     * 先落库（QUEUED）再启动任务协程：即便进程随即被杀，任务行仍然存在，
     * recoverOnStart 会把它转成 WAITING_USER。未登录时无法确定 accountId，抛 IllegalStateException。
     */
    suspend fun enqueue(source: DownloadSource, destination: DownloadDestination): String {
        val accountId = currentAccountId
            ?: throw IllegalStateException(DownloadMessages.NOT_LOGGED_IN)
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        taskDao.upsert(
            TransferTaskEntity(
                accountId = accountId,
                taskId = taskId,
                fileId = source.fileId,
                fileName = source.fileName,
                direction = TransferDirection.DOWNLOAD,
                state = TransferState.QUEUED,
                size = source.size,
                etag = source.etag,
                targetUri = "",
                destinationTree = (destination as? DownloadDestination.Tree)?.treeUri.orEmpty(),
                segments = 0,
                downloadedBytes = 0,
                createTime = now,
                updateTime = now,
                error = null,
                s3KeyFlag = source.s3KeyFlag,
                shareKey = source.shareKey,
                sharePassword = source.sharePassword,
            ),
        )
        sources[taskId] = source
        logger.i(LogSource.DOWNLOAD, "加入下载队列：${source.fileName}")
        startTask(RunState(accountId, taskId, source, destination))
        return taskId
    }

    /**
     * 恢复一个任务：重新取链、以原 [OpenedSink] uri 续写，分段级断点由引擎的 [NsfxStorage] 日志提供。
     *
     * [source] 为空时依次回退到"入队时捕获的来源"与"任务行重建的来源"；进程重启后内存来源已丢失，
     * 调用方应尽量传入刚从云端取到的来源，以保证 s3KeyFlag 等取链必需字段正确。
     * 当传入的 source 与任务行的 fileId/size/etag 不一致时，说明云端文件已变化：清空分段表与
     * 工作目录后从头下载。
     */
    suspend fun resume(taskId: String, source: DownloadSource? = null) {
        val accountId = currentAccountId
            ?: throw IllegalStateException(DownloadMessages.NOT_LOGGED_IN)
        if (jobs.containsKey(taskId)) {
            logger.i(LogSource.DOWNLOAD, "任务已在运行，忽略重复恢复：$taskId")
            return
        }
        val row = taskDao.get(accountId, taskId)
        if (row == null) {
            logger.w(LogSource.DOWNLOAD, "恢复失败：任务不存在或不属于当前账户：$taskId")
            return
        }
        if (row.state == TransferState.COMPLETED) {
            logger.i(LogSource.DOWNLOAD, "任务已完成，无需恢复：${row.fileName}")
            return
        }

        val effective = source ?: sources[taskId] ?: row.toSource()
        // fileId/size/etag 与任务行不符 → 云端文件已变化，断点身份失效。
        val changed = row.fileId != null &&
            (effective.fileId != row.fileId || effective.size != row.size || effective.etag != row.etag)
        val now = System.currentTimeMillis()
        if (changed) {
            logger.i(LogSource.DOWNLOAD, "${row.fileName} 云端文件已变化，重置断点后重新下载")
            workDirFor(accountId, taskId).deleteRecursively()
            segmentDao.clear(accountId, taskId)
            taskDao.upsert(
                row.copy(
                    fileId = effective.fileId,
                    fileName = effective.fileName,
                    size = effective.size,
                    etag = effective.etag,
                    targetUri = "",
                    segments = 0,
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
        startTask(RunState(accountId, taskId, effective, destinationFor(row, effective.fileName)))
    }

    /** 暂停：取消任务协程，由 NonCancellable 分支落库 PAUSED，保留已下载数据与分段表以便续传。 */
    suspend fun pause(taskId: String) = requestTerminal(taskId, TransferState.PAUSED)

    /** 取消：取消任务协程，落库 CANCELED，并 discard 目标文件、清空分段、删除工作目录。 */
    suspend fun cancel(taskId: String) = requestTerminal(taskId, TransferState.CANCELED)

    /**
     * 进程重启后的恢复：把遗留的 QUEUED/RESOLVING/RUNNING/COMPLETING 行统一转为
     * WAITING_USER，绝不自动重启——是否继续由用户显式决定。跨账户处理。
     */
    suspend fun recoverOnStart() {
        val leftovers = taskDao.activeTasks()
        if (leftovers.isEmpty()) return
        val now = System.currentTimeMillis()
        leftovers.forEach { row ->
            taskDao.upsert(row.copy(state = TransferState.WAITING_USER, updateTime = now))
        }
        logger.i(LogSource.DOWNLOAD, "启动恢复：${leftovers.size} 个未完成任务转为等待用户继续（不自动重启）")
    }

    // ---- 任务执行 ----

    /** 在应用级 scope 启动任务协程，并把整个执行的异常/取消收敛为持久终态（对应 NsfxKernel.startDownload）。 */
    private fun startTask(run: RunState) {
        val scope = appScope
            ?: error("DownloadCoordinator.attach(scope) 必须在 enqueue/resume 之前调用")
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
                failTask(run, DownloadMessages.transferFailure(error))
            } finally {
                jobs.remove(run.taskId)
                requested.remove(run.taskId)
                segmentPlans.remove(run.taskId)
            }
        }
        jobs[run.taskId] = job
        job.start()
    }

    /**
     * 单个任务的主流程：RESOLVING → 取链 → 存储校验 → 打开目标 → RUNNING → 引擎下载 →
     * COMPLETING → 完成落库。预期内的失败（取链失败 / 存储不可用 / 打开失败）就地转 FAILED 并返回；
     * 未预期异常由 [startTask] 的 catch 统一映射。
     */
    private suspend fun perform(run: RunState) {
        val accountId = run.accountId
        val taskId = run.taskId
        val source = run.source
        val destination = run.destination

        persistState(accountId, taskId, TransferState.RESOLVING)
        logger.i(LogSource.DOWNLOAD, "开始下载：${source.fileName}")

        val resolved = when (val outcome = resolver.resolve(source)) {
            is ResolveOutcome.Success -> outcome
            is ResolveOutcome.Failure -> {
                failTask(run, outcome.userMessage)
                return
            }
        }
        if (resolved.trafficLimited) {
            logger.w(LogSource.DOWNLOAD, "${source.fileName} 下载流量已超限，已按参考实现尝试绕过")
        }

        when (val check = storage.check(destination)) {
            is StorageCheck.Unavailable -> {
                failTask(run, check.userMessage)
                return
            }
            StorageCheck.Ok -> Unit
        }

        val existingUri = taskDao.get(accountId, taskId)?.targetUri?.ifEmpty { null }
        val opened = storage.open(destination, totalSize = source.size, existingUri = existingUri)
        if (opened == null) {
            failTask(run, DownloadMessages.SAF_PERMISSION)
            return
        }
        run.opened = opened
        updateTask(accountId, taskId) { it.copy(targetUri = opened.uri, updateTime = System.currentTimeMillis()) }
        persistState(accountId, taskId, TransferState.RUNNING)

        val workDir = workDirFor(accountId, taskId)
        workDir.mkdirs()
        val progress = MutableStateFlow(0L)
        val segments = MutableStateFlow<List<SegmentSnapshot>?>(null)
        val tracker = segmentPlans.getOrPut(taskId) { SegmentPlanTracker() }
        val written = coroutineScope {
            val sidecar = launch { sidecar(accountId, taskId, progress, segments, tracker) }
            try {
                executor.download(
                    request = NsfxRequest(
                        key = taskId,
                        url = resolved.url,
                        expectedSize = source.size,
                        etag = source.etag,
                        connections = connections(),
                    ),
                    storage = NsfxStorage(workDir),
                    sink = opened.sink,
                    progress = { done, _, _ -> progress.value = done },
                    telemetry = { telemetry -> segments.value = telemetry.segments },
                )
            } finally {
                sidecar.cancel()
            }
        }

        persistSegments(accountId, taskId, segments.value, tracker)
        persistState(accountId, taskId, TransferState.COMPLETING)
        val finalUri = storage.complete(opened, destination, written)
        run.opened = null
        if (finalUri == null) {
            failTask(run, DownloadMessages.SAF_PERMISSION)
            return
        }
        updateTask(accountId, taskId) {
            it.copy(
                state = TransferState.COMPLETED,
                targetUri = finalUri,
                downloadedBytes = written,
                segments = segments.value?.size ?: it.segments,
                error = null,
                updateTime = System.currentTimeMillis(),
            )
        }
        logger.i(LogSource.DOWNLOAD, "${source.fileName} 下载完成")
    }

    /**
     * 进度/分段旁路协程：按 [progressThrottleMs] 周期落库最新进度（节流），并在分段
     * 计划变化时整体替换分段表。引擎的 progress/telemetry 回调是非挂起的，无法直接调用 DAO，
     * 因此回调只更新内存 StateFlow，由本协程负责落库。
     */
    private suspend fun sidecar(
        accountId: String,
        taskId: String,
        progress: MutableStateFlow<Long>,
        segments: MutableStateFlow<List<SegmentSnapshot>?>,
        tracker: SegmentPlanTracker,
    ) {
        var lastBytes = -1L
        while (true) {
            delay(progressThrottleMs)
            val done = progress.value
            if (done != lastBytes) {
                lastBytes = done
                taskDao.updateProgress(accountId, taskId, done, TransferState.RUNNING.name, System.currentTimeMillis())
            }
            segments.value?.let { snapshots ->
                val plan = tracker.planOf(snapshots)
                if (plan != tracker.lastPlan) {
                    tracker.lastPlan = plan
                    segmentDao.replaceFor(accountId, taskId, snapshots.map { it.toEntity(accountId, taskId) })
                }
            }
        }
    }

    /** 预期内失败：释放写入目标（保留已下载数据以便续传）、落库 FAILED 与用户可读文案。 */
    private suspend fun failTask(run: RunState, message: String) {
        run.opened?.let {
            run.opened = null
            closeQuietly(it, run.source.fileName)
        }
        updateTask(run.accountId, run.taskId) {
            it.copy(state = TransferState.FAILED, error = message, updateTime = System.currentTimeMillis())
        }
        logger.e(LogSource.DOWNLOAD, "${run.source.fileName} 下载失败：$message")
    }

    /**
     * 取消协程后的持久终态（NonCancellable 内调用）。CANCELED 需要 discard 目标文件、清空分段表
     * 并删除工作目录（明确取消即放弃断点）；PAUSED 只释放写入目标，保留数据以续传。
     */
    private suspend fun settle(run: RunState, terminal: TransferState) {
        val opened = run.opened
        run.opened = null
        when (terminal) {
            TransferState.CANCELED -> {
                if (opened != null) discardQuietly(opened, run.destination, run.source.fileName)
                segmentDao.clear(run.accountId, run.taskId)
                workDirFor(run.accountId, run.taskId).deleteRecursively()
            }
            else -> if (opened != null) closeQuietly(opened, run.source.fileName)
        }
        updateTask(run.accountId, run.taskId) {
            it.copy(state = terminal, error = null, updateTime = System.currentTimeMillis())
        }
        val label = if (terminal == TransferState.CANCELED) "已取消" else "已暂停"
        logger.i(LogSource.DOWNLOAD, "${run.source.fileName} $label")
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
                logger.w(LogSource.DOWNLOAD, "请求 $terminal 的任务不可操作：$taskId")
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

    /** 读改写整行（用于状态迁移、错误与 targetUri 落库）；行不存在时安全跳过。 */
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

    /** 任务完成前的最后一次分段落库（仍按计划去重，避免与旁路协程重复写）。 */
    private suspend fun persistSegments(
        accountId: String,
        taskId: String,
        snapshots: List<SegmentSnapshot>?,
        tracker: SegmentPlanTracker,
    ) {
        if (snapshots.isNullOrEmpty()) return
        val plan = tracker.planOf(snapshots)
        if (plan == tracker.lastPlan) return
        tracker.lastPlan = plan
        segmentDao.replaceFor(accountId, taskId, snapshots.map { it.toEntity(accountId, taskId) })
    }

    private fun closeQuietly(opened: OpenedSink, fileName: String) {
        try {
            opened.close()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.w(LogSource.DOWNLOAD, "$fileName 释放写入目标失败：${failure.message.orEmpty()}")
        }
    }

    private fun discardQuietly(opened: OpenedSink, destination: DownloadDestination, fileName: String) {
        try {
            storage.discard(opened, destination)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.w(LogSource.DOWNLOAD, "$fileName 丢弃写入目标失败：${failure.message.orEmpty()}")
        }
    }

    private fun workDirFor(accountId: String, taskId: String): File = File(File(workRoot, accountId), taskId)

    private fun destinationFor(row: TransferTaskEntity, fileName: String): DownloadDestination =
        if (row.destinationTree.isEmpty()) {
            DownloadDestination.Internal(fileName)
        } else {
            DownloadDestination.Tree(row.destinationTree, fileName)
        }

    private fun TransferTaskEntity.toSource(): DownloadSource = DownloadSource(
        fileId = fileId ?: 0L,
        fileName = fileName,
        size = size,
        etag = etag,
        s3KeyFlag = s3KeyFlag,
        isFolder = false,
        shareKey = shareKey,
        sharePassword = sharePassword,
    )

    private fun SegmentSnapshot.toEntity(accountId: String, taskId: String): DownloadSegmentEntity =
        DownloadSegmentEntity(
            accountId = accountId,
            taskId = taskId,
            segmentIndex = index,
            start = start,
            end = end,
            downloaded = downloaded,
        )

    /** 一次任务执行的运行态：持有 opened（供取消/失败清理）与来源/目标。 */
    private class RunState(
        val accountId: String,
        val taskId: String,
        val source: DownloadSource,
        val destination: DownloadDestination,
    ) {
        @Volatile
        var opened: OpenedSink? = null
    }

    /** 分段计划去重状态：(index,start,end) 三元组序列，下载量变化不触发写库。 */
    private class SegmentPlanTracker {
        var lastPlan: List<Triple<Int, Long, Long>>? = null

        fun planOf(snapshots: List<SegmentSnapshot>): List<Triple<Int, Long, Long>> =
            snapshots.map { Triple(it.index, it.start, it.end) }
    }
}
