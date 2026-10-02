package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToLog
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesFilesAndSwitchesEveryPrimaryPage() {
        assertVisibleWithDiagnostics("files_list")
        compose.onNodeWithTag("tab_1").performClick()
        assertVisibleWithDiagnostics("transfer-page")
        compose.onNodeWithTag("tab_2").performClick()
        assertVisibleWithDiagnostics("share-page")
        compose.onNodeWithTag("tab_3").performClick()
        assertVisibleWithDiagnostics("account_screen")
        compose.onNodeWithTag("tab_0").performClick()
        assertVisibleWithDiagnostics("files_list")
    }

    @Test
    fun opensSettingsAndPersistsThemeThroughActivityRecreation() {
        compose.onNodeWithTag("tab_3").performClick()
        // Scroll the entire applications card up: minimal row scroll can leave
        // its click center behind the overlay bar, which is outside LazyColumn.
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("open_settings").assertIsDisplayed().performClick()
        // Miuix NavDisplay's regular transition is 500ms. Advance its Compose clock
        // explicitly instead of nesting an Espresso-idle semantics query in waitUntil.
        compose.mainClock.advanceTimeBy(600)
        assertVisibleWithDiagnostics("settings_screen")
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_appearance"))
        compose.onNodeWithTag("settings_appearance").performClick()
        compose.mainClock.advanceTimeBy(600)
        assertVisibleWithDiagnostics("appearance_screen")
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            (compose.activity.application as PanXApplication).container.settings.state.value.themeMode == ThemeMode.DARK
        }
        compose.onNodeWithText("深色").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(600)
        assertVisibleWithDiagnostics("appearance_screen")
        compose.onNodeWithText("深色").performScrollTo().assertIsSelected()
        compose.onNodeWithText("跟随系统").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            (compose.activity.application as PanXApplication).container.settings.state.value.themeMode == ThemeMode.SYSTEM
        }
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("account_screen").assertIsDisplayed()
    }

    private fun assertVisibleWithDiagnostics(tag: String) {
        try {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        } catch (failure: Throwable) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "test-diagnostics")
            check(directory.isDirectory || directory.mkdirs())
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "$tag.png").outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                bitmap.recycle()
            }
            Log.e("PanXTestDiagnostics", "tag=$tag failure=${failure.javaClass.simpleName} clock=${compose.mainClock.currentTime}")
            if (failure is AssertionError) {
                val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
                Log.e("PanXTestDiagnostics", "bounds=${nodes.firstOrNull()?.boundsInRoot} root=${compose.onRoot().fetchSemanticsNode().boundsInRoot}")
                compose.onRoot(useUnmergedTree = true).printToLog("PanXTestDiagnostics")
            }
            throw failure
        }
    }
}
