package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.data.settings.AppLanguage
import io.github.bileizhen.pan123x.data.settings.AppSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdvancedSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as PanXApplication).container
    private fun scroll(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun openSettings() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("open_settings").performClick()
        compose.mainClock.advanceTimeBy(600)
    }
    private inline fun preserveSettings(block: () -> Unit) {
        val original = container.settings.state.value
        try { block() } finally { runBlocking { container.settings.edit { original } } }
    }

    @Test fun transferControlsPersistAndDialogsValidateBounds() = preserveSettings {
        openSettings()
        scroll("setting_upload_threads").performClick()
        compose.onNodeWithText("2 个分片").performClick()
        scroll("setting_max_downloads").performClick()
        compose.onNodeWithTag("setting_number_input").performTextReplacement("0")
        compose.onNodeWithTag("setting_number_save").assertIsNotEnabled()
        compose.onNodeWithTag("setting_number_input").performTextReplacement("7")
        compose.onNodeWithTag("setting_number_save").performClick()
        scroll("setting_download_speed").performClick()
        compose.onNodeWithTag("setting_number_input").performTextReplacement("256")
        compose.onNodeWithTag("setting_number_save").performClick()
        scroll("setting_multi_thread").performClick()
        compose.waitUntil(5_000) { container.settings.state.value.let { it.uploadThreads == 2 && it.maxConcurrentDownloads == 7 && it.downloadSpeedLimit == 256L * 1024 && !it.multiThreadDownload } }
        compose.activityRule.scenario.recreate(); compose.mainClock.advanceTimeBy(600)
        scroll("setting_max_downloads")
        compose.onNodeWithText("7 个任务").assertIsDisplayed()
        capture("transfer-controls")
        scroll("setting_ask_location").performClick()
        compose.waitUntil(5_000) { container.settings.state.value.askDownloadLocation }
        scroll("setting_backoff_retry").performClick()
        compose.waitUntil(5_000) { !container.settings.state.value.errorBackoffRetry }
        capture("network-controls")
    }

    @Test fun proxyPageMasksPasswordValidatesPortAndPersistsEncryptedConfiguration() {
        val original = container.proxySettings.state.value
        try {
            openSettings()
            scroll("setting_proxy_config").performClick()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("proxy_mode_manual").performClick()
            compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_host"))
            compose.onNodeWithTag("proxy_host").performTextReplacement("127.0.0.1")
            compose.onNodeWithTag("proxy_port").performTextReplacement("0")
            compose.onNodeWithTag("proxy_save").assertIsNotEnabled()
            compose.onNodeWithTag("proxy_port").performTextReplacement("8080")
            compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_username"))
            compose.onNodeWithTag("proxy_username").performTextReplacement("test-user")
            compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_password"))
            compose.onNodeWithTag("proxy_password").performTextReplacement("test-secret")
            compose.runOnUiThread {
                compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
                compose.activity.currentFocus?.clearFocus()
            }
            compose.onNodeWithTag("proxy_password").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
            capture("proxy-editor")
            compose.onNodeWithTag("proxy_save").performClick()
            compose.waitUntil(5_000) { container.proxySettings.state.value.enabled && container.proxySettings.state.value.port == 8080 }
            assertEquals("test-secret", container.proxySettings.state.value.password)
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("navigate_back").performClick(); compose.mainClock.advanceTimeBy(600)
            compose.activityRule.scenario.recreate(); compose.mainClock.advanceTimeBy(600)
            scroll("setting_proxy_config").performClick(); compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("proxy_mode_manual").assertIsSelected()
            compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_host"))
            compose.onNodeWithTag("proxy_host").assertTextContains("127.0.0.1")
        } finally { runBlocking { container.proxySettings.save(original) } }
    }

    @Test fun englishSwitchPreservesNavigationAndDoesNotChangeData() = preserveSettings {
        openSettings()
        scroll("setting_language").performClick()
        compose.onNodeWithText("English").performClick()
        compose.waitUntil(5_000) { container.settings.state.value.language == AppLanguage.ENGLISH }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        scroll("setting_max_uploads")
        compose.onNodeWithText("Concurrent uploads").assertIsDisplayed()
        capture("settings-english")
        compose.onNodeWithTag("navigate_back").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("account_check_update").assertTextContains("Check for updates")
        compose.onNodeWithText("About").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("about_check_update").assertDoesNotExist()
    }

    @Test fun fourProxyModesAndBypassSurviveSaveAndRecreation() {
        val original = container.proxySettings.state.value
        val originalSettings = container.settings.state.value
        try {
            openSettings(); scroll("setting_proxy_config").performClick(); compose.mainClock.advanceTimeBy(600)
            val modes = io.github.bileizhen.pan123x.core.network.ProxyMode.entries
            for (mode in modes) compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_mode_${mode.name.lowercase()}"))
            for (mode in listOf(io.github.bileizhen.pan123x.core.network.ProxyMode.SYSTEM, io.github.bileizhen.pan123x.core.network.ProxyMode.AUTO, io.github.bileizhen.pan123x.core.network.ProxyMode.NONE)) {
                compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_mode_${mode.name.lowercase()}"))
                compose.onNodeWithTag("proxy_mode_${mode.name.lowercase()}").performClick()
                compose.onNodeWithTag("proxy_save").performClick()
                compose.waitUntil(5_000) { container.proxySettings.state.value.effectiveMode == mode }
            }
            compose.onNodeWithTag("proxy_mode_system").performClick()
            compose.onNodeWithTag("proxy_save").performClick()
            compose.waitUntil(5_000) { container.proxySettings.state.value.effectiveMode == io.github.bileizhen.pan123x.core.network.ProxyMode.SYSTEM }
            capture("proxy-modes")
            runBlocking { container.settings.edit { it.copy(themeMode = io.github.bileizhen.pan123x.data.settings.ThemeMode.DARK) } }
            compose.mainClock.advanceTimeBy(600)
            capture("proxy-modes-dark")
            runBlocking { container.settings.edit { originalSettings } }
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_bypass"))
            compose.onNodeWithTag("proxy_bypass").performTextReplacement("example.com, <local>")
            compose.runOnUiThread { androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            compose.onNodeWithTag("proxy_save").performClick()
            compose.waitUntil(5_000) { container.proxySettings.state.value.bypass == "example.com, <local>" }
            capture("proxy-bypass")
            compose.activityRule.scenario.recreate(); compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("proxy_mode_system").assertIsSelected()
            compose.onNodeWithTag("proxy_screen").performScrollToNode(hasTestTag("proxy_bypass"))
            compose.onNodeWithTag("proxy_bypass").assertTextContains("example.com, <local>")
        } finally { runBlocking { container.proxySettings.save(original); container.settings.edit { originalSettings } } }
    }

    @Test fun updateEntryIsAboveAboutAndOpensItsDialogFromMyPage() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToNode(hasTestTag("account_about"))
        compose.onNodeWithTag("account_screen").performTouchInput { swipeUp() }
        compose.onNodeWithTag("account_about").assertIsDisplayed()
        val update = compose.onNodeWithTag("account_check_update").fetchSemanticsNode().boundsInRoot
        val about = compose.onNodeWithTag("account_about").fetchSemanticsNode().boundsInRoot
        assertTrue("Check updates is immediately above About", update.bottom <= about.top)
        assertTrue("About stays above the floating navigation after scrolling",
            about.bottom <= compose.onNodeWithTag("tab_3").fetchSemanticsNode().boundsInRoot.top)
        capture("account-update-entry")
        compose.onNodeWithTag("account_check_update").performClick()
        compose.onNodeWithTag("update_message").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(isEnabled() and hasText("关闭")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("关闭").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("account_about").performClick(); compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("about_check_update").assertDoesNotExist()
    }

    @Test fun resetCacheRequiresConfirmationAndDoesNotRestoreRemovedPages() {
        openSettings()
        scroll("setting_reset_files").performClick()
        compose.onNodeWithTag("setting_reset_confirm").assertIsDisplayed()
        capture("cache-confirmation")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("setting_reset_confirm").assertDoesNotExist()
        compose.onNodeWithTag("settings_about").assertDoesNotExist()
        compose.onNodeWithTag("settings_logs").assertDoesNotExist()
        compose.onNodeWithTag("settings_diagnostics").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "advanced-settings").apply { mkdirs() }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
