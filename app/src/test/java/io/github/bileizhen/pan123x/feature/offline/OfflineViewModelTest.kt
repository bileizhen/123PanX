@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.offline

import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.network.OfflineResolvedFile
import io.github.bileizhen.pan123x.core.network.OfflineResolvedItem
import io.github.bileizhen.pan123x.data.offline.OfflineRepositoryApi
import io.github.bileizhen.pan123x.data.offline.OfflineState
import io.github.bileizhen.pan123x.data.offline.OfflineSubmitSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 构造解析结果样例（字段覆盖 OfflineResolvedItem 全量；DTO 为 data class，非默认字段须逐个给出）。
 * 顶层函数：FakeOfflineRepository 是嵌套类，无法访问外部类的实例成员。
 */
private fun file(id: Long, size: Long = 1_024L) = OfflineResolvedFile(fileId = id, name = "文件$id", size = size)

private fun item(
    resourceId: Long,
    ok: Boolean = true,
    files: List<OfflineResolvedFile> = emptyList(),
    type: Int = 0,
) = OfflineResolvedItem(
    url = "https://example.com/$resourceId",
    type = type,
    ok = ok,
    name = "资源$resourceId",
    size = files.sumOf { it.size },
    resourceId = resourceId,
    fileNums = files.size,
    files = files,
    errMessage = if (ok) "" else "链接不支持",
)

