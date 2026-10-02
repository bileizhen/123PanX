// ThemeController and density approach adapted from LeiFetch / XBlocker / SukiSU-Ultra.
// GPL-3.0; see THIRD_PARTY_NOTICES.md.
package io.github.bileizhen.pan123x.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import io.github.bileizhen.pan123x.data.settings.AppSettings
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean = LocalDarkTheme.current

@Composable
fun PanXTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val monet = settings.monet && Build.VERSION.SDK_INT >= 31
    val mode = when (settings.themeMode) {
        ThemeMode.SYSTEM -> if (monet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
        ThemeMode.LIGHT -> if (monet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        ThemeMode.DARK -> if (monet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
    }
    val controller = remember(mode, dark) {
        ThemeController(
            colorSchemeMode = mode, isDark = dark,
            lightColors = lightColorScheme(),
            darkColors = darkColorScheme(
                background = Color(0xFF111214), surface = Color(0xFF111214),
                surfaceContainer = Color(0xFF1D1F22),
            ),
        )
    }
    val context = LocalContext.current
    LaunchedEffect(dark) {
        (context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val density = LocalDensity.current
    // Recreated only when the scale actually changes: FloatingBottomBar keys remembers on this
    // instance, so a fresh Density per recomposition would rebuild its animations and `Animatable`s.
    val scaledDensity = remember(density.density, density.fontScale, settings.uiScale) {
        Density(density.density * settings.uiScale, density.fontScale)
    }
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalDensity provides scaledDensity,
        io.github.bileizhen.pan123x.ui.util.LocalAppLanguage provides settings.language,
    ) { MiuixTheme(controller = controller, content = content) }
}
