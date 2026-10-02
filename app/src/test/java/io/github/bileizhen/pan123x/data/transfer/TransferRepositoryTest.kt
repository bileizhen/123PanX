@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.data.transfer

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.DownloadSegmentDao
import io.github.bileizhen.pan123x.core.database.DownloadSegmentEntity
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskDao
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.database.UploadPartDao
import io.github.bileizhen.pan123x.core.database.UploadPartEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.transfer.download.DownloadCoordinator
import io.github.bileizhen.pan123x.core.transfer.download.DownloadExecutor
import io.github.bileizhen.pan123x.core.transfer.download.DownloadResolver
import io.github.bileizhen.pan123x.core.transfer.download.DownloadStorageGateway
import io.github.bileizhen.pan123x.core.transfer.download.ResolveOutcome
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadDestination
import io.github.bileizhen.pan123x.core.transfer.storage.OpenedSink
import io.github.bileizhen.pan123x.core.transfer.storage.StorageCheck
import io.github.bileizhen.pan123x.core.transfer.upload.UploadCoordinator
import io.github.bileizhen.pan123x.core.transfer.upload.UploadExecutor
import io.github.bileizhen.pan123x.core.transfer.upload.RandomAccessReader
import io.github.bileizhen.pan123x.core.transfer.upload.UploadSource
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadPartSnapshot
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadRequest
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadSession
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TransferRepository 行为测试（纯 JVM、不触网、不依赖 Room/Context）。
 *
 * 替身策略：[TransferTaskDao] 是接口，直接手写假实现（deleteFinished 由应用在接口上提供，
 * 这里按接口约定覆写并计数）；[UploadPartDao]/[DownloadSegmentDao] 是抽象类，子类化覆盖抽象方法。
 * 两个协调器用**真实现** + 假接缝构造——仓库的职责就是方向分派，用真协调器才能验证
 * "分派真的落到了对应协调器"。后台 launcher（object，依赖 Context）经构造注入收集器验证。
 */
class TransferRepositoryTest {

    private var taskScope: CoroutineScope? = null

    @After
    fun tearDown() {
        taskScope?.cancel()
        taskScope = null
    }

    // ---- 替身 ----

    private class FakeTransferTaskDao : TransferTaskDao {
        val rows = MutableStateFlow<List<TransferTaskEntity>>(emptyList())
        var deleteFinishedCalls = 0
            private set

        override fun observeTasks(accountId: String): Flow<List<TransferTaskEntity>> = rows
            .map { list -> list.filter { it.accountId == accountId }.sortedByDescending { it.createTime } }
            .distinctUntilChanged()

        override suspend fun get(accountId: String, taskId: String): TransferTaskEntity? =
            rows.value.firstOrNull { it.accountId == accountId && it.taskId == taskId }

        override suspend fun upsert(task: TransferTaskEntity) {
            rows.value = rows.value.filterNot { it.accountId == task.accountId && it.taskId == task.taskId } + task
        }

        override suspend fun delete(accountId: String, taskId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId && it.taskId == taskId }
        }

        override suspend fun updateProgress(
            accountId: String,
            taskId: String,
            downloadedBytes: Long,
            state: String,
            updateTime: Long,
        ) {
            val parsed = TransferState.valueOf(state)
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

        override suspend fun deleteFinished(accountId: String) {
            deleteFinishedCalls++
            rows.value = rows.value.filterNot {
                it.accountId == accountId && (it.state == TransferState.COMPLETED || it.state == TransferState.CANCELED)
            }
        }

        private companion object {
            val ACTIVE_STATES = setOf(
                TransferState.QUEUED, TransferState.RESOLVING, TransferState.RUNNING, TransferState.COMPLETING,
            )
        }
    }

    private class FakeDownloadSegmentDao : DownloadSegmentDao() {
        val rows = MutableStateFlow<List<DownloadSegmentEntity>>(emptyList())

