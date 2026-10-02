package io.github.bileizhen.pan123x

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensAboutFromAccountAndReadsLicense() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithText("关于").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("123PanX 图标").assertIsDisplayed()
        compose.onNodeWithTag("about_license").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("license_screen").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("about_screen").assertIsDisplayed()
    }

    @Test fun settingsOmitsAboutAndAccountStillOpensNotices() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithText("刷新账户信息").assertDoesNotExist()
        compose.onNodeWithTag("account_screen").performScrollToIndex(5)
        compose.onNodeWithTag("open_settings").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("setting_share_clipboard"))
        compose.onNodeWithTag("setting_share_clipboard").assertIsDisplayed()
        compose.onNodeWithTag("settings_about").assertDoesNotExist()
        compose.onNodeWithTag("settings_diagnostics").assertDoesNotExist()
        compose.onNodeWithText("日志").assertDoesNotExist()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("关于").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("about_screen").assertIsDisplayed()
        compose.onNodeWithTag("about_notices").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("license_screen").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("about_screen").assertIsDisplayed()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("account_screen").assertIsDisplayed()
    }
}
