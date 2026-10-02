package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No cloud calls: exercise the real Navigation 3 dispatcher and its cancel/complete paths. */
@RunWith(AndroidJUnit4::class)
class NavigationInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun account() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("account_screen").assertIsDisplayed()
    }

    private fun settings() {
        account()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("open_settings").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test fun primarySwitchAnimatesBothPagesAndRestoresScroll() {
        account()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("tab_1").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("account_screen").assertExists()
        compose.onNodeWithTag("transfer-page").assertExists()
        screenshot("primary-transition")
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("tab_3").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("open_settings").assertIsDisplayed()
    }

    @Test fun predictiveBackFollowsProgressCancelsAndCompletes() {
        val container = (compose.activity.application as PanXApplication).container
        runBlocking { container.settings.edit { it.copy(predictiveBack = true, themeMode = ThemeMode.LIGHT, monet = false) } }
        settings()
        val before = compose.onNodeWithTag("settings_screen").fetchSemanticsNode().boundsInRoot.left
        screenshot("settings")
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, .5f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(100)
        val during = compose.onNodeWithTag("settings_screen").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Back gesture must move the scene before commit: before=$before during=$during", during > before + 1f)
        screenshot("predictive-back-half")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 400f, .8f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        compose.onNodeWithTag("account_screen").assertIsDisplayed()
    }

    @Test fun reopeningLoginStartsWithFreshForm() {
        account()
        compose.onNodeWithTag("account_screen").performScrollToIndex(0)
        compose.onNodeWithTag("account_login_entry").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("login_passport").performTextInput("local-test-only")
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("account_login_entry").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("login_passport").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun capturesAllPrimaryPagesInLightAndDarkThemes() {
        val container = (compose.activity.application as PanXApplication).container
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            runBlocking { container.settings.edit { it.copy(themeMode = theme, monet = false) } }
            compose.waitForIdle()
            for (page in 0..3) {
                compose.onNodeWithTag("tab_$page").performClick()
                compose.mainClock.advanceTimeBy(800)
                compose.onNodeWithTag(listOf("files_list", "transfer-page", "share-page", "account_screen")[page]).assertIsDisplayed()
                screenshot("${theme.name.lowercase()}-page-$page")
            }
        }
        runBlocking { container.settings.edit { it.copy(themeMode = ThemeMode.SYSTEM, monet = true) } }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-redesign")
        check(directory.isDirectory || directory.mkdirs())
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun loggedOutShareOffersLoginInsteadOfLoadingForever() {
        compose.onNodeWithTag("tab_2").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("share-login").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("login_passport").assertIsDisplayed()
    }
}
