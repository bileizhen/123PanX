package io.github.bileizhen.pan123x

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.core.logging.LogSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ShareClipboardAndLogExportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @org.junit.After fun clearFixtureClipboard() {
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("123PanX 分享测试", ""))
        }
    }

    private fun waitForExportDialog() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("export_logs_save").fetchSemanticsNodes().isNotEmpty() && compose.onNodeWithTag("export_logs_save").isDisplayed() }
    }

    @Test fun saveActionWritesZipAndShareActionGrantsOnlyReadAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        val destination = File(context.cacheDir, "bugreports/save-test.zip").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf()) }
        val destinationUri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
        val chooser = java.util.concurrent.atomic.AtomicReference<android.content.Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                return when (intent.action) {
                    android.content.Intent.ACTION_CREATE_DOCUMENT -> android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(destinationUri))
                    android.content.Intent.ACTION_CHOOSER -> {
                        chooser.set(intent)
                        android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                    }
                    else -> null
                }
            }
        }
        instrumentation.addMonitor(monitor)
        var sharedFile: File? = null
        try {
            compose.onNodeWithTag("tab_3").performClick()
            compose.onNodeWithTag("account_screen").performScrollToIndex(5)
            compose.onNodeWithTag("account_export_logs").performClick()
            waitForExportDialog()
            compose.onNodeWithTag("export_logs_save").performClick()
            compose.waitUntil(10_000) { destination.length() > 0 && compose.onAllNodesWithText("正在生成日志").fetchSemanticsNodes().isEmpty() }
            ZipFile(destination).use { assertNotNull(it.getEntry("app.log")) }
            compose.onNodeWithTag("account_export_logs").performClick()
            waitForExportDialog()
            compose.onNodeWithTag("export_logs_share").performClick()
            compose.waitUntil(10_000) { chooser.get() != null }
            val intent = androidx.core.content.IntentCompat.getParcelableExtra(chooser.get(), android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)!!
            assertEquals(android.content.Intent.ACTION_SEND, intent.action)
            assertEquals("application/zip", intent.type)
            assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)!!
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(uri.path.orEmpty().startsWith("/bugreports/"))
            sharedFile = File(destination.parentFile, uri.lastPathSegment!!)
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
        } finally { instrumentation.removeMonitor(monitor); destination.delete(); sharedFile?.delete() }
    }

    @Test fun clipboardRecognizesPasswordAndDoesNotPromptAgainAfterIgnoreOrOwnCopy() {
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("测试分享链接", "https://www.123pan.com/s/fixture-key 提取码：Ab12"))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("clipboard_share_password").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clipboard_share_password").assertTextContains("Ab12", substring = true)
        capture("clipboard")
        compose.onNodeWithTag("clipboard_share_ignore").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {
            val manager = compose.activity.getSystemService(ClipboardManager::class.java)
            manager.setPrimaryClip(ClipData.newPlainText("测试分享链接", "https://www.123pan.com/s/fixture-key 提取码：Ab12"))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("clipboard_share_open").assertDoesNotExist()
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("123PanX 分享链接", "https://www.123pan.cn/s/own-fixture"))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("clipboard_share_open").assertDoesNotExist()
    }

    @Test fun reportedMobileSharePromptsAndOpensInAppViewerWithPassword() {
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("测试移动端分享", reportedMobileShare))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("clipboard_share_password").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clipboard_share_password").assertTextContains("kkaE", substring = true)
        compose.onNodeWithTag("clipboard_share_url").assertTextContains("1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh?pwd=kkaE", substring = true)
        capture("mobile-share")
        compose.onNodeWithTag("clipboard_share_open").performClick()
        // 应用内查看页：面包屑可先渲染；测试环境网络仅允许本地服务器，最终给出整页错误与重试。
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shared-files-root").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-files-root").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("shared-files-error-page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-files-retry").assertIsDisplayed()
        capture("mobile-share-in-app")
        // 返回键离开分享查看页；提示框已消费，不再出现。
        compose.onNodeWithTag("navigate_back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shared-files-root").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("clipboard_share_open").assertDoesNotExist()
    }

    @Test fun mobileShareCopiedWhileAwayIsRecognizedOnResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            manager.setPrimaryClip(ClipData.newPlainText("测试返回应用识别", reportedMobileShare.replace("O0mFTd-uHjIh", "resume-fixture")))
        }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("clipboard_share_password").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("clipboard_share_password").assertTextContains("kkaE", substring = true)
        compose.onNodeWithTag("clipboard_share_url").assertTextContains("/123pan/resume-fixture", substring = true)
        compose.onNodeWithTag("clipboard_share_ignore").performClick()
    }

    private val reportedMobileShare = "https://1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh?notoken=1&pwd=kkaE&pendingAction=TRANSFER_SAVE_ALL"

    @Test fun exportDialogAndReportUseNarrowFileProviderUriAndRedactedZip() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("account_export_logs").performClick()
        compose.mainClock.advanceTimeBy(700)
        capture("logs-export-before")
        waitForExportDialog()
        compose.onNodeWithTag("export_logs_save").assertIsDisplayed()
        compose.onNodeWithTag("export_logs_share").assertIsDisplayed()
        capture("logs-export")
        compose.onNodeWithTag("export_logs_cancel").performClick()
        val container = (compose.activity.application as PanXApplication).container
        container.logger.e(LogSource.AUTH, "Authorization: Bearer secret-export-token")
        val report = runBlocking { container.diagnosticReport.create() }
        try {
            val uri = FileProvider.getUriForFile(compose.activity, "${compose.activity.packageName}.files", report)
            assertEquals("content", uri.scheme)
            assertTrue(uri.path.orEmpty().startsWith("/bugreports/"))
            compose.activity.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            ZipFile(report).use { zip ->
                val app = zip.getInputStream(zip.getEntry("app.log")).bufferedReader().readText()
                assertFalse(app.contains("secret-export-token"))
                assertTrue(app.contains("[已隐藏]"))
                assertNotNull(zip.getEntry("logcat.txt"))
                val summary = zip.getInputStream(zip.getEntry("summary.json")).bufferedReader().readText()
                assertFalse(summary.contains("accountId"))
            }
        } finally { report.delete() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "share-export").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
