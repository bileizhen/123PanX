@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.offline

import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidFile
import io.github.bileizhen.pan123x.data.transfer.RapidProgress
import io.github.bileizhen.pan123x.data.transfer.RapidImportApi
import io.github.bileizhen.pan123x.data.transfer.RapidReport
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

/** 构造秒传文件样例（RapidFile 为 data class，字段全量给出）。 */
private fun rapidFile(path: String, size: Long = 100L) = RapidFile(path = path, etag = "etag-$path", size = size)

/**
 * RapidImportViewModel 行为测试（纯 JVM，注入 [RapidImportApi] 手工假实现与
 * 替身解析函数——不依赖真实 RapidCodec，B 落库后仅需确认形态）。
 *
 * 覆盖：parse 的 IAE 文案透出与输入修正清除、空解析结果提示、解析成功构建预览（条数 / 总大小）、
 * 导入经仓库执行（入参 / 目标目录 / 取消回调透传）、进行中进度透传与结束隐藏、
 * 取消置位 + 协作回调返回 true + 部分汇总、导入中防重复、reset 全清。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RapidImportViewModelTest {

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

    private class FakeRapidImport : RapidImportApi {
        val mutableProgress = MutableStateFlow<RapidProgress?>(null)
        override val progress: StateFlow<RapidProgress?> = mutableProgress

        class ImportCall(val files: List<RapidFile>, val parentDirId: Long, val cancel: () -> Boolean)

        val imports = mutableListOf<ImportCall>()
        /** 非 null 时 import 挂起在门闩上，模拟导入在途。 */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun import(files: List<RapidFile>, parentDirId: Long, cancel: () -> Boolean): RapidReport {
            imports += ImportCall(files, parentDirId, cancel)
            if (files.isNotEmpty()) {
                // 在途即透出 0/N 首帧，验证进度条可见性。
                mutableProgress.value = RapidProgress(0, files.size, files.first().path)
            }
            gate?.await()
            val success = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()
            for (f in files) {
                if (cancel()) break
                mutableProgress.value = RapidProgress(success.size + failed.size + 1, files.size, f.path)
                success += f.path
            }
            return RapidReport(success, failed)
        }
    }

    private fun viewModel(
        repo: FakeRapidImport,
        parse: (String) -> List<RapidFile>,
    ): RapidImportViewModel = RapidImportViewModel(repo, parseText = parse).also { owner.put("rapid", it) }

    // ---- 解析预览 ----

    @Test
    fun parseErrorShowsCodecMessageAndClearsOnEdit() {
        val repo = FakeRapidImport()
        val model = viewModel(repo) { throw IllegalArgumentException("秒传内容不是有效的 JSON 或 123FLCPV2 链接") }

        model.updateInput("垃圾内容")
        model.parse()

        assertEquals("秒传内容不是有效的 JSON 或 123FLCPV2 链接", model.uiState.value.parseError)
        assertEquals(0, model.uiState.value.files.size)

        // 修正输入后错误清除。
        model.updateInput("{\"files\":[]}")
        assertNull(model.uiState.value.parseError)
    }

    @Test
    fun parseEmptyResultShowsHint() {
        val repo = FakeRapidImport()
        val model = viewModel(repo) { emptyList() }

        model.parse()

        assertEquals("未解析到有效文件，请检查秒传内容", model.uiState.value.parseError)
    }

    @Test
    fun parseSuccessBuildsPreviewTotals() {
        val repo = FakeRapidImport()
        val parsed = listOf(rapidFile("a.txt", 10), rapidFile("b/c.txt", 25))
        val model = viewModel(repo) { parsed }

        model.updateInput("123FLCPV2$...")
        model.parse()

        val state = model.uiState.value
        assertEquals(parsed, state.files)
        assertEquals(35L, state.totalSize)
        assertNull(state.parseError)
    }

    // ---- 导入 ----

    @Test
    fun importRunsThroughRepositoryWithTargetAndReport() {
        val repo = FakeRapidImport()
        val parsed = listOf(rapidFile("a.txt"), rapidFile("b.txt"))
        val model = viewModel(repo) { parsed }

        model.parse()
        model.setTargetDirectory(77L)
        model.startImport()

        val call = repo.imports.single()
        assertEquals(parsed, call.files)
        assertEquals(77L, call.parentDirId)
        assertFalse("未请求取消时协作回调为 false", call.cancel())
        assertEquals(RapidReport(listOf("a.txt", "b.txt"), emptyList()), model.uiState.value.report)
        assertFalse(model.uiState.value.importing)
        assertNull("导入结束后进度隐藏", model.uiState.value.progress)
    }

    @Test
    fun importProgressVisibleWhileRunning() {
        val repo = FakeRapidImport()
        repo.gate = CompletableDeferred()
        val parsed = listOf(rapidFile("a.txt"), rapidFile("b.txt"))
        val model = viewModel(repo) { parsed }

        model.parse()
        model.startImport()

        val state = model.uiState.value
        assertTrue(state.importing)
        assertEquals(RapidProgress(0, 2, "a.txt"), state.progress)

        repo.gate?.complete(Unit)
        assertFalse(model.uiState.value.importing)
    }

    @Test
    fun cancelSetsFlagAndImportReturnsPartialReport() {
        val repo = FakeRapidImport()
        repo.gate = CompletableDeferred()
        val parsed = listOf(rapidFile("a.txt"), rapidFile("b.txt"), rapidFile("c.txt"))
        val model = viewModel(repo) { parsed }

        model.parse()
        model.startImport()
        assertTrue(model.uiState.value.importing)

        model.requestCancel()
        assertTrue(model.uiState.value.cancelRequested)
        val call = repo.imports.single()
        assertTrue("取消置位后协作回调返回 true", call.cancel())

        // 取消已请求后按钮语义为"正在取消"，不可重复触发。
        model.requestCancel()

        repo.gate?.complete(Unit)
        // 门闩释放后首个文件前即命中取消 → 空部分汇总。
        assertEquals(RapidReport(emptyList(), emptyList()), model.uiState.value.report)
        assertFalse(model.uiState.value.importing)
        assertFalse(model.uiState.value.cancelRequested)
    }

    @Test
    fun importBusyIgnoresRepeatStarts() {
        val repo = FakeRapidImport()
        repo.gate = CompletableDeferred()
        val parsed = listOf(rapidFile("a.txt"))
        val model = viewModel(repo) { parsed }

        model.parse()
        model.startImport()
        model.startImport()

        assertEquals(1, repo.imports.size)
        repo.gate?.complete(Unit)
    }

    @Test
    fun startImportWithoutParsedFilesIsNoOp() {
        val repo = FakeRapidImport()
        val model = viewModel(repo) { emptyList() }

        model.startImport()

        assertEquals(0, repo.imports.size)
        assertFalse(model.uiState.value.importing)
    }

    // ---- 复位 ----

    @Test
    fun resetClearsEverything() {
        val repo = FakeRapidImport()
        val parsed = listOf(rapidFile("a.txt", 5))
        val model = viewModel(repo) { parsed }

        model.updateInput("内容")
        model.parse()
        model.setTargetDirectory(9L)
        model.startImport()
        assertTrue(model.uiState.value.report != null)

        model.reset()

        val state = model.uiState.value
        assertEquals("", state.input)
        assertEquals(0, state.files.size)
        assertEquals(0L, state.totalSize)
        assertNull(state.parseError)
        assertEquals(0L, state.parentDirId)
        assertNull(state.report)
        assertFalse(state.importing)
        assertFalse(state.cancelRequested)
    }
}