        override fun observe(accountId: String, taskId: String): Flow<List<DownloadSegmentEntity>> = rows
            .map { list ->
                list.filter { it.accountId == accountId && it.taskId == taskId }.sortedBy { it.segmentIndex }
            }
            .distinctUntilChanged()

        override suspend fun insert(segments: List<DownloadSegmentEntity>) {
            val keys = segments.mapTo(mutableSetOf()) { Triple(it.accountId, it.taskId, it.segmentIndex) }
            rows.value = rows.value.filterNot { Triple(it.accountId, it.taskId, it.segmentIndex) in keys } + segments
        }

        override suspend fun clear(accountId: String, taskId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId && it.taskId == taskId }
        }
    }

    private class FakeUploadPartDao : UploadPartDao() {
        val rows = MutableStateFlow<List<UploadPartEntity>>(emptyList())

        override fun observe(accountId: String, taskId: String): Flow<List<UploadPartEntity>> = rows
            .map { list ->
                list.filter { it.accountId == accountId && it.taskId == taskId }.sortedBy { it.partNumber }
            }
            .distinctUntilChanged()

        override suspend fun insert(parts: List<UploadPartEntity>) {
            val keys = parts.mapTo(mutableSetOf()) { Triple(it.accountId, it.taskId, it.partNumber) }
            rows.value = rows.value.filterNot { Triple(it.accountId, it.taskId, it.partNumber) in keys } + parts
        }

        override suspend fun clear(accountId: String, taskId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId && it.taskId == taskId }
        }

        override suspend fun markUploaded(part: UploadPartEntity) {
            // 基类 markUploaded 是抽象方法；REPLACE 语义等价于单条 insert。
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

    /** 下载存储假接缝：open 恒失败，resume 只需证明"分派到了下载协调器"（行会经 failTask 转 FAILED）。 */
    private class NoStorage : DownloadStorageGateway {
        override fun check(destination: DownloadDestination): StorageCheck = StorageCheck.Ok
        override fun open(destination: DownloadDestination, totalSize: Long, existingUri: String?): OpenedSink? = null
        override fun complete(opened: OpenedSink, destination: DownloadDestination, size: Long): String? = "uri"
        override fun discard(opened: OpenedSink, destination: DownloadDestination) = Unit
    }

    private class FakeUploadExecutor : UploadExecutor {
        var outcome: UploadOutcome = UploadOutcome.Done(fileId = 42L, reused = false)
        var started = false
        val requests = mutableListOf<UploadRequest>()

        override suspend fun upload(
            request: UploadRequest,
            progress: (uploaded: Long, total: Long) -> Unit,
            session: (UploadSession) -> Unit,
            parts: (List<UploadPartSnapshot>) -> Unit,
        ): UploadOutcome {
            started = true
            requests += request
            return outcome
        }
    }

    private class Fixture(
        val taskDao: FakeTransferTaskDao,
        val segmentDao: FakeDownloadSegmentDao,
        val partDao: FakeUploadPartDao,
        val uploadExecutor: FakeUploadExecutor,
        val manager: AccountManager,
        val repository: TransferRepository,
        val backgroundCalls: MutableList<Triple<String, Long, String>>,
    )

    private fun TestScope.fixture(
        sourceForUri: suspend (String) -> UploadSource? = { null },
        loggedIn: Boolean = true,
    ): Fixture {
        val taskDao = FakeTransferTaskDao()
        val segmentDao = FakeDownloadSegmentDao()
        val partDao = FakeUploadPartDao()
        val manager = AccountManager()
        if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "1", "Bearer token")
        val logger = AppLogger()
        val backgroundCalls = mutableListOf<Triple<String, Long, String>>()
        val download = DownloadCoordinator(
            resolver = DownloadResolver { ResolveOutcome.Failure("测试不取链") },
            storage = NoStorage(),
            executor = DownloadExecutor { _, _, _, _, _ -> 0L },
            taskDao = taskDao,
            segmentDao = segmentDao,
            manager = manager,
            logger = logger,
            workRoot = File("build/tmp/transfer-repo-test/download"),
        )
        val uploadExecutor = FakeUploadExecutor()
        val upload = UploadCoordinator(
            executor = uploadExecutor,
            taskDao = taskDao,
            partDao = partDao,
            manager = manager,
            logger = logger,
        )
        // 协调器任务必须挂在可取消的普通 scope 上（同 UploadCoordinatorTest：不能用 backgroundScope，
        // advanceUntilIdle 只推进前台协程，任务状态机会停在 QUEUED）。
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        taskScope = scope
        download.attach(scope)
        upload.attach(scope)
        val repository = TransferRepository(
            context = null, // JVM 单测无法构造 Context；后台启动经注入的收集器验证
            taskDao = taskDao,
            segmentDao = segmentDao,
            partDao = partDao,
            download = download,
            upload = upload,
            accountManager = manager,
            uploadSourceFromUri = sourceForUri,
            logger = logger,
            backgroundStart = { taskId, estimatedBytes, title ->
                backgroundCalls += Triple(taskId, estimatedBytes, title)
            },
        )
        return Fixture(taskDao, segmentDao, partDao, uploadExecutor, manager, repository, backgroundCalls)
    }

    // ---- 任务流 ----

    @Test
    fun observeTasksFollowsAccountState() = runTest {
        val f = fixture(loggedIn = false)
        f.taskDao.rows.value = listOf(
            task("t1", TransferDirection.DOWNLOAD, TransferState.RUNNING),
            task("t2", TransferDirection.UPLOAD, TransferState.RUNNING).copy(accountId = "acc-2"),
        )

        // 未登录：空表；登录后只看得到当前账户（跨账户缓存绝不互串）
        assertTrue(f.repository.observeTasks().first().isEmpty())

        f.manager.onLoginSuccess("acc-1", "user@example.com", "1", "Bearer token")
        val visible = f.repository.observeTasks().first()
        assertEquals(listOf("t1"), visible.map { it.taskId })
    }

    // ---- 方向分派：暂停 / 取消 ----

    @Test
    fun pauseAndCancelDispatchByDirection() = runTest {
        val f = fixture()
        f.taskDao.rows.value = listOf(
            task("d", TransferDirection.DOWNLOAD, TransferState.RUNNING),
            task("u", TransferDirection.UPLOAD, TransferState.RUNNING),
            task("x", TransferDirection.UPLOAD, TransferState.WAITING_USER),
        )

        f.repository.pause("acc-1", "d")
        f.repository.pause("acc-1", "u")
        f.repository.cancel("acc-1", "x")

        assertEquals(TransferState.PAUSED, f.taskDao.get("acc-1", "d")!!.state)
        assertEquals(TransferState.PAUSED, f.taskDao.get("acc-1", "u")!!.state)
        assertEquals(TransferState.CANCELED, f.taskDao.get("acc-1", "x")!!.state)
    }

    // ---- 方向分派：恢复 ----

    @Test
    fun resumeDispatchesDownloadAndRequestsBackground() = runTest {
        val f = fixture()
        f.taskDao.rows.value = listOf(task("d1", TransferDirection.DOWNLOAD, TransferState.PAUSED, size = 500))

        f.repository.resume("acc-1", "d1")
        advanceUntilIdle()

        // 假 resolver 恒失败：终态 FAILED 恰好证明分派进入了下载协调器；后台启动携带任务行的 size
        assertEquals(Triple("d1", 500L, "d1.bin"), f.backgroundCalls.single())
        assertEquals(TransferState.FAILED, f.taskDao.get("acc-1", "d1")!!.state)
        assertFalse("下载恢复不得触发上传引擎", f.uploadExecutor.started)
    }

    @Test
    fun resumeSkipsBackgroundForCompletedRows() = runTest {
        val f = fixture()
        f.taskDao.rows.value = listOf(task("d1", TransferDirection.DOWNLOAD, TransferState.COMPLETED))

        f.repository.resume("acc-1", "d1")
        advanceUntilIdle()

        assertTrue(f.backgroundCalls.isEmpty())
        assertEquals(TransferState.COMPLETED, f.taskDao.get("acc-1", "d1")!!.state)
    }

    @Test
    fun resumeUploadRebuildsSourceFromUri() = runTest {
        val f = fixture(sourceForUri = { uri ->
            if (uri == "content://source/bin") FakeUploadSource(displayName = "a.bin", size = 1_000L) else null
        })
        f.taskDao.rows.value = listOf(
            task("u1", TransferDirection.UPLOAD, TransferState.PAUSED, size = 1_000L, targetUri = "content://source/bin"),
        )

        f.repository.resume("acc-1", "u1")
        advanceUntilIdle()

        assertTrue(f.uploadExecutor.started)
        assertEquals(TransferState.COMPLETED, f.taskDao.get("acc-1", "u1")!!.state)
        assertEquals(Triple("u1", 1_000L, "a.bin"), f.backgroundCalls.single())
    }

    @Test
    fun resumeUploadWithoutRecoverableSourceKeepsTaskIdle() = runTest {
        val f = fixture(sourceForUri = { null })
        f.taskDao.rows.value = listOf(
            task("u1", TransferDirection.UPLOAD, TransferState.PAUSED, size = 1_000L, targetUri = "content://gone"),
        )

        f.repository.resume("acc-1", "u1")
        advanceUntilIdle()

        // uri 不可恢复：记录日志并保持现状（PAUSED），绝不启动上传或后台 job
        assertFalse(f.uploadExecutor.started)
        assertEquals(TransferState.PAUSED, f.taskDao.get("acc-1", "u1")!!.state)
        assertTrue(f.backgroundCalls.isEmpty())

        // targetUri 为空同样不恢复
        f.taskDao.rows.value = listOf(task("u2", TransferDirection.UPLOAD, TransferState.PAUSED, targetUri = ""))
        f.repository.resume("acc-1", "u2")
        advanceUntilIdle()
        assertFalse(f.uploadExecutor.started)
        assertEquals(TransferState.PAUSED, f.taskDao.get("acc-1", "u2")!!.state)
        assertTrue(f.backgroundCalls.isEmpty())
    }

    // ---- 冲突 ----

    @Test
    fun resolveConflictDispatchesToUploadWithChosenPolicy() = runTest {
        val f = fixture(sourceForUri = { FakeUploadSource(displayName = "a.bin", size = 1_000L) })
        f.uploadExecutor.outcome = UploadOutcome.Conflict("同名文件")
        f.taskDao.rows.value = listOf(
            task("u1", TransferDirection.UPLOAD, TransferState.PAUSED, size = 1_000L, targetUri = "content://source"),
        )

        f.repository.resume("acc-1", "u1")
        advanceUntilIdle()
        assertEquals(TransferState.WAITING_USER, f.taskDao.get("acc-1", "u1")!!.state)

        f.uploadExecutor.outcome = UploadOutcome.Done(fileId = 9L, reused = false)
        f.repository.resolveConflict("acc-1", "u1", ConflictPolicy.OVERWRITE)
        advanceUntilIdle()

        assertEquals(TransferState.COMPLETED, f.taskDao.get("acc-1", "u1")!!.state)
        assertEquals(ConflictPolicy.OVERWRITE, f.uploadExecutor.requests.last().policy)
    }

    @Test
    fun resolveConflictIgnoresDownloadRows() = runTest {
        val f = fixture()
        f.taskDao.rows.value = listOf(task("d1", TransferDirection.DOWNLOAD, TransferState.FAILED))

        f.repository.resolveConflict("acc-1", "d1", ConflictPolicy.KEEP_BOTH)
        advanceUntilIdle()

        assertEquals(TransferState.FAILED, f.taskDao.get("acc-1", "d1")!!.state)
        assertFalse(f.uploadExecutor.started)
    }

    // ---- 分片视图 ----

    @Test
    fun observePartsMapsDownloadSegments() = runTest {
        val f = fixture()
        f.taskDao.rows.value = listOf(
            task("d1", TransferDirection.DOWNLOAD, TransferState.RUNNING, size = 150),
        )
        f.segmentDao.rows.value = listOf(
            DownloadSegmentEntity("acc-1", "d1", 0, start = 0, end = 100, downloaded = 40),
            DownloadSegmentEntity("acc-1", "d1", 1, start = 100, end = 150, downloaded = 50),
        )

        val parts = f.repository.observeParts("acc-1", "d1").first()

        assertEquals(
            listOf(
                TransferPartView(0, 100, 40, false),
                TransferPartView(1, 50, 50, true),
            ),
            parts,
        )
    }

    @Test
    fun observePartsDerivesFullUploadPlan() = runTest {
        val f = fixture()
        // 12_582_912 字节 = 2.4 块 → 3 片（5 MiB + 5 MiB + 2 MiB）；blockSize=0 时回退默认块大小
        f.taskDao.rows.value = listOf(
            task(
                "u1", TransferDirection.UPLOAD, TransferState.RUNNING,
                size = 12_582_912L, blockSize = 0L,
            ),
        )
        f.partDao.rows.value = listOf(UploadPartEntity("acc-1", "u1", partNumber = 2, size = 5_242_880))

        val parts = f.repository.observeParts("acc-1", "u1").first()

        assertEquals(listOf(1, 2, 3), parts.map { it.index })
        assertEquals(5_242_880L, parts[0].size)
        assertEquals(5_242_880L, parts[1].size)
        assertEquals(2_097_152L, parts[2].size)
        // 已传分片 transferred=整片长度；未传分片不在表里，由计划推导且 transferred=0
        assertFalse(parts[0].done)
        assertTrue(parts[1].done)
        assertEquals(5_242_880L, parts[1].transferred)
        assertFalse(parts[2].done)
        assertEquals(0L, parts[2].transferred)
    }

    // ---- 清理与入队 ----

    @Test
    fun clearFinishedDelegatesToDeleteFinished() = runTest {
        val f = fixture()
        f.taskDao.rows.value = listOf(
            task("done", TransferDirection.DOWNLOAD, TransferState.COMPLETED),
            task("canceled", TransferDirection.UPLOAD, TransferState.CANCELED),
            task("running", TransferDirection.DOWNLOAD, TransferState.RUNNING),
        )

        f.repository.clearFinished("acc-1")

        assertEquals(1, f.taskDao.deleteFinishedCalls)
        assertEquals(listOf("running"), f.taskDao.rows.value.map { it.taskId })
    }

    @Test
    fun enqueueUploadStartsBackgroundJob() = runTest {
        val f = fixture()

        val taskId = f.repository.enqueueUpload(
            FakeUploadSource(displayName = "a.bin", size = 1_234L),
            parentFileId = 7L,
        )
        advanceUntilIdle()

        assertEquals(Triple(taskId, 1_234L, "a.bin"), f.backgroundCalls.single())
        val row = f.taskDao.get("acc-1", taskId)!!
        assertEquals(TransferDirection.UPLOAD, row.direction)
        assertEquals(7L, row.parentFileId)
    }

    private companion object {
        fun task(
            taskId: String,
            direction: TransferDirection,
            state: TransferState,
            size: Long = 1_000L,
            targetUri: String = "",
            blockSize: Long = 0L,
        ) = TransferTaskEntity(
            accountId = "acc-1",
            taskId = taskId,
            fileId = if (direction == TransferDirection.DOWNLOAD) 1L else null,
            fileName = "$taskId.bin",
            direction = direction,
            state = state,
            size = size,
            etag = "etag",
            targetUri = targetUri,
            blockSize = blockSize,
        )
    }
}
