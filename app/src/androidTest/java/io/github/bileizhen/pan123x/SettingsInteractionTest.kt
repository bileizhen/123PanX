package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openSettings() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("open_settings").performClick()
        compose.mainClock.advanceTimeBy(600)
    }

    @Test fun dropdownChoicesPersistAndAppearanceSwitchesFollowDependencies() {
        openSettings()
        compose.onNodeWithTag("setting_connections").performClick()
        compose.onNodeWithText("8 个连接").performClick()
        compose.waitUntil(5_000) { (compose.activity.application as PanXApplication).container.settings.state.value.downloadConnections == 8 }
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("8 个连接").assertIsDisplayed()
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_appearance"))
        compose.onNodeWithTag("settings_appearance").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("setting_blur").performScrollTo().performClick()
        compose.waitUntil(5_000) { !(compose.activity.application as PanXApplication).container.settings.state.value.blur }
        compose.onNodeWithTag("setting_liquid").assertIsNotEnabled()
        compose.onNodeWithTag("setting_liquid").performClick()
        compose.runOnIdle { check((compose.activity.application as PanXApplication).container.settings.state.value.liquidGlass) }
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        // Reset the persistent test preferences for other activity-based tests.
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_appearance"))
        compose.onNodeWithTag("settings_appearance").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("setting_blur").performScrollTo().performClick()
        compose.waitUntil(5_000) { (compose.activity.application as PanXApplication).container.settings.state.value.blur }
    }

    @Test fun scaleAccessibilityAdjustmentPersistsAcrossRecreation() {
        openSettings()
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_appearance"))
        compose.onNodeWithTag("settings_appearance").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("setting_scale", useUnmergedTree = true).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.9f) }
        compose.waitUntil(5_000) { (compose.activity.application as PanXApplication).container.settings.state.value.uiScale == .9f }
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("appearance_screen").assertIsDisplayed()
        compose.onNodeWithTag("setting_scale", useUnmergedTree = true).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.waitUntil(5_000) { (compose.activity.application as PanXApplication).container.settings.state.value.uiScale == 1f }
    }

    @Test fun capturesSettingsAppearanceAndAbout() {
        openSettings()
        capture("settings")
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_appearance"))
        compose.onNodeWithTag("settings_appearance").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("appearance_screen").assertIsDisplayed()
        capture("appearance")
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("settings_about").assertDoesNotExist()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("关于").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("about_screen").assertIsDisplayed()
        capture("about")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "account-settings")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
