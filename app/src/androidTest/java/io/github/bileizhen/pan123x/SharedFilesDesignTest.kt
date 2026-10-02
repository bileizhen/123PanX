package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.core.network.*
import io.github.bileizhen.pan123x.core.share.SharedLink
import io.github.bileizhen.pan123x.data.settings.*
import io.github.bileizhen.pan123x.data.share.*
import io.github.bileizhen.pan123x.feature.share.*
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(AndroidJUnit4::class)
class SharedFilesDesignTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @org.junit.After fun cleanup() { compose.runOnIdle { store.clear() } }
    private val time = Instant.parse("2026-09-06T11:09:23Z").toEpochMilli()
    private fun file(id: Long, name: String, folder: Boolean = false, status: Int = 2) = FileItemDto(
        id, 0, name, folder, 153279523, "e", "s", "", time, time, false, false, "", status)
    private class Actions(val files: List<FileItemDto>) : SharedFilesActions {
        var downloads = 0
        val parents = mutableListOf<Long>()
        override suspend fun info(key: String) = ApiResult.Success(SharedInfoDto("Root工具模块.zip", "133****6243", "", true,
            Instant.parse("2099-12-12T00:00:00Z").toEpochMilli(), Instant.parse("2026-09-06T11:11:03Z").toEpochMilli(), false, false))
        override suspend fun list(key: String, password: String, parentId: Long, page: Int, next: String): ApiResult<FileListDto> {
            parents += parentId
            return ApiResult.Success(FileListDto(if (parentId == 0L) files else emptyList(), files.size, "-1", files.size, true))
        }
        override suspend fun save(key: String, password: String, files: List<FileItemDto>, targetId: Long): String? = error("No real cloud writes in UI fixtures")
        override suspend fun download(key: String, password: String, files: List<FileItemDto>, tree: String?): SharedQueueResult {
            downloads += files.size
            return SharedQueueResult(files.size)
        }
    }
    private val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = error("Directory picker not requested by this fixture")
    }
    private fun show(actions: Actions, onQueued: () -> Unit = {}): SharedFilesViewModel {
        val vm = SharedFilesViewModel(SharedLink("https://www.123pan.cn/s/key", "kkaE"), actions)
        store.put("share", vm)
        compose.setContent {
            PanXTheme(AppSettings(themeMode = ThemeMode.LIGHT, monet = false)) {
                Scaffold { Box(Modifier.fillMaxSize()) { SharedFilesScreen(vm, factory, {}, onDownloadsQueued = onQueued) } }
            }
        }
        compose.waitUntil(5_000) { !vm.state.value.loading && !vm.state.value.infoLoading }
        compose.mainClock.advanceTimeBy(600)
        return vm
    }
    @Test fun metadataDetailsAndDownloadWorkWithoutBrowserAndButtonsRemainReachable() {
        val actions = Actions(listOf(file(1, "Root工具模块.zip")))
        var queued = 0
        val vm = show(actions) { queued++ }
        compose.onNodeWithTag("shared-owner").assertTextEquals("133****6243")
        compose.onNodeWithTag("shared-expiry").assertTextEquals("永久分享")
        compose.onNodeWithText("2026-09-06 19:09").assertIsDisplayed()
        compose.onNodeWithText("有效").assertIsDisplayed()
        compose.onNodeWithTag("shared-files-save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("shared-file_1").performClick()
        compose.onNodeWithTag("shared-file-detail-name").assertTextEquals("Root工具模块.zip")
        compose.onNodeWithText("状态：有效").assertIsDisplayed()
        compose.onNodeWithTag("shared-detail-close").performClick()
        compose.onNodeWithTag("shared-file-check_1").performClick()
        compose.onNodeWithTag("shared-files-save").assertIsEnabled()
        compose.onNodeWithTag("shared-files-download").assertIsDisplayed().assertIsEnabled()
        capture("share-list-light")
        compose.onNodeWithTag("shared-files-download").performClick()
        compose.waitUntil(5_000) { queued == 1 }
        compose.runOnIdle { vm.toggleLayout() }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(1, queued)
        assertEquals(1, actions.downloads)
    }
    @Test fun filteredSelectionSortAndGridRetainSelectionAndFooter() {
        val actions = Actions(listOf(file(1, "one.zip"), file(2, "two.zip"), file(3, "one-invalid.zip", status = 0)))
        val vm = show(actions)
        compose.onNodeWithTag("shared-files-search").performTextInput("one")
        compose.onNodeWithTag("shared-files-search").performImeAction()
        compose.onNodeWithTag("shared-files-select-all").performClick()
        compose.runOnIdle { assertEquals(setOf(1L), vm.state.value.selected) }
        compose.onNodeWithTag("shared-files-list").performScrollToNode(hasTestTag("shared-file_3"))
        compose.onNodeWithTag("shared-file-check_3").assertIsNotEnabled()
        compose.onNodeWithTag("shared-files-sort").performClick()
        compose.onNodeWithTag("shared-sort-SIZE").performClick()
        compose.onNodeWithTag("shared-files-layout").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("shared-files-save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("shared-files-download").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertTrue(vm.state.value.grid); assertEquals(setOf(1L), vm.state.value.selected) }
    }
    @Test fun folderTapDuringSelectionDoesNotNavigateAndLastFileNeverSitsUnderActions() {
        val actions = Actions(listOf(file(1, "相册", true)) + (2L..18L).map { file(it, "文件-$it.zip") })
        val vm = show(actions)
        compose.onNodeWithTag("shared-files-list").performScrollToNode(hasTestTag("shared-file_2"))
        compose.onNodeWithTag("shared-file_2").performTouchInput { longClick() }
        compose.onNodeWithTag("shared-files-list").performScrollToNode(hasTestTag("shared-file_1"))
        compose.onNodeWithTag("shared-file_1").performClick()
        compose.runOnIdle { assertEquals(setOf(1L, 2L), vm.state.value.selected); assertEquals(listOf(0L), actions.parents) }
        compose.onNodeWithTag("shared-files-list").performScrollToNode(hasTestTag("shared-file_18"))
        compose.onNodeWithTag("shared-file_18").assertIsDisplayed()
        compose.onNodeWithTag("shared-files-download").assertIsDisplayed()
    }
    @Test fun longNamesAndGridRenderInDarkTheme() {
        val actions = Actions(listOf(file(1, "很长的文件名-项目备份-完整资料-2026-09-06-最终修订版.zip"), file(2, "另一个文件夹-重要资料", true)))
        val vm = SharedFilesViewModel(SharedLink("https://www.123pan.cn/s/key", "kkaE"), actions)
        store.put("share", vm)
        compose.setContent {
            PanXTheme(AppSettings(themeMode = ThemeMode.DARK, monet = false)) {
                Scaffold { Box(Modifier.fillMaxSize()) { SharedFilesScreen(vm, factory, {}) } }
            }
        }
        compose.waitUntil(5_000) { !vm.state.value.loading }
        compose.onNodeWithTag("shared-files-layout").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("shared-files-select-all").performClick()
        compose.onNodeWithTag("shared-files-save").assertIsDisplayed().assertIsEnabled()
        capture("share-grid-dark")
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "share-design").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
