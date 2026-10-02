package io.github.bileizhen.pan123x

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.data.settings.*
import io.github.bileizhen.pan123x.feature.about.*
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import java.io.File
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(AndroidJUnit4::class)
class UpdateFlowTest {
    @get:Rule val compose = createComposeRule()
    private val release = AppRelease("1.0.0", "新增更新组件\n支持 GitHub 与镜像下载\n下载后请求系统安装", "https://github.com/bileizhen/123PanX/releases/tag/v1.0.0", "https://github.com/bileizhen/123PanX/releases/download/v1.0.0/123PanX-1.0.0.apk", 100, "a".repeat(64))
    private val installer = object : UpdateInstall {
        override fun canInstall() = false
        override suspend fun request(file: File, release: AppRelease) = UpdateInstallResult.PERMISSION_REQUIRED
    }
    private fun show(vm: UpdateViewModel, theme: ThemeMode = ThemeMode.LIGHT) {
        compose.setContent { PanXTheme(AppSettings(themeMode = theme, monet = false)) { Scaffold { Box(Modifier.fillMaxSize()) { UpdateDialog(vm) } } } }
        compose.runOnIdle { vm.checkAtStartup(true) }
        compose.waitUntil(5_000) { vm.state.value.visible && !vm.state.value.checking }
        compose.mainClock.advanceTimeBy(600)
    }
    @Test fun mirrorSelectionProgressAndCancellationWorkOnDevice() {
        val store = ViewModelStore()
        lateinit var selected: UpdateSource
        val vm = UpdateViewModel(UpdateChecker { UpdateResult.Available(release) }, UpdateDownload { _, source, progress -> selected = source; progress(50, 100); awaitCancellation() }, installer)
        store.put("update", vm)
        try {
            show(vm)
            compose.onNodeWithTag("update_source").performClick()
            compose.onNodeWithText("gh.dpik.top 镜像").performClick()
            compose.onNodeWithTag("update_download").performClick()
            compose.onNodeWithTag("update_progress").assertIsDisplayed()
            compose.onNodeWithTag("update_download").assertIsNotEnabled()
            assertEquals(UpdateSource.MIRROR, selected)
            capture("mirror-progress")
            compose.onNodeWithText("取消下载").performClick()
            compose.waitUntil(5_000) { vm.state.value.download == UpdateDownloadState.Idle }
            compose.onNodeWithTag("update_download").assertIsEnabled()
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun downloadAutomaticallyRequestsInstallAndExplainsPermission() {
        val store = ViewModelStore()
        val vm = UpdateViewModel(UpdateChecker { UpdateResult.Available(release) }, UpdateDownload { _, _, _ -> File("verified.apk") }, installer)
        store.put("update", vm)
        try {
            show(vm, ThemeMode.DARK)
            compose.onNodeWithTag("update_download").performClick()
            compose.waitUntil(5_000) { vm.state.value.permissionRequired }
            compose.onNodeWithTag("update_install_message").assertIsDisplayed()
            compose.onNodeWithText("请求安装").assertIsDisplayed()
            capture("install-permission-dark")
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun systemInstallerReceivesReadableContentUriAndOnlyReadPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "updates/provider-test.apk")
        file.parentFile!!.mkdirs(); file.writeText("provider fixture")
        try {
            val intent = AndroidUpdateInstaller.installIntent(context, file)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals("provider fixture", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
        } finally { file.delete() }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "update-flow").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