/**
 * OfflineViewModel 行为测试（纯 JVM，注入 [OfflineRepositoryApi] 手工假实现）。
 *
 * 覆盖：解析推进（Idle→Resolving→Resolved，默认全选；空输入本地校验）、busy 防重
 * （仓库在途 / 状态忙均忽略重复触发）、勾选切换与提交组装（部分勾选只传选中 fileId、
 * 全选 = 空列表"整个资源"、全不选跳过、失败资源不上送）、提交 busy、Done 汇总文案 +
 * 自动清空回 Idle（虚拟时间）、Error 文案保留输入、手动 reset。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineViewModelTest {

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

    /** OfflineRepository 的手工假实现：状态完全由测试驱动，不做任何网络动作。 */
    private class FakeOfflineRepository : OfflineRepositoryApi {
        val mutableState = MutableStateFlow<OfflineState>(OfflineState.Idle)
        override val state: StateFlow<OfflineState> = mutableState

        var resolveCalls = 0
            private set
        var submitCalls = 0
            private set
        var resetCalls = 0
            private set
        val submitted = mutableListOf<Map<Long, List<Long>>>()
        /** 非 null 时 resolve 挂起在门闩上，模拟请求在途。 */
        var resolveGate: CompletableDeferred<Unit>? = null
        /** 记录最近一次 resolve 收到的入参，供断言。 */
        var lastResolveInput: String? = null
            private set

        override suspend fun resolve(urlsText: String) {
            resolveCalls++
            lastResolveInput = urlsText
            mutableState.value = OfflineState.Resolving
            resolveGate?.await()
        }

        override suspend fun submit(selections: Map<Long, List<Long>>) {
            submitCalls++
            submitted += selections
            // B 的 Submitting 携带当前 items（UI 期间资源卡不闪没）。
            mutableState.value = OfflineState.Submitting(emptyList())
        }

        override fun reset() {
            resetCalls++
            mutableState.value = OfflineState.Idle
        }
    }

    private fun viewModel(
        repo: FakeOfflineRepository,
        resultClearMillis: Long = 60_000L,
    ): OfflineViewModel = OfflineViewModel(repo, resultClearMillis).also { owner.put("offline", it) }

    // ---- 解析推进 ----

    @Test
    fun blankInputShowsNoticeAndSkipsRepository() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)

        model.updateInput("   ")
        model.resolve()

        assertEquals(0, repo.resolveCalls)
        assertEquals("请输入至少一个链接", model.uiState.value.notice)
        assertFalse(model.uiState.value.busy)

        // 修正输入后提示清除。
        model.updateInput("https://example.com/a")
        assertNull(model.uiState.value.notice)
    }

    @Test
    fun resolveAdvancesToResolvedWithDefaultFullSelection() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)

        model.updateInput("https://example.com/a\nmagnet:?xt=1")
        model.resolve()

        assertEquals(1, repo.resolveCalls)
        assertEquals("https://example.com/a\nmagnet:?xt=1", repo.lastResolveInput)
        assertTrue(model.uiState.value.resolving)
        assertTrue(model.uiState.value.busy)

        repo.mutableState.value = OfflineState.Resolved(
            listOf(item(1, files = listOf(file(11), file(12)))),
        )
        val state = model.uiState.value
        assertEquals(1, state.items.size)
        assertEquals(setOf(11L, 12L), state.selection[1L])
        assertTrue("解析成功且默认全选后可提交", state.canSubmit)
        assertFalse(state.resolving)

        // Resolved 后再次解析：清旧卡，回到 Resolving。
        model.resolve()
        assertEquals(2, repo.resolveCalls)
        assertEquals(0, model.uiState.value.items.size)
    }

    @Test
    fun busyGuardsResolveAndSubmit() {
        val repo = FakeOfflineRepository()
        repo.resolveGate = CompletableDeferred()
        val model = viewModel(repo)

        model.updateInput("url")
        model.resolve()
        assertEquals(1, repo.resolveCalls)
        model.resolve()
        assertEquals("解析在途时重复触发被忽略", 1, repo.resolveCalls)

        repo.resolveGate?.complete(Unit)
        repo.mutableState.value = OfflineState.Resolved(listOf(item(1, files = listOf(file(11)))))
        model.submit()
        assertEquals(1, repo.submitCalls)
        assertTrue(model.uiState.value.submitting)
        model.submit()
        assertEquals("提交中重复触发被忽略", 1, repo.submitCalls)
        model.resolve()
        assertEquals("提交中解析也被忽略", 1, repo.resolveCalls)
    }

    // ---- 勾选与提交组装 ----

    @Test
    fun partialSelectionSubmitsOnlyCheckedFileIds() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)
        repo.mutableState.value = OfflineState.Resolved(
            listOf(item(1, files = listOf(file(11), file(12), file(13)))),
        )
        assertEquals(setOf(11L, 12L, 13L), model.uiState.value.selection[1L])

        // 取消勾选 12 再勾回：切换对称。
        model.toggleFile(1L, 12L)
        assertEquals(setOf(11L, 13L), model.uiState.value.selection[1L])
        model.toggleFile(1L, 12L)
        assertEquals(setOf(11L, 12L, 13L), model.uiState.value.selection[1L])

        // 部分勾选 → 只传选中 fileId（保持清单顺序）。
        model.toggleFile(1L, 12L)
        model.submit()
        assertEquals(listOf(mapOf(1L to listOf(11L, 13L))), repo.submitted)
    }

    @Test
    fun fullSelectionSubmitsWholeResourceAndSkipsUncheckedOrFailed() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)
        repo.mutableState.value = OfflineState.Resolved(
            listOf(
                item(1, files = listOf(file(11), file(12))),
                item(2),             // 无文件清单 → 整个资源
                item(3, ok = false), // 解析失败资源不上送
            ),
        )

        model.submit()
        assertEquals(listOf(mapOf(1L to emptyList<Long>(), 2L to emptyList<Long>())), repo.submitted)

        // 全部取消勾选 → 该资源跳过（空列表语义是"整个资源"，不得违背用户意图）。
        repo.mutableState.value = OfflineState.Idle
        model.setAllFiles(1L, false)
        model.submit()
        assertEquals(mapOf(2L to emptyList<Long>()), repo.submitted[1])
        assertEquals("仅剩无清单资源仍可提交", 2, repo.submitCalls)
    }

    @Test
    fun canSubmitRequiresAtLeastOneValidSelection() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)
        repo.mutableState.value = OfflineState.Resolved(
            listOf(item(1, files = listOf(file(11)))),
        )
        assertTrue(model.uiState.value.canSubmit)

        model.setAllFiles(1L, false)
        assertFalse(model.uiState.value.canSubmit)
        model.submit()
        assertEquals(0, repo.submitCalls)
        assertEquals("没有可提交的资源，请先解析并至少保留一个有效资源", model.uiState.value.notice)
    }

    // ---- 完成 / 错误 / 复位 ----

    @Test
    fun doneShowsSummaryThenAutoResetsToIdle() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo, resultClearMillis = 1_000L)

        repo.mutableState.value = OfflineState.Resolved(listOf(item(1)))
        model.submit()
        assertEquals(1, repo.submitCalls)

        repo.mutableState.value = OfflineState.Done(
            OfflineSubmitSummary(total = 2, succeeded = 1, failed = 1, firstFailure = "离线链接无效"),
            items = emptyList(),
        )
        assertEquals("成功 1 个任务，失败 1 个：离线链接无效", model.uiState.value.resultMessage)

        mainDispatcher.scheduler.advanceTimeBy(1_000L)
        mainDispatcher.scheduler.runCurrent()

        assertEquals("Done 后自动复位并通知仓库", 1, repo.resetCalls)
        val state = model.uiState.value
        assertEquals("", state.input)
        assertEquals(0, state.items.size)
        assertNull(state.resultMessage)
        assertFalse(state.busy)
    }

    @Test
    fun doneSummaryCoversAllSuccessAndAllFailure() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)

        repo.mutableState.value = OfflineState.Done(
            OfflineSubmitSummary(total = 2, succeeded = 2, failed = 0, firstFailure = null),
            items = emptyList(),
        )
        assertEquals("成功提交 2 个任务", model.uiState.value.resultMessage)

        repo.mutableState.value = OfflineState.Done(
            OfflineSubmitSummary(total = 1, succeeded = 0, failed = 1, firstFailure = "资源失效"),
            items = emptyList(),
        )
        assertEquals("提交失败：资源失效", model.uiState.value.resultMessage)
    }

    @Test
    fun errorStateShowsMessageAndKeepsInput() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)

        model.updateInput("https://example.com/a")
        repo.mutableState.value = OfflineState.Error("离线链接解析失败，请检查链接格式")

        val state = model.uiState.value
        assertEquals("离线链接解析失败，请检查链接格式", state.error)
        assertEquals("输入保留，便于修改后重试", "https://example.com/a", state.input)
        assertFalse(state.canSubmit)
    }

    @Test
    fun manualResetClearsStateAndNotifiesRepository() {
        val repo = FakeOfflineRepository()
        val model = viewModel(repo)

        model.updateInput("url")
        repo.mutableState.value = OfflineState.Resolved(listOf(item(1, files = listOf(file(11)))))
        model.toggleFile(1L, 11L)

        model.reset()

        assertEquals(1, repo.resetCalls)
        val state = model.uiState.value
        assertEquals("", state.input)
        assertEquals(0, state.items.size)
        assertNull(state.selection[1L])
        assertFalse(state.busy)
    }
}
