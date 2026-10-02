// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.feature.diagnostics

import io.github.bileizhen.pan123x.data.diagnostics.DiagnosticsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * DiagnosticsViewModel 状态机测试（纯 JVM）：注入 snapshot/clear suspend lambda
 * 替身（PreviewViewModel 的 resolve 注入先例），Main 置 UnconfinedTestDispatcher——init 的
 * 首次加载与后续动作在构造/调用点同步走完，便于直接断言 StateFlow 终值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private fun snapshot(cache: Long = 100, preview: Long = 40) = DiagnosticsSnapshot(
        appVersion = "test-9.9",
        sdkInt = 30,
        deviceModel = "test device",
        cacheBytes = cache,
        previewCacheBytes = preview,
        taskCounts = mapOf("RUNNING" to 1),
        logTail = listOf("12:00:00 INFO [APP] ok"),
    )

    @Test
    fun loadsSnapshotOnceOnStart() = runTest {
        val expected = snapshot()
        var loads = 0
        val viewModel = DiagnosticsViewModel(snapshotProvider = { loads++; expected }, clearPreview = { 0L })

        // 构造内 init 即加载：无需手动 refresh，首帧就是内容态。
        assertEquals(1, loads)
        assertEquals(expected, viewModel.uiState.value.snapshot)
        assertEquals(false, viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun refreshReplacesSnapshot() = runTest {
        val first = snapshot()
        val second = snapshot(cache = 200, preview = 0)
        var current = first
        val viewModel = DiagnosticsViewModel(snapshotProvider = { current }, clearPreview = { 0L })

        current = second
        viewModel.refresh()

        assertEquals(second, viewModel.uiState.value.snapshot)
        assertEquals(false, viewModel.uiState.value.loading)
    }

    @Test
    fun clearFreesCacheResetsFieldAndShowsOneShotMessage() = runTest {
        var current = snapshot(cache = 8_192, preview = 4_096)
        val viewModel = DiagnosticsViewModel(
            snapshotProvider = { current },
            clearPreview = {
                // 模拟仓库行为：清掉预览缓存后快照里对应字段归零；释放字节数由仓库返回。
                val freed = current.previewCacheBytes
                current = current.copy(cacheBytes = current.cacheBytes - freed, previewCacheBytes = 0)
                freed
            },
        )

        viewModel.clearPreviewCache()

        assertEquals(0L, viewModel.uiState.value.snapshot?.previewCacheBytes)
        assertEquals(4_096L, viewModel.uiState.value.snapshot?.cacheBytes)
        assertEquals(false, viewModel.uiState.value.clearing)
        assertEquals("已释放 4.0 KB", viewModel.uiState.value.message)

        // 提示是一次性的：消费后归 null，且不再自动重现。
        viewModel.consumeMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun refreshFailureKeepsOldSnapshotAndShowsOneShotError() = runTest {
        var fail = false
        val viewModel = DiagnosticsViewModel(
            snapshotProvider = { if (fail) throw RuntimeException("boom") else snapshot() },
            clearPreview = { 0L },
        )
        val oldSnapshot = viewModel.uiState.value.snapshot
        fail = true

        viewModel.refresh()

        assertEquals("诊断信息加载失败，请稍后重试", viewModel.uiState.value.message)
        assertEquals(false, viewModel.uiState.value.loading)
        // 失败保留旧快照，不把页面清成空态（网络失败保留旧数据的同款取向）。
        assertNotNull(viewModel.uiState.value.snapshot)
        viewModel.consumeMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun clearFailureShowsOneShotErrorAndResetsClearing() = runTest {
        val viewModel = DiagnosticsViewModel(
            snapshotProvider = { snapshot() },
            clearPreview = { throw RuntimeException("disk gone") },
        )

        viewModel.clearPreviewCache()

        assertEquals("清除预览缓存失败，请稍后重试", viewModel.uiState.value.message)
        assertEquals(false, viewModel.uiState.value.clearing)
    }
}
