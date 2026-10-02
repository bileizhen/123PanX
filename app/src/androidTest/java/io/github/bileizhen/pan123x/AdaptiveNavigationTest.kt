package io.github.bileizhen.pan123x

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bileizhen.pan123x.data.settings.AppSettings
import io.github.bileizhen.pan123x.ui.MainPages
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Text
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import io.github.bileizhen.pan123x.ui.component.PlainFloatingBar
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.HighApiFloatingNavigation
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class AdaptiveNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blurredFloatingSurfaceKeepsWhiteBackdropNeutral() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        compose.setContent {
            PanXTheme(AppSettings(themeMode = ThemeMode.LIGHT, monet = false)) {
                HighApiFloatingNavigation(0, listOf("文件", "传输", "分享", "我的"),
                    listOf(PanIcons.Folder, PanIcons.Transfer, PanIcons.Share, PanIcons.Account), {}, blur = true, glass = true) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        // Unsupported renderers deliberately use the independently tested opaque fallback.
        val tag = if (compose.onAllNodesWithTag("blur_floating_bar").fetchSemanticsNodes().isNotEmpty()) "blur_floating_bar" else "solid_floating_bar"
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val color = pixels[(pixels.width * .70f).toInt(), (pixels.height * .20f).toInt()]
        assertTrue("White backdrop must remain neutral with glass enabled: $color", color.red > .98f && color.green > .98f && color.blue > .98f)
    }

    @Test fun lightFloatingSurfaceDoesNotAddGrayTintToWhiteBackdrop() {
        compose.setContent {
            PanXTheme(AppSettings(themeMode = ThemeMode.LIGHT, monet = false)) {
                Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                    PlainFloatingBar(0, listOf("文件", "传输", "分享", "我的"), listOf(PanIcons.Folder, PanIcons.Transfer, PanIcons.Share, PanIcons.Account)) {}
                }
            }
        }
        val pixels = compose.onNodeWithTag("solid_floating_bar").captureToImage().toPixelMap()
        val color = pixels[(pixels.width * .65f).toInt(), (pixels.height * .12f).toInt()]
        assertTrue("Unselected pill surface should stay white: $color", color.red > .98f && color.green > .98f && color.blue > .98f)
    }

    @Test
    fun adaptsAt840DpAndKeepsThresholdIndependentOfScale() {
        var width by mutableStateOf(839.dp)
        var selected by mutableIntStateOf(0)
        var scale by mutableStateOf(1f)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                // The explicit test host models a resizable window; no device wm overrides.
                Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(width).fillMaxHeight()) {
                    PanXTheme(AppSettings(uiScale = scale)) {
                        MainPages(selected, floating = true, blur = false, liquid = false, uiScale = scale,
                            onSelect = { selected = it }) { Text("页面 $selected") }
                    }
                }
            }
        }
        compose.onNodeWithTag("main_sidebar").assertDoesNotExist()
        compose.onNodeWithTag("solid_floating_bar").assertExists()
        compose.runOnIdle { width = 840.dp }
        compose.onNodeWithTag("main_sidebar").assertExists()
        compose.onNodeWithTag("solid_floating_bar").assertDoesNotExist()
        compose.runOnIdle { width = 900.dp }
        compose.onNodeWithTag("tab_2").performClick().assertIsSelected()
        compose.runOnIdle { scale = 1.1f }
        compose.onNodeWithTag("main_sidebar").assertExists()
        compose.runOnIdle { width = 839.dp }
        compose.onNodeWithTag("main_sidebar").assertDoesNotExist()
        compose.onNodeWithTag("solid_floating_bar").assertExists()
    }

    @Test
    fun normalBarAndSolidFloatingFallbackRemainInteractive() {
        var floating by mutableStateOf(false)
        var selected by mutableIntStateOf(0)
        compose.setContent {
            PanXTheme(AppSettings(blur = false, floatingBar = floating)) {
                MainPages(selected, floating, blur = false, liquid = false, uiScale = 1f,
                    onSelect = { selected = it }) { Text("页面 $selected") }
            }
        }
        compose.onNodeWithTag("standard_navigation_bar").assertExists()
        compose.onNodeWithTag("solid_floating_bar").assertDoesNotExist()
        compose.onNodeWithTag("tab_1").performClick().assertIsSelected()
        compose.runOnIdle { floating = true }
        compose.onNodeWithTag("standard_navigation_bar").assertDoesNotExist()
        compose.onNodeWithTag("solid_floating_bar").assertExists()
        compose.onNodeWithTag("tab_3").performClick().assertIsSelected()
        compose.onNodeWithTag("tab_0").performClick().assertIsSelected()
    }
}
