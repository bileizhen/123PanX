@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.transfer

import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.data.transfer.TransferPartView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TransferViewModel 行为测试（纯 JVM，注入 [TransferTasksSource] 假实现）。
 *
 * 覆盖：筛选分类映射、加载/空态、速度估算（初值为 0、拍间差分 + 平滑、非活跃归零）、
 * 冲突弹窗状态机（WAITING_USER → 弹窗 → 豁免/处理 → 弹窗消失）、取消二次确认、
 * stopBackground 的跳变沿去重、动作透传 accountId。速度采样依赖注入的 nowMillis 与
 * 虚拟时间（UnconfinedTestDispatcher 的 scheduler），与真实时钟解耦、完全确定。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    private val owner = ViewModelStore()
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun cleanup() {
        owner.clear()
        Dispatchers.resetMain()
    }

    // ---- 替身 ----

    private class FakeTransferTasksSource : TransferTasksSource {
        val rows = MutableStateFlow<List<TransferTaskEntity>>(emptyList())
        var paused = 0
            private set
        var resumed = 0
            private set
        var canceled = 0
            private set
        var cleared = 0
            private set
        val conflicts = mutableListOf<Pair<String, ConflictPolicy>>()

        override fun observeTasks(): Flow<List<TransferTaskEntity>> =
            rows.map { it.toList() }.distinctUntilChanged()

        override fun observeParts(accountId: String, taskId: String): Flow<List<TransferPartView>> =
            flowOf(emptyList())

        override suspend fun pause(accountId: String, taskId: String) {
            paused++
        }

        override suspend fun resume(accountId: String, taskId: String) {
            resumed++
        }

        override suspend fun cancel(accountId: String, taskId: String) {
            canceled++
        }

        override suspend fun resolveConflict(accountId: String, taskId: String, policy: ConflictPolicy) {
            conflicts += taskId to policy
        }

        override suspend fun clearFinished(accountId: String) {
            cleared++
        }
    }

    private fun viewModel(
        source: FakeTransferTasksSource,
        stopBackground: (String) -> Unit = {},
        nowMillis: () -> Long = System::currentTimeMillis,
    ): TransferViewModel = TransferViewModel(
        source = source,
        stopBackground = stopBackground,
        sampleIntervalMs = 1_000L,
        nowMillis = nowMillis,
    ).also { owner.put("transfer", it) }

    // ---- 筛选与状态 ----

    @Test fun queuedDownloadNavigationClearsOldSearchAndShowsActiveWithPartialFailure() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(task("new-download", TransferState.QUEUED), task("old", TransferState.COMPLETED))
        val model = viewModel(source)
        model.selectFilter(TransferFilter.COMPLETED)
        model.setSearchOpen(true)
        model.setSearch("old")
        model.showQueuedDownloads("另一个文件加入失败")
        assertEquals(TransferFilter.ACTIVE, model.uiState.value.filter)
        assertEquals("", model.uiState.value.search)
        assertEquals(false, model.uiState.value.searchOpen)
        assertEquals(listOf("new-download"), model.uiState.value.visible.map { it.taskId })
        assertEquals("另一个文件加入失败", model.uiState.value.actionError)
    }

    @Test
    fun filterCategoriesMapStatesCorrectly() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(
            task("run", TransferState.RUNNING),
            task("queued", TransferState.QUEUED),
            task("paused", TransferState.PAUSED),
            task("failed", TransferState.FAILED),
            task("waiting", TransferState.WAITING_USER),
            task("done", TransferState.COMPLETED),
            task("canceled", TransferState.CANCELED),
            task("net", TransferState.WAITING_NETWORK),
        )
        val model = viewModel(source)
        val state = model.uiState.value

        // 进行中 = QUEUED/RESOLVING/RUNNING/COMPLETING
        assertEquals(listOf("run", "queued"), state.visible.map { it.taskId })
        assertEquals(TransferContentStatus.CONTENT, state.status)
        assertEquals(2, state.activeCount)

        // 已停止 = PAUSED/WAITING_NETWORK/WAITING_USER/FAILED
        model.selectFilter(TransferFilter.STOPPED)
        assertEquals(listOf("paused", "failed", "waiting", "net"), model.uiState.value.visible.map { it.taskId })

        // 已完成；CANCELED 只出现在"全部"
        model.selectFilter(TransferFilter.COMPLETED)
        assertEquals(listOf("done"), model.uiState.value.visible.map { it.taskId })
        model.selectFilter(TransferFilter.ALL)
        assertEquals(8, model.uiState.value.visible.size)
        assertEquals(TransferFilter.ALL, model.uiState.value.filter)
    }

    @Test
    fun emptySourceYieldsEmptyStatusWithZeroSpeeds() {
        val model = viewModel(FakeTransferTasksSource())
        val state = model.uiState.value
        assertEquals(TransferContentStatus.EMPTY, state.status)
        assertEquals(0, state.activeCount)
        assertEquals(0L, state.downloadSpeed)
        assertEquals(0L, state.uploadSpeed)
        assertTrue(state.speeds.isEmpty())
    }

    // ---- 速度估算 ----

    @Test fun searchAndBulkActionsOnlyAffectMatchingEligibleTasks() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(task("wanted-run", TransferState.RUNNING), task("other-run", TransferState.RUNNING),
            task("wanted-paused", TransferState.PAUSED), task("wanted-conflict", TransferState.WAITING_USER))
        val model = viewModel(source)
        model.selectFilter(TransferFilter.ALL)
        model.setSearch("  WANTED  ")
        assertEquals(3, model.uiState.value.visible.size)
        model.pauseVisible()
        model.resumeVisible()
        assertEquals(1, source.paused)
        assertEquals(1, source.resumed)
        model.selectFilter(TransferFilter.ACTIVE)
        assertEquals(listOf("wanted-run"), model.uiState.value.visible.map { it.taskId })
    }

    @Test fun dismissingOneConflictExposesNextWaitingTask() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(task("one", TransferState.WAITING_USER, TransferDirection.UPLOAD, error = "同名冲突"),
            task("two", TransferState.WAITING_USER, TransferDirection.UPLOAD, error = "同名冲突"))
        val model = viewModel(source)
        assertEquals("one", model.uiState.value.conflictTask?.taskId)
        model.dismissConflict()
        assertEquals("two", model.uiState.value.conflictTask?.taskId)
        model.dismissConflict()
        assertNull(model.uiState.value.conflictTask)
    }

    @Test
    fun speedEstimateStartsAtZeroThenFollowsDeltaWithSmoothing() {
        var now = 0L
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(
            task("d", TransferState.RUNNING, direction = TransferDirection.DOWNLOAD),
            task("u", TransferState.RUNNING, direction = TransferDirection.UPLOAD),
            task("p", TransferState.PAUSED, direction = TransferDirection.DOWNLOAD),
        )
        val model = viewModel(source, nowMillis = { now })

        // 初值：未建立基线前一律 0（写明这是估算）
        assertEquals(0L, model.uiState.value.downloadSpeed)
        assertEquals(0L, model.uiState.value.uploadSpeed)

        // 拍 1：建立基线（虚拟时间 0ms，进度无变化）
        mainDispatcher.scheduler.advanceTimeBy(1_000)
        mainDispatcher.scheduler.runCurrent()
        assertEquals(0L, model.uiState.value.downloadSpeed)

        // 拍 2：1s 内下载 +2MB、上传 +0.5MB → 估算恰为差分值（首次平滑基线取自身）
        now = 1_000
        source.rows.value = listOf(
            task("d", TransferState.RUNNING, direction = TransferDirection.DOWNLOAD, downloaded = 2_000_000),
            task("u", TransferState.RUNNING, direction = TransferDirection.UPLOAD, downloaded = 500_000),
            task("p", TransferState.PAUSED, direction = TransferDirection.DOWNLOAD, downloaded = 9_999_999),
        )
        mainDispatcher.scheduler.advanceTimeBy(1_000)
        mainDispatcher.scheduler.runCurrent()

        val state = model.uiState.value
        assertEquals(2_000_000L, state.downloadSpeed)
        assertEquals(500_000L, state.uploadSpeed)
        // 非活跃任务不产生速度估算
        assertEquals(0L, state.speeds["p"] ?: 0L)
    }

    // ---- 冲突弹窗状态机 ----

    @Test
    fun conflictDialogFollowsWaitingUserTasks() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(
            task("u1", TransferState.WAITING_USER, direction = TransferDirection.UPLOAD, error = "存在同名文件"),
            // 非冲突的 WAITING_USER（如进程重启恢复）不得弹冲突窗
            task("u2", TransferState.WAITING_USER, direction = TransferDirection.UPLOAD, error = "缺少上传来源"),
        )
        val model = viewModel(source)
        assertEquals("u1", model.uiState.value.conflictTask?.taskId)

        // 用户点"取消"：弹窗消失且保持豁免（任务仍在等待态时不再弹出）
        model.dismissConflict()
        assertNull(model.uiState.value.conflictTask)

        // 任务离开等待态后豁免被清理；再次冲突时弹窗重新出现
        source.rows.value = listOf(task("u1", TransferState.RUNNING, direction = TransferDirection.UPLOAD))
        source.rows.value = listOf(
            task("u1", TransferState.WAITING_USER, direction = TransferDirection.UPLOAD, error = "存在同名文件"),
        )
        assertEquals("u1", model.uiState.value.conflictTask?.taskId)

        // 选择处理方式 → 分派到 source，弹窗随任务离开 WAITING_USER 消失
        model.resolveConflictKeepBoth("u1")
        assertEquals(listOf("u1" to ConflictPolicy.KEEP_BOTH), source.conflicts)
        source.rows.value = listOf(task("u1", TransferState.COMPLETED, direction = TransferDirection.UPLOAD))
        assertNull(model.uiState.value.conflictTask)

        model.resolveConflictOverwrite("u1")
        assertEquals(ConflictPolicy.OVERWRITE, source.conflicts.last().second)
    }

    // ---- 取消二次确认 ----

    @Test
    fun cancelRequiresExplicitConfirmation() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(task("d1", TransferState.RUNNING))
        val model = viewModel(source)

        model.requestCancel("d1")
        assertEquals("d1", model.uiState.value.cancelTaskId)
        assertEquals("确认前不得触发取消", 0, source.canceled)

        model.dismissCancel()
        assertNull(model.uiState.value.cancelTaskId)
        assertEquals(0, source.canceled)

        model.requestCancel("d1")
        model.confirmCancel()
        assertNull(model.uiState.value.cancelTaskId)
        assertEquals(1, source.canceled)

        // 无待确认任务时 confirm 是安全 no-op
        model.confirmCancel()
        assertEquals(1, source.canceled)
    }

    // ---- 后台收尾去重 ----

    @Test
    fun stopBackgroundFiresOncePerDeparture() {
        val stopped = mutableListOf<String>()
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(
            task("d1", TransferState.RUNNING),
            task("d2", TransferState.RUNNING),
        )
        val model = viewModel(source, stopBackground = { stopped += it })
        assertEquals(0, stopped.size)

        source.rows.value = listOf(
            task("d1", TransferState.PAUSED),
            task("d2", TransferState.RUNNING),
        )
        assertEquals(listOf("d1"), stopped)

        // 停留在非活跃的后续帧不再重复调用（同一"离开"只 stop 一次）
        source.rows.value = listOf(
            task("d1", TransferState.PAUSED).copy(updateTime = 2),
            task("d2", TransferState.RUNNING).copy(updateTime = 2),
        )
        assertEquals(listOf("d1"), stopped)

        // 重新激活后的再一次离开是新的跳变沿，需要再次收尾（否则后台 job 随暂停/完成循环泄漏）
        source.rows.value = listOf(
            task("d1", TransferState.RUNNING),
            task("d2", TransferState.RUNNING).copy(updateTime = 3),
        )
        source.rows.value = listOf(
            task("d1", TransferState.FAILED),
            task("d2", TransferState.RUNNING).copy(updateTime = 4),
        )
        assertEquals(listOf("d1", "d1"), stopped)
    }

    // ---- 动作透传 ----

    @Test
    fun pauseResumeAndClearFinishedDelegateWithRowAccount() {
        val source = FakeTransferTasksSource()
        source.rows.value = listOf(task("d1", TransferState.PAUSED))
        val model = viewModel(source)

        model.pause("d1")
        model.resume("d1")
        // 行不存在的任务被安全跳过
        model.resume("missing")

        assertEquals(1, source.paused)
        assertEquals(1, source.resumed)

        model.clearFinished()
        assertEquals(1, source.cleared)

        // 没有任何任务行时 clearFinished 安全跳过（取不到 accountId）
        source.rows.value = emptyList()
        model.clearFinished()
        assertEquals(1, source.cleared)
    }

    private companion object {
        fun task(
            taskId: String,
            state: TransferState,
            direction: TransferDirection = TransferDirection.DOWNLOAD,
            downloaded: Long = 0L,
            error: String? = null,
        ) = TransferTaskEntity(
            accountId = "acc-1",
            taskId = taskId,
            fileName = "$taskId.bin",
            direction = direction,
            state = state,
            size = 10_000_000,
            downloadedBytes = downloaded,
            error = error,
        )
    }
}
