package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import io.github.bileizhen.pan123x.feature.about.AboutLogoOpacity
import io.github.bileizhen.pan123x.feature.about.AboutScrollProgress
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutDesignTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openAbout() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithText("关于").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("about_screen").assertIsDisplayed()
    }

    @Test fun directScrollJumpCollapsesHeaderAndPrivacyReturnKeepsScroll() {
        openAbout()
        compose.onNodeWithTag("about_logo").assert(SemanticsMatcher.expectValue(AboutLogoOpacity, 1f))
        compose.onNodeWithTag("about_screen").performScrollToIndex(1)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("about_screen").assert(SemanticsMatcher.expectValue(AboutScrollProgress, 1f))
        compose.onNodeWithTag("about_logo").assert(SemanticsMatcher.expectValue(AboutLogoOpacity, 0f))
        compose.onNodeWithTag("about_privacy").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("privacy_screen").assertIsDisplayed()
        compose.onNodeWithText("账号与凭据").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("about_screen").assert(SemanticsMatcher.expectValue(AboutScrollProgress, 1f))
        compose.onNodeWithTag("about_logo").assert(SemanticsMatcher.expectValue(AboutLogoOpacity, 0f))
        compose.onNodeWithTag("about_screen").performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("about_logo").assert(SemanticsMatcher.expectValue(AboutLogoOpacity, 1f))
    }

    @Test fun creditDialogBackClosesDialogBeforeLeavingPage() {
        openAbout()
        compose.onNodeWithTag("about_credit_bileizhen").performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("about_credit_bileizhen").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("项目与致谢").assertIsDisplayed()
        capture("credit-dialog")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("项目与致谢").assertDoesNotExist()
        compose.onNodeWithTag("about_screen").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("account_screen").assertIsDisplayed()
    }

    @Test fun capturesLightDarkAndBlurDisabledDesigns() {
        val container = (compose.activity.application as PanXApplication).container
        for ((theme, blur, name) in listOf(Triple(ThemeMode.LIGHT, true, "light"), Triple(ThemeMode.DARK, true, "dark"), Triple(ThemeMode.LIGHT, false, "fallback"))) {
            runBlocking { container.settings.edit { it.copy(themeMode = theme, monet = false, blur = blur) } }
            openAbout()
            compose.onNodeWithContentDescription("123PanX 图标").assertIsDisplayed()
            assertLogoContrast()
            capture("$name-header")
            compose.onNodeWithTag("about_screen").performScrollToIndex(1)
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("about_credit_leifetch").performScrollTo()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("about_credit_leifetch").assertIsDisplayed()
            capture("$name-credits")
            compose.onNodeWithTag("navigate_back").performClick()
            compose.mainClock.advanceTimeBy(800)
        }
        runBlocking { container.settings.edit { it.copy(themeMode = ThemeMode.SYSTEM, monet = true, blur = true) } }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "leifetch-about")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun assertLogoContrast() {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val bounds = compose.onNodeWithTag("about_logo").fetchSemanticsNode().boundsInRoot
        fun sample(x: Float, y: Float) = Color(bitmap.getPixel(
            (bounds.left + bounds.width * x).toInt(), (bounds.top + bounds.height * y).toInt(),
        )).luminance()
        // Interior of the original "1" versus the clear area above the wordmark.
        // This catches the earlier dark-blue-on-blue result even if navigation passes.
        val foreground = sample(.08f, .5f)
        val background = sample(.08f, .2f)
        val ratio = (maxOf(foreground, background) + .05f) / (minOf(foreground, background) + .05f)
        assertTrue("About mark contrast is $ratio; expected >= 3", ratio >= 3f)
    }
}
