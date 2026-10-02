@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.core.transfer.download

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.DownloadSegmentDao
import io.github.bileizhen.pan123x.core.database.DownloadSegmentEntity
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskDao
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.EngineTelemetry
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxConfig
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxRequest
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxStorage
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SegmentSink
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SegmentSnapshot
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadDestination
import io.github.bileizhen.pan123x.core.transfer.storage.OpenedSink
import io.github.bileizhen.pan123x.core.transfer.storage.StorageCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DownloadCoordinator 行为测试（纯 JVM、不触网、不依赖 Room/Context）。
 *
 * 替身策略：手写 [TransferTaskDao] / [DownloadSegmentDao] 内存替身（可观察，基于 MutableStateFlow），
 * 以及 [DownloadResolver] / [DownloadStorageGateway] / [DownloadExecutor] 三个窄接口的替身；
 * AccountManager / AppLogger 用真实现。Room 与 Android Context 都被挡在协调器之外，因此本文件
 * 只验证编排语义：先落库再执行、状态机顺序、节流、暂停/取消终态、恢复重置、启动恢复。
 */
class DownloadCoordinatorTest {

    // ---- 替身 ----

    private data class ProgressCall(
        val accountId: String,
        val taskId: String,
        val downloadedBytes: Long,
        val state: String,
        val updateTime: Long,
    )

    private class FakeTransferTaskDao : TransferTaskDao {
        val rows = MutableStateFlow<List<TransferTaskEntity>>(emptyList())
        val stateLog = mutableListOf<TransferState>()
        val progressCalls = mutableListOf<ProgressCall>()

        fun seed(vararg tasks: TransferTaskEntity) {
            rows.value = rows.value + tasks
        }

        override fun observeTasks(accountId: String): Flow<List<TransferTaskEntity>> = rows
            .map { list -> list.filter { it.accountId == accountId }.sortedByDescending { it.createTime } }
            .distinctUntilChanged()

        override suspend fun get(accountId: String, taskId: String): TransferTaskEntity? =
            rows.value.firstOrNull { it.accountId == accountId && it.taskId == taskId }

        override suspend fun upsert(task: TransferTaskEntity) {
            rows.value = rows.value.filterNot { it.accountId == task.accountId && it.taskId == task.taskId } + task
            stateLog += task.state
        }

        override suspend fun delete(accountId: String, taskId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId && it.taskId == taskId }
        }

        override suspend fun deleteFinished(accountId: String) {
            rows.value = rows.value.filterNot {
                it.accountId == accountId && it.state in setOf(TransferState.COMPLETED, TransferState.CANCELED)
            }
        }

        override suspend fun updateProgress(
            accountId: String,
            taskId: String,
            downloadedBytes: Long,
            state: String,
            updateTime: Long,
        ) {
            progressCalls += ProgressCall(accountId, taskId, downloadedBytes, state, updateTime)
            val parsed = TransferState.valueOf(state)
            stateLog += parsed
            rows.value = rows.value.map {
                if (it.accountId == accountId && it.taskId == taskId) {
                    it.copy(downloadedBytes = downloadedBytes, state = parsed, updateTime = updateTime)
                } else {
                    it
                }
            }
        }

        override fun observeActive(accountId: String): Flow<List<TransferTaskEntity>> = rows
            .map { list -> list.filter { it.accountId == accountId && it.state in ACTIVE_STATES } }
            .distinctUntilChanged()

        override suspend fun pendingResumable(accountId: String): List<TransferTaskEntity> =
            rows.value.filter { it.accountId == accountId && it.state in ACTIVE_STATES }

        override suspend fun activeTasks(): List<TransferTaskEntity> =
            rows.value.filter { it.state in ACTIVE_STATES }

