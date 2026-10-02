@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.core.transfer.upload

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskDao
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.database.UploadPartDao
import io.github.bileizhen.pan123x.core.database.UploadPartEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadPartSnapshot
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadRequest
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadSession
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * UploadCoordinator 行为测试（纯 JVM、不触网、不依赖 Room/Context）。
 *
 * 替身策略：手写 [TransferTaskDao] / [UploadPartDao] 内存替身（可观察，基于 MutableStateFlow），
 * 以及 [UploadExecutor] 与 [UploadSource] 替身；AccountManager / AppLogger 用真实现。Room 与
 * Android Context 都被挡在协调器之外，因此本文件只验证编排语义：先落库再执行、状态机顺序、会话/
 * 分片落库、节流、冲突交互、暂停/取消终态、续传会话重建、启动恢复。
 */
class UploadCoordinatorTest {

    // ---- 替身 ----

    private data class ProgressCall(
        val accountId: String,
        val taskId: String,
        val uploadedBytes: Long,
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

        override suspend fun activeTasksByDirection(direction: String): List<TransferTaskEntity> =
            rows.value.filter { it.direction.name == direction && it.state in ACTIVE_STATES }

        private companion object {
            val ACTIVE_STATES = setOf(
                TransferState.QUEUED, TransferState.RESOLVING, TransferState.RUNNING, TransferState.COMPLETING,
            )
        }
    }

    private class FakeUploadPartDao : UploadPartDao() {
        val rows = MutableStateFlow<List<UploadPartEntity>>(emptyList())
        var replaceCalls = 0
        var markCalls = 0

        fun seed(vararg parts: UploadPartEntity) {
            rows.value = rows.value + parts
        }

        override fun observe(accountId: String, taskId: String): Flow<List<UploadPartEntity>> = rows
            .map { list -> list.filter { it.accountId == accountId && it.taskId == taskId }.sortedBy { it.partNumber } }
            .distinctUntilChanged()

        override suspend fun insert(parts: List<UploadPartEntity>) {
            val keys = parts.mapTo(mutableSetOf()) { Triple(it.accountId, it.taskId, it.partNumber) }
            rows.value = rows.value.filterNot { Triple(it.accountId, it.taskId, it.partNumber) in keys } + parts
        }

        override suspend fun clear(accountId: String, taskId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId && it.taskId == taskId }
        }

        override suspend fun replaceFor(accountId: String, taskId: String, parts: List<UploadPartEntity>) {
            replaceCalls++
            super.replaceFor(accountId, taskId, parts)
        }

