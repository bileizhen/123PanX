package io.github.bileizhen.pan123x

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated application and system accessibility, without Espresso's hidden input APIs. */
@RunWith(AndroidJUnit4::class)
class ShareSubdomainClipboardTest {
    @Test fun shareSubdomainPromptsInForegroundAndOnResumeAndOpensInApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            val manager = activity!!.getSystemService(ClipboardManager::class.java)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!activity!!.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue(activity!!.hasWindowFocus())
            var original: ClipData? = null
            scenario.onActivity { original = manager.primaryClip }
            try {
                val url = "https://1234567890.share.123pan.cn/123pan/clipboard-fixture"
                scenario.onActivity {
                    manager.setPrimaryClip(ClipData.newPlainText("分享测试", "[$url]($url)"))
                }
                waitForText(url)
                clickText("忽略")
                SystemClock.sleep(500)
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync {
                    manager.setPrimaryClip(ClipData.newPlainText("返回应用测试", "$url-resume?pwd=Ab12"))
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitForText("$url-resume?pwd=Ab12")
                waitForText("提取码：Ab12")
                clickText("应用内打开")
                waitForText("分享文件")
            } finally {
                scenario.moveToState(Lifecycle.State.RESUMED)
                instrumentation.runOnMainSync {
                    manager.setPrimaryClip(original ?: ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private fun waitForText(text: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            findText(automation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected accessibility text did not appear: $text")
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), text)?.let { return it }
        }
        return null
    }

    private fun clickText(text: String) {
        var node: AccessibilityNodeInfo? = waitForText(text)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("Expected clickable control: $text")
    }
}