        private companion object {
            val ACTIVE_STATES = setOf(
                TransferState.QUEUED, TransferState.RESOLVING, TransferState.RUNNING, TransferState.COMPLETING,
            )
        }
    }

    private class FakeDownloadSegmentDao : DownloadSegmentDao() {
        val rows = MutableStateFlow<List<DownloadSegmentEntity>>(emptyList())
        var replaceCalls = 0

        fun seed(vararg segments: DownloadSegmentEntity) {
            rows.value = rows.value + segments
        }

        override fun observe(accountId: String, taskId: String): Flow<List<DownloadSegmentEntity>> = rows
            .map { list -> list.filter { it.accountId == accountId && it.taskId == taskId }.sortedBy { it.segmentIndex } }
            .distinctUntilChanged()

        override suspend fun insert(segments: List<DownloadSegmentEntity>) {
            val keys = segments.mapTo(mutableSetOf()) { Triple(it.accountId, it.taskId, it.segmentIndex) }
            rows.value = rows.value.filterNot { Triple(it.accountId, it.taskId, it.segmentIndex) in keys } + segments
        }

        override suspend fun clear(accountId: String, taskId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId && it.taskId == taskId }
        }

        override suspend fun replaceFor(
            accountId: String,
            taskId: String,
            segments: List<DownloadSegmentEntity>,
        ) {
            replaceCalls++
            super.replaceFor(accountId, taskId, segments)
        }
    }

    private class FakeResolver : DownloadResolver {
        var outcome: ResolveOutcome = ResolveOutcome.Success(CDN_URL, trafficLimited = false)
        val resolvedSources = mutableListOf<DownloadSource>()

        override suspend fun resolve(source: DownloadSource): ResolveOutcome {
            resolvedSources += source
            return outcome
        }
    }

    private class FakeSink : SegmentSink {
        var closed = false
        override fun writeAt(offset: Long, buffer: ByteArray, length: Int) = Unit
        override fun sync() = Unit
        override fun currentLength(): Long = 0
        override fun close() {
            closed = true
        }
    }

    private class FakeStorageGateway : DownloadStorageGateway {
        var checkResult: StorageCheck = StorageCheck.Ok
        var openFails = false
        val openedSinks = mutableListOf<FakeSink>()
        val completedSizes = mutableListOf<Long>()
        val discarded = mutableListOf<String>()

        override fun check(destination: DownloadDestination): StorageCheck = checkResult

        override fun open(destination: DownloadDestination, totalSize: Long, existingUri: String?): OpenedSink? {
            if (openFails) return null
            val sink = FakeSink()
            openedSinks += sink
            return OpenedSink(uriOf(destination), sink) { sink.closed = true }
        }

        override fun complete(opened: OpenedSink, destination: DownloadDestination, size: Long): String? {
            completedSizes += size
            return opened.uri
        }

        override fun discard(opened: OpenedSink, destination: DownloadDestination) {
            discarded += opened.uri
            opened.close()
        }

        private fun uriOf(destination: DownloadDestination): String = when (destination) {
            is DownloadDestination.Internal -> "file:///target/${destination.fileName}"
            is DownloadDestination.Tree -> "content://tree/${destination.fileName}"
        }
    }

    private class FakeExecutor : DownloadExecutor {
        var ticks = 0
        var tickDelayMs = 0L
        var failure: Exception? = null
        var planAt: (Int) -> List<SegmentSnapshot>? = { null }
        var gate: CompletableDeferred<Unit>? = null
        var started = false
        var finished = false
        var progressTicks = 0
        var telemetryEmissions = 0
        val requests = mutableListOf<NsfxRequest>()

        override suspend fun download(
            request: NsfxRequest,
            storage: NsfxStorage,
            sink: SegmentSink,
            progress: (done: Long, total: Long, speed: Long) -> Unit,
            telemetry: (EngineTelemetry) -> Unit,
        ): Long {
            started = true
            requests += request
            failure?.let { throw it }
            repeat(ticks) { index ->
                if (tickDelayMs > 0) delay(tickDelayMs)
                planAt(index)?.let { plan ->
                    telemetryEmissions++
                    telemetry(
                        EngineTelemetry(
                            connections = 2,
                            pieceSize = 1L shl 20,
                            fills = ByteArray(0),
                            speed = 0,
                            segments = plan,
                        ),
                    )
                }
                progressTicks++
                progress((index + 1) * 100L, request.expectedSize, 1_000L)
            }
            gate?.await()
            finished = true
            return request.expectedSize
        }
    }

    private class Fixture(
        val tasks: FakeTransferTaskDao,
        val segments: FakeDownloadSegmentDao,
        val resolver: FakeResolver,
        val storage: FakeStorageGateway,
        val executor: FakeExecutor,
        val workRoot: File,
        val coordinator: DownloadCoordinator,
    )

    private fun TestScope.fixture(progressThrottleMs: Long = 400): Fixture {
        val tasks = FakeTransferTaskDao()
        val segments = FakeDownloadSegmentDao()
        val resolver = FakeResolver()
        val storage = FakeStorageGateway()
        val executor = FakeExecutor()
        val manager = AccountManager().apply { onLoginSuccess("acc-1", "user@example.com", "1", "Bearer token") }
        val workRoot = Files.createTempDirectory("pan123x-download").toFile()
        val coordinator = DownloadCoordinator(
            resolver = resolver,
            storage = storage,
            executor = executor,
            taskDao = tasks,
            segmentDao = segments,
            manager = manager,
            logger = AppLogger(),
            workRoot = workRoot,
            engineConfig = NsfxConfig(),
            progressThrottleMs = progressThrottleMs,
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        taskScope = scope
        coordinator.attach(scope)
        return Fixture(tasks, segments, resolver, storage, executor, workRoot, coordinator)
    }

    /**
     * 协调器任务所挂的 scope。**不能**用 `TestScope.backgroundScope`：`advanceUntilIdle` 只推进
     * 前台任务（`advanceUntilIdleOr { none(::isForeground) }`），background 标记的协程不会被它驱动，
     * 状态机会一直停在 QUEUED。这里改用同一测试调度器上、未标记 background 的普通 scope，
     * 并在 [tearDown] 统一取消，避免测试间残留协程。
     */
    private var taskScope: CoroutineScope? = null

    @After
    fun tearDown() {
        taskScope?.cancel()
        taskScope = null
    }

    // ---- 入队 ----

    @Test
    fun enqueuePersistsQueuedRowBeforeJobStarts() = runTest {
        val f = fixture()

        val taskId = f.coordinator.enqueue(source(size = 1_000), DownloadDestination.Internal("a.bin"))

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.QUEUED, row.state)
        assertEquals(TransferDirection.DOWNLOAD, row.direction)
        assertEquals(1L, row.fileId)
        assertEquals(1_000L, row.size)
        assertEquals("etag-1", row.etag)
        assertEquals("", row.targetUri)
        assertEquals(0L, row.downloadedBytes)
        // 先落库再执行：任务行已存在，但执行器还没被调用
        assertFalse(f.executor.started)
        assertTrue(f.tasks.progressCalls.isEmpty())
    }

    @Test
    fun enqueueRecordsSafTreeUriInDestinationTree() = runTest {
        val f = fixture()

        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Tree("content://tree/root", "a.bin"))

        assertEquals("content://tree/root", f.tasks.get("acc-1", taskId)!!.destinationTree)
    }

    // ---- 状态机 ----

    @Test
    fun stateMachineReachesCompletedInOrder() = runTest {
        val f = fixture()
        f.executor.ticks = 2

        val taskId = f.coordinator.enqueue(source(size = 200), DownloadDestination.Internal("a.bin"))
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.COMPLETED, row.state)
        assertEquals(200L, row.downloadedBytes)
        assertEquals("file:///target/a.bin", row.targetUri)
        assertTrue(f.executor.started)
        assertTrue(f.executor.finished)

        val states = f.tasks.stateLog
        assertEquals(TransferState.QUEUED, states.first())
        assertTrue(states.indexOf(TransferState.RESOLVING) < states.indexOf(TransferState.RUNNING))
        assertTrue(states.indexOf(TransferState.RUNNING) < states.indexOf(TransferState.COMPLETING))
        assertTrue(states.indexOf(TransferState.COMPLETING) < states.indexOf(TransferState.COMPLETED))
    }

    @Test
    fun resolverFailurePersistsFailedWithMappedMessage() = runTest {
        val f = fixture()
        f.resolver.outcome = ResolveOutcome.Failure("下载地址不可信，已拒绝")

        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Internal("a.bin"))
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals("下载地址不可信，已拒绝", row.error)
        assertFalse(f.executor.started)
    }

    @Test
    fun storageUnavailablePersistsFailedWithoutStartingEngine() = runTest {
        val f = fixture()
        f.storage.checkResult = StorageCheck.Unavailable("保存位置权限已失效，请重新选择")

        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Tree("content://tree/root", "a.bin"))
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals("保存位置权限已失效，请重新选择", row.error)
        assertFalse(f.executor.started)
    }

    @Test
    fun openFailurePersistsFailed() = runTest {
        val f = fixture()
        f.storage.openFails = true

        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Internal("a.bin"))
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals(DownloadMessages.SAF_PERMISSION, row.error)
        assertFalse(f.executor.started)
    }

    @Test
    fun engineExceptionPersistsFailedWithTransferMessage() = runTest {
        val f = fixture()
        f.executor.failure = java.io.IOException("broken pipe")

        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Internal("a.bin"))
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals(DownloadMessages.transferFailure(java.io.IOException("broken pipe")), row.error)
    }

    // ---- 进度节流 ----

    @Test
    fun progressWritesAreThrottledToConfiguredInterval() = runTest {
        val f = fixture(progressThrottleMs = 400)
        f.executor.ticks = 20
        f.executor.tickDelayMs = 50

        val taskId = f.coordinator.enqueue(source(size = 2_000), DownloadDestination.Internal("a.bin"))
        advanceUntilIdle()

        assertEquals(20, f.executor.progressTicks)
        assertTrue(f.tasks.progressCalls.isNotEmpty())
        assertTrue(
            "进度写库必须节流，实际 ${f.tasks.progressCalls.size} 次 / ${f.executor.progressTicks} tick",
            f.tasks.progressCalls.size < f.executor.progressTicks,
        )
        assertTrue(f.tasks.progressCalls.all { it.state == TransferState.RUNNING.name })
        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", taskId)!!.state)
    }

    @Test
    fun segmentPlanIsPersistedOnlyWhenItChanges() = runTest {
        val f = fixture(progressThrottleMs = 400)
        val planA = listOf(SegmentSnapshot(0, 0, 999, 0), SegmentSnapshot(1, 1_000, 1_999, 0))
        val planB = listOf(SegmentSnapshot(0, 0, 1_499, 0), SegmentSnapshot(1, 1_500, 1_999, 0))
        f.executor.ticks = 12
        f.executor.tickDelayMs = 100
        f.executor.planAt = { index -> if (index < 6) planA else planB }

        val taskId = f.coordinator.enqueue(source(size = 2_000), DownloadDestination.Internal("a.bin"))
        advanceUntilIdle()

        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", taskId)!!.state)
        assertEquals(12, f.executor.telemetryEmissions)
        assertTrue("分段计划未变化时不应重复写库", f.segments.replaceCalls <= 3)
        assertTrue(f.segments.replaceCalls >= 1)
        // 最终落库的是最后一次计划（B）
        assertEquals(listOf(0, 1), f.segments.rows.value.map { it.segmentIndex })
        assertEquals(1_499L, f.segments.rows.value.first { it.segmentIndex == 0 }.end)
    }

    // ---- 暂停 / 取消 ----

    @Test
    fun pausePersistsPausedAndKeepsPartialData() = runTest {
        val f = fixture()
        f.executor.gate = CompletableDeferred()
        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Internal("a.bin"))
        runCurrent()
        assertEquals(TransferState.RUNNING, f.tasks.get("acc-1", taskId)!!.state)

        f.coordinator.pause(taskId)

        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", taskId)!!.state)
        assertFalse(f.executor.finished)
        // 暂停保留写入目标以便续传，不丢弃文件
        assertTrue(f.storage.discarded.isEmpty())
        assertTrue(f.storage.openedSinks.single().closed)
    }

    @Test
    fun cancelPersistsCanceledAndClearsSegmentsAndWorkDir() = runTest {
        val f = fixture()
        f.executor.gate = CompletableDeferred()
        val taskId = f.coordinator.enqueue(source(), DownloadDestination.Internal("a.bin"))
        runCurrent()

        // 模拟断点数据：已有分段行 + 工作目录中的日志文件
        f.segments.seed(DownloadSegmentEntity("acc-1", taskId, 0, 0, 499, 100))
        val workDir = File(File(f.workRoot, "acc-1"), taskId)
        workDir.mkdirs()
        val journal = File(workDir, "journal.tmp").apply { writeText("state") }
        assertTrue(journal.exists())

        f.coordinator.cancel(taskId)

        assertEquals(TransferState.CANCELED, f.tasks.get("acc-1", taskId)!!.state)
        assertTrue("取消必须清空分段表", f.segments.rows.value.isEmpty())
        assertFalse("取消必须删除工作目录", workDir.exists())
        assertEquals(listOf("file:///target/a.bin"), f.storage.discarded)
    }

    @Test
    fun pauseOnTaskWithoutActiveJobWritesTerminalStateDirectly() = runTest {
        val f = fixture()
        f.tasks.seed(task("waiting", TransferState.WAITING_USER))

        f.coordinator.pause("waiting")

        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", "waiting")!!.state)
    }

    // ---- 恢复 ----

    @Test
    fun resumeAfterFailureRestartsFromQueuedAndCompletes() = runTest {
        val f = fixture()
        val taskId = f.coordinator.enqueue(source(size = 300), DownloadDestination.Internal("a.bin"))
        f.resolver.outcome = ResolveOutcome.Failure("网络连接失败，请检查网络后重试")
        advanceUntilIdle()
        assertEquals(TransferState.FAILED, f.tasks.get("acc-1", taskId)!!.state)

        f.resolver.outcome = ResolveOutcome.Success(CDN_URL, trafficLimited = false)
        f.coordinator.resume(taskId)
        advanceUntilIdle()

        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", taskId)!!.state)
        assertEquals(300L, f.executor.requests.last().expectedSize)
    }

    @Test
    fun resumeWithChangedSourceResetsJournalAndSegments() = runTest {
        val f = fixture()
        val taskId = "task-fixed"
        f.tasks.seed(
            TransferTaskEntity(
                accountId = "acc-1",
                taskId = taskId,
                fileId = 1L,
                fileName = "a.bin",
                direction = TransferDirection.DOWNLOAD,
                state = TransferState.PAUSED,
                size = 100,
                etag = "old",
                targetUri = "file:///target/a.bin",
            ),
        )
        f.segments.seed(DownloadSegmentEntity("acc-1", taskId, 0, 0, 99, 50))
        val workDir = File(File(f.workRoot, "acc-1"), taskId)
        workDir.mkdirs()
        val journal = File(workDir, "journal.tmp").apply { writeText("state") }

        // size / etag 与任务行不符 → 断点失效，重置后从头下载
        f.coordinator.resume(taskId, source(size = 200, etag = "new"))
        advanceUntilIdle()

        assertFalse("断点日志必须被清除", journal.exists())
        assertTrue("分段行必须被清除后不再写入（本次无 telemetry）", f.segments.rows.value.isEmpty())
        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.COMPLETED, row.state)
        assertEquals(200L, row.size)
        assertEquals("new", row.etag)
        assertEquals(200L, f.executor.requests.single().expectedSize)
    }

    @Test
    fun resumeKeepsJournalWhenSourceMatches() = runTest {
        val f = fixture()
        val taskId = f.coordinator.enqueue(source(size = 400, etag = "etag-1"), DownloadDestination.Internal("a.bin"))
        f.executor.gate = CompletableDeferred()
        runCurrent()
        f.coordinator.pause(taskId)
        val workDir = File(File(f.workRoot, "acc-1"), taskId)
        workDir.mkdirs()
        val journal = File(workDir, "journal.tmp").apply { writeText("state") }

        // 传入与任务行一致的来源：不应重置断点
        f.executor.gate = null
        f.coordinator.resume(taskId, source(size = 400, etag = "etag-1"))
        advanceUntilIdle()

        assertTrue("来源一致时必须保留断点日志", journal.exists())
        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", taskId)!!.state)
    }

    // ---- 启动恢复 ----

    @Test
    fun recoverOnStartMovesLeftoverActiveRowsToWaitingUser() = runTest {
        val f = fixture()
        f.tasks.seed(
            task("queued", TransferState.QUEUED),
            task("resolving", TransferState.RESOLVING),
            task("running", TransferState.RUNNING),
            task("completing", TransferState.COMPLETING),
            task("done", TransferState.COMPLETED),
            task("paused", TransferState.PAUSED),
        )

        f.coordinator.recoverOnStart()

        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "queued")!!.state)
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "resolving")!!.state)
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "running")!!.state)
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "completing")!!.state)
        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", "done")!!.state)
        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", "paused")!!.state)
        assertFalse("启动恢复不得自动重启下载", f.executor.started)
    }

    private companion object {
        const val CDN_URL = "https://cdn.example.com/signed/file"

        fun source(
            fileId: Long = 1L,
            fileName: String = "a.bin",
            size: Long = 1_000L,
            etag: String = "etag-1",
        ) = DownloadSource(
            fileId = fileId,
            fileName = fileName,
            size = size,
            etag = etag,
            s3KeyFlag = "flag-1",
            isFolder = false,
        )

        fun task(taskId: String, state: TransferState) = TransferTaskEntity(
            accountId = "acc-1",
            taskId = taskId,
            fileId = 1L,
            fileName = "$taskId.bin",
            direction = TransferDirection.DOWNLOAD,
            state = state,
            size = 100,
            etag = "etag-$taskId",
        )
    }
}
