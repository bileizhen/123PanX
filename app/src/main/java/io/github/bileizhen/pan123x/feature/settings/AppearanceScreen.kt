// Appearance layout migrated from LeiFetch MainActivity.appearanceItems (XBlocker / SukiSU-Ultra).
// SPDX-License-Identifier: GPL-3.0-only.
package io.github.bileizhen.pan123x.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.data.settings.AppSettings
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.SuperSwitch
import io.github.bileizhen.pan123x.ui.theme.LocalDarkTheme
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppearanceScreen(viewModel: SettingsViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config = state.settings
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("appearance_screen"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            ThemePreviewCardMiuix(LocalDarkTheme.current, config.monet, config.floatingBar, config.liquidGlass && config.blur && Build.VERSION.SDK_INT >= 33)
            Spacer(Modifier.height(28.dp))
            TabRow(tabs = ThemeMode.entries.map { uiText(it.label) }, selectedTabIndex = config.themeMode.ordinal,
                onTabSelected = { index -> viewModel.edit { it.copy(themeMode = ThemeMode.entries[index]) } }, height = 48.dp)
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                SuperSwitch(uiText("Monet 动态颜色"), config.monet, { value -> viewModel.edit { it.copy(monet = value) } },
                    enabled = Build.VERSION.SDK_INT >= 31, summary = if (Build.VERSION.SDK_INT < 31) "需要 Android 12 或更高版本" else null,
                    startAction = { SettingsIcon(PanIcons.Image) }, tag = "setting_monet")
            }
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                SuperSwitch(uiText("模糊"), config.blur, { value -> viewModel.edit { it.copy(blur = value) } },
                    enabled = Build.VERSION.SDK_INT >= 33, summary = if (Build.VERSION.SDK_INT >= 33) "模糊悬浮底栏背景" else "需要 Android 13 或更高版本",
                    startAction = { SettingsIcon(PanIcons.Blur) }, tag = "setting_blur")
                SuperSwitch(uiText("悬浮底栏"), config.floatingBar, { value -> viewModel.edit { it.copy(floatingBar = value) } }, summary = uiText("文件、传输、分享与我的"),
                    startAction = { SettingsIcon(PanIcons.BottomBar) }, tag = "setting_floating")
                SuperSwitch(uiText("液态玻璃"), config.liquidGlass, { value -> viewModel.edit { it.copy(liquidGlass = value) } },
                    enabled = config.floatingBar && config.blur && Build.VERSION.SDK_INT >= 33, summary = uiText("为悬浮底栏应用液态玻璃效果"),
                    startAction = { SettingsIcon(PanIcons.Drop) }, tag = "setting_liquid")
            }
            Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                SuperSwitch(uiText("预测性返回手势"), config.predictiveBack, { value -> viewModel.edit { it.copy(predictiveBack = value) } },
                    enabled = Build.VERSION.SDK_INT >= 34, summary = uiText("启用预测性返回手势支持"),
                    startAction = { SettingsIcon(PanIcons.Back) }, tag = "setting_predictive")
                var sliderValue by remember(config.uiScale) { mutableFloatStateOf(config.uiScale) }
                var showScaleDialog by rememberSaveable { mutableStateOf(false) }
                BasicComponent(title = uiText("显示缩放"), summary = uiText("调整界面整体缩放"), startAction = { SettingsIcon(PanIcons.Scale) },
                    endActions = {
                        Text("${(sliderValue * 100).roundToInt()}%", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        Icon(PanIcons.Forward, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    }, onClick = { showScaleDialog = true },
                    bottomAction = {
                        Slider(value = sliderValue, onValueChange = { sliderValue = it },
                            onValueChangeFinished = { viewModel.edit { it.copy(uiScale = sliderValue) } },
                            valueRange = AppSettings.MIN_SCALE..AppSettings.MAX_SCALE, showKeyPoints = true,
                            keyPoints = listOf(.8f, .9f, 1f, 1.1f, 1.2f), magnetThreshold = .01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step, modifier = Modifier.testTag("setting_scale").semantics {
                                // Miuix's SetProgress does not invoke onValueChangeFinished;
                                // accessibility/programmatic adjustments must persist as well.
                                setProgress { target ->
                                    sliderValue = target.coerceIn(AppSettings.MIN_SCALE, AppSettings.MAX_SCALE)
                                    viewModel.edit { it.copy(uiScale = sliderValue) }
                                    true
                                }
                            })
                    })
                OverlayDialog(show = showScaleDialog, title = uiText("显示缩放"), summary = "80% - 120%", onDismissRequest = { showScaleDialog = false }) {
                    var input by remember(showScaleDialog) { mutableStateOf((config.uiScale * 100).roundToInt().toString()) }
                    TextField(value = input, onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) input = it }, singleLine = true)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(uiText("取消"), onClick = { showScaleDialog = false }, modifier = Modifier.weight(1f))
                        TextButton(uiText("确定"), enabled = input.toIntOrNull() in 80..120, onClick = {
                            input.toIntOrNull()?.let { value -> viewModel.edit { it.copy(uiScale = value.coerceIn(80, 120) / 100f) } }
                            showScaleDialog = false
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        state.error?.let { message -> item { Text(message, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
    }
}