        override suspend fun markUploaded(part: UploadPartEntity) {
            markCalls++
            // 基类的 markUploaded 是抽象方法（无法 super 调用）；REPLACE 语义等价于 insert 单条。
            insert(listOf(part))
        }
    }

    private class FakeUploadSource(
        override val displayName: String = "a.bin",
        override val size: Long = 1_000L,
        override val lastModified: Long = 1_000L,
    ) : UploadSource {
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun openRandomAccess(): RandomAccessReader? = null
    }

    private class FakeUploadExecutor : UploadExecutor {
        var outcome: UploadOutcome = UploadOutcome.Done(fileId = 42L, reused = false)
        var failure: Exception? = null
        var session: UploadSession? = null
        var initialParts: List<UploadPartSnapshot>? = null
        var completedParts: List<UploadPartSnapshot> = emptyList()
        var ticks = 0
        var tickDelayMs = 0L
        var gate: CompletableDeferred<Unit>? = null
        var started = false
        var finished = false
        var progressTicks = 0
        var partEmissions = 0
        val requests = mutableListOf<UploadRequest>()

        override suspend fun upload(
            request: UploadRequest,
            progress: (uploaded: Long, total: Long) -> Unit,
            session: (UploadSession) -> Unit,
            parts: (List<UploadPartSnapshot>) -> Unit,
        ): UploadOutcome {
            started = true
            requests += request
            failure?.let { throw it }
            this.session?.let { active -> session(active) }
            initialParts?.let { plan ->
                partEmissions++
                parts(plan)
            }
            repeat(ticks) { index ->
                if (tickDelayMs > 0) delay(tickDelayMs)
                progressTicks++
                progress((index + 1) * 100L, request.source.size)
            }
            completedParts.forEach { part ->
                partEmissions++
                parts(listOf(part))
            }
            gate?.await()
            finished = true
            return outcome
        }
    }

    private class Fixture(
        val tasks: FakeTransferTaskDao,
        val parts: FakeUploadPartDao,
        val executor: FakeUploadExecutor,
        val coordinator: UploadCoordinator,
    )

    private fun TestScope.fixture(progressThrottleMs: Long = 400): Fixture {
        val tasks = FakeTransferTaskDao()
        val parts = FakeUploadPartDao()
        val executor = FakeUploadExecutor()
        val manager = AccountManager().apply { onLoginSuccess("acc-1", "user@example.com", "1", "Bearer token") }
        val coordinator = UploadCoordinator(
            executor = executor,
            taskDao = tasks,
            partDao = parts,
            manager = manager,
            logger = AppLogger(),
            progressThrottleMs = progressThrottleMs,
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        taskScope = scope
        coordinator.attach(scope)
        return Fixture(tasks, parts, executor, coordinator)
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

        val taskId = f.coordinator.enqueue(source(size = 1_000, mtime = 7L), parentFileId = 99L)

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.QUEUED, row.state)
        assertEquals(TransferDirection.UPLOAD, row.direction)
        assertEquals(99L, row.parentFileId)
        assertEquals(7L, row.sourceMtime)
        assertEquals(UploadPartPlan.BLOCK_SIZE, row.blockSize)
        assertEquals("", row.etag)
        assertNull(row.fileId)
        assertEquals(0L, row.downloadedBytes)
        // 先落库再执行：任务行已存在，但执行器还没被调用
        assertFalse(f.executor.started)
        assertTrue(f.tasks.progressCalls.isEmpty())
    }

    @Test
    fun enqueueRequiresLogin() = runTest {
        val manager = AccountManager()
        val coordinator = UploadCoordinator(
            executor = FakeUploadExecutor(),
            taskDao = FakeTransferTaskDao(),
            partDao = FakeUploadPartDao(),
            manager = manager,
            logger = AppLogger(),
        )
        coordinator.attach(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        var thrown = false
        try {
            coordinator.enqueue(source(), parentFileId = 0L)
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    // ---- 状态机 ----

    @Test
    fun stateMachineReachesCompletedInOrder() = runTest {
        val f = fixture()
        f.executor.session = session()
        f.executor.ticks = 2

        val taskId = f.coordinator.enqueue(source(size = 200), parentFileId = 0L)
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.COMPLETED, row.state)
        assertEquals(200L, row.downloadedBytes)
        assertEquals(42L, row.fileId)
        assertTrue(f.executor.started)
        assertTrue(f.executor.finished)

        val states = f.tasks.stateLog
        assertEquals(TransferState.QUEUED, states.first())
        assertTrue(states.indexOf(TransferState.RESOLVING) < states.indexOf(TransferState.RUNNING))
        assertTrue(states.indexOf(TransferState.RUNNING) < states.indexOf(TransferState.COMPLETING))
        assertTrue(states.indexOf(TransferState.COMPLETING) < states.indexOf(TransferState.COMPLETED))
    }

    @Test
    fun reuseOutcomeCompletesWithoutSessionCallback() = runTest {
        val f = fixture()
        f.executor.outcome = UploadOutcome.Done(fileId = 7L, reused = true)

        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.COMPLETED, row.state)
        assertEquals(7L, row.fileId)
    }

    @Test
    fun engineFailurePersistsFailedWithTransferMessage() = runTest {
        val f = fixture()
        f.executor.failure = java.io.IOException("broken pipe")

        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals(UploadMessages.transferFailure(java.io.IOException("broken pipe")), row.error)
    }

    // ---- 会话 / 分片落库 ----

    @Test
    fun sessionCallbackPersistsS3SessionFieldsImmediately() = runTest {
        val f = fixture()
        f.executor.session = session(
            bucket = "bkt", storageNode = "node-1", uploadKey = "key-1",
            uploadId = "uid-1", fileId = 55L, etag = "md5-abc", blockSize = UploadPartPlan.BLOCK_SIZE,
        )

        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals("bkt", row.bucket)
        assertEquals("node-1", row.storageNode)
        assertEquals("key-1", row.uploadKey)
        assertEquals("uid-1", row.uploadId)
        assertEquals(55L, row.fileId)
        assertEquals("md5-abc", row.etag)
        assertEquals(UploadPartPlan.BLOCK_SIZE, row.blockSize)
    }

    @Test
    fun partsPlanReplacesOnceThenMarksEachCompletedPart() = runTest {
        val f = fixture()
        val part1 = UploadPartSnapshot(1, 5)
        val part2 = UploadPartSnapshot(2, 3)
        f.executor.initialParts = listOf(part1, part2)
        f.executor.completedParts = listOf(part1, part2)

        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        advanceUntilIdle()

        assertEquals(1, f.parts.replaceCalls)
        assertEquals(2, f.parts.markCalls)
        assertEquals(listOf(1, 2), f.parts.rows.value.map { it.partNumber })
    }

    // ---- 进度节流 ----

    @Test
    fun progressWritesAreThrottledToConfiguredInterval() = runTest {
        val f = fixture(progressThrottleMs = 400)
        f.executor.session = session()
        f.executor.ticks = 20
        f.executor.tickDelayMs = 50

        val taskId = f.coordinator.enqueue(source(size = 2_000), parentFileId = 0L)
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

    // ---- 冲突 ----

    @Test
    fun conflictMovesTaskToWaitingUserWithHint() = runTest {
        val f = fixture()
        f.executor.outcome = UploadOutcome.Conflict("同名文件")

        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        advanceUntilIdle()

        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.WAITING_USER, row.state)
        assertEquals(UploadMessages.CONFLICT_TITLE, row.error)
        assertEquals(ConflictPolicy.ASK, f.executor.requests.single().policy)
    }

    @Test
    fun resolveConflictRerunsWithChosenPolicy() = runTest {
        val f = fixture()
        f.executor.outcome = UploadOutcome.Conflict("同名文件")
        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        advanceUntilIdle()
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", taskId)!!.state)

        f.executor.outcome = UploadOutcome.Done(fileId = 88L, reused = false)
        f.coordinator.resolveConflict(taskId, ConflictPolicy.OVERWRITE)
        advanceUntilIdle()

        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", taskId)!!.state)
        assertEquals(2, f.executor.requests.size)
        assertEquals(ConflictPolicy.OVERWRITE, f.executor.requests.last().policy)
        assertNull(f.tasks.get("acc-1", taskId)!!.error)
    }

    // ---- 暂停 / 取消 ----

    @Test
    fun pausePersistsPausedAndKeepsParts() = runTest {
        val f = fixture()
        f.executor.session = session()
        f.executor.gate = CompletableDeferred()
        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        runCurrent()
        assertEquals(TransferState.RUNNING, f.tasks.get("acc-1", taskId)!!.state)

        f.parts.seed(UploadPartEntity("acc-1", taskId, 1, 5))
        f.coordinator.pause(taskId)

        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", taskId)!!.state)
        assertFalse(f.executor.finished)
        assertTrue("暂停必须保留分片表以便续传", f.parts.rows.value.isNotEmpty())
    }

    @Test
    fun cancelPersistsCanceledAndClearsParts() = runTest {
        val f = fixture()
        f.executor.session = session()
        f.executor.gate = CompletableDeferred()
        val taskId = f.coordinator.enqueue(source(), parentFileId = 0L)
        runCurrent()

        f.parts.seed(
            UploadPartEntity("acc-1", taskId, 1, 5),
            UploadPartEntity("acc-1", taskId, 2, 3),
        )

        f.coordinator.cancel(taskId)

        assertEquals(TransferState.CANCELED, f.tasks.get("acc-1", taskId)!!.state)
        assertTrue("取消必须清空分片表", f.parts.rows.value.isEmpty())
    }

    @Test
    fun pauseOnTaskWithoutActiveJobWritesTerminalStateDirectly() = runTest {
        val f = fixture()
        f.tasks.seed(uploadTask("waiting", TransferState.WAITING_USER))

        f.coordinator.pause("waiting")

        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", "waiting")!!.state)
    }

    // ---- 续传 ----

    @Test
    fun resumePassesStoredSessionToEngine() = runTest {
        val f = fixture()
        val taskId = "task-fixed"
        f.tasks.seed(
            uploadTask(
                taskId = taskId,
                state = TransferState.PAUSED,
                size = 1_000L,
                sourceMtime = 1_000L,
                bucket = "bkt",
                storageNode = "node",
                uploadKey = "key",
                uploadId = "uid",
                fileId = 77L,
                etag = "md5-old",
            ),
        )

        f.coordinator.resume(taskId, source(size = 1_000, mtime = 1_000))
        advanceUntilIdle()

        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", taskId)!!.state)
        val request = f.executor.requests.single()
        val session = request.resumeSession
        assertTrue("会话五字段齐全时必须复用 S3 会话", session != null)
        assertEquals("bkt", session!!.bucket)
        assertEquals("uid", session.uploadId)
        assertEquals(77L, session.fileId)
        assertEquals("md5-old", session.etag)
    }

    @Test
    fun resumeWithChangedSourceDropsSessionAndParts() = runTest {
        val f = fixture()
        val taskId = "task-fixed"
        f.tasks.seed(
            uploadTask(
                taskId = taskId,
                state = TransferState.PAUSED,
                size = 100L,
                sourceMtime = 1_000L,
                bucket = "bkt",
                storageNode = "node",
                uploadKey = "key",
                uploadId = "uid",
                fileId = 77L,
            ),
        )
        f.parts.seed(UploadPartEntity("acc-1", taskId, 1, 5))

        // size 与任务行不符 → 会话失效，重置后从头上传
        f.coordinator.resume(taskId, source(size = 200, mtime = 1_000))
        advanceUntilIdle()

        assertTrue("来源变化必须清空分片表", f.parts.rows.value.isEmpty())
        val row = f.tasks.get("acc-1", taskId)!!
        assertEquals(TransferState.COMPLETED, row.state)
        assertEquals(200L, row.size)
        assertEquals("", row.bucket)
        assertNull(f.executor.requests.single().resumeSession)
    }

    @Test
    fun resumeWithoutSourceKeepsTaskIdle() = runTest {
        val f = fixture()
        val taskId = "task-fixed"
        f.tasks.seed(uploadTask(taskId = taskId, state = TransferState.PAUSED))

        f.coordinator.resume(taskId)
        advanceUntilIdle()

        assertFalse("缺少来源时不得启动上传", f.executor.started)
        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", taskId)!!.state)
    }

    // ---- 启动恢复 ----

    @Test
    fun recoverOnStartMovesLeftoverUploadsToWaitingUser() = runTest {
        val f = fixture()
        f.tasks.seed(
            uploadTask("queued", TransferState.QUEUED),
            uploadTask("resolving", TransferState.RESOLVING),
            uploadTask("running", TransferState.RUNNING),
            uploadTask("completing", TransferState.COMPLETING),
            uploadTask("done", TransferState.COMPLETED),
            uploadTask("paused", TransferState.PAUSED),
            downloadTask("download-running", TransferState.RUNNING),
        )

        f.coordinator.recoverOnStart()

        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "queued")!!.state)
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "resolving")!!.state)
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "running")!!.state)
        assertEquals(TransferState.WAITING_USER, f.tasks.get("acc-1", "completing")!!.state)
        assertEquals(TransferState.COMPLETED, f.tasks.get("acc-1", "done")!!.state)
        assertEquals(TransferState.PAUSED, f.tasks.get("acc-1", "paused")!!.state)
        assertEquals(
            "启动恢复不得改动下载任务",
            TransferState.RUNNING,
            f.tasks.get("acc-1", "download-running")!!.state,
        )
        assertFalse("启动恢复不得自动重启上传", f.executor.started)
    }

    private companion object {
        fun source(
            displayName: String = "a.bin",
            size: Long = 1_000L,
            mtime: Long = 1_000L,
        ) = FakeUploadSource(displayName = displayName, size = size, lastModified = mtime)

        fun session(
            bucket: String = "bucket",
            storageNode: String = "node",
            uploadKey: String = "key",
            uploadId: String = "upload-id",
            fileId: Long = 42L,
            etag: String = "md5",
            blockSize: Long = UploadPartPlan.BLOCK_SIZE,
        ) = UploadSession(
            bucket = bucket,
            storageNode = storageNode,
            uploadKey = uploadKey,
            uploadId = uploadId,
            fileId = fileId,
            etag = etag,
            blockSize = blockSize,
        )

        fun uploadTask(
            taskId: String,
            state: TransferState,
            size: Long = 1_000L,
            sourceMtime: Long = 1_000L,
            bucket: String = "",
            storageNode: String = "",
            uploadKey: String = "",
            uploadId: String = "",
            fileId: Long? = null,
            etag: String = "",
        ) = TransferTaskEntity(
            accountId = "acc-1",
            taskId = taskId,
            fileId = fileId,
            fileName = "$taskId.bin",
            direction = TransferDirection.UPLOAD,
            state = state,
            size = size,
            etag = etag,
            parentFileId = 0L,
            sourceMtime = sourceMtime,
            bucket = bucket,
            storageNode = storageNode,
            uploadKey = uploadKey,
            uploadId = uploadId,
        )

        fun downloadTask(taskId: String, state: TransferState) = TransferTaskEntity(
            accountId = "acc-1",
            taskId = taskId,
            fileId = 1L,
            fileName = "$taskId.bin",
            direction = TransferDirection.DOWNLOAD,
            state = state,
            size = 100,
            etag = "etag",
        )
    }
}
