// SPDX-License-Identifier: GPL-3.0-only
// Four-mode cards and manual fields adapted from LeiFetch ui/ProxyScreen.kt.
package io.github.bileizhen.pan123x.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.network.ProxyMode
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProxyScreen(viewModel: SettingsViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Secrets stay out of saved-state bundles. Changes are committed atomically with Save.
    var draft by remember { mutableStateOf(state.proxy) }
    var port by remember { mutableStateOf(state.proxy.port.takeIf { it > 0 }?.toString().orEmpty()) }
    LaunchedEffect(state.proxy, state.proxySavedRevision) {
        draft = state.proxy
        port = state.proxy.port.takeIf { it > 0 }?.toString().orEmpty()
    }
    val mode = draft.effectiveMode
    val manual = mode == ProxyMode.MANUAL && !state.busy
    val next = draft.copy(host = draft.host.trim(), port = port.toIntOrNull() ?: 0)
    val validation = next.validationError()
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).testTag("proxy_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(uiText("代理"), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text(uiText("API、下载、上传和预览共用此配置。直连名单中的地址始终直连。"),
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    ProxyMode.entries.forEach { option ->
                        BasicComponent(title = uiText(option.label), summary = uiText(option.summary), enabled = !state.busy,
                            onClick = { draft = draft.withMode(option); viewModel.dismissMessage() },
                            modifier = Modifier.testTag("proxy_mode_${option.name.lowercase()}").semantics { selected = mode == option }, endActions = {
                                androidx.compose.animation.AnimatedVisibility(mode == option, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                                    Icon(PanIcons.Check, uiText("已选择"), tint = MiuixTheme.colorScheme.primary)
                                }
                            })
                    }
                }
            }
            item { SmallTitle(uiText("手动配置"), insideMargin = SectionTitleMargin) }
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OverlaySpinnerPreference(title = uiText("代理类型"), items = listOf("HTTP", "SOCKS5").map { DropdownItem(it) },
                            selectedIndex = if (draft.type == "SOCKS5") 1 else 0, enabled = manual,
                            onSelectedIndexChange = { draft = draft.copy(type = if (it == 1) "SOCKS5" else "HTTP") }, modifier = Modifier.testTag("proxy_type"))
                        TextField(draft.host, onValueChange = { draft = draft.copy(host = it.take(253)) }, label = uiText("服务器地址"), singleLine = true,
                            enabled = manual, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.testTag("proxy_host"))
                        TextField(port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = uiText("端口"), singleLine = true,
                            enabled = manual, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("proxy_port"))
                        TextField(draft.username, onValueChange = { draft = draft.copy(username = it) }, label = uiText("用户名（可选）"), singleLine = true,
                            enabled = manual, modifier = Modifier.testTag("proxy_username"))
                        TextField(draft.password, onValueChange = { draft = draft.copy(password = it) }, label = uiText("密码（可选）"), singleLine = true,
                            enabled = manual, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("proxy_password"))
                    }
                }
            }
            item { SmallTitle(uiText("直连名单"), insideMargin = SectionTitleMargin) }
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(draft.bypass, onValueChange = { draft = draft.copy(bypass = it.take(4097)) }, label = uiText("直连名单（可留空）"),
                            enabled = !state.busy, modifier = Modifier.testTag("proxy_bypass"))
                        Text(uiText("用逗号或空格分隔；域名匹配自身及子域，* 表示全部直连，<local> 表示 localhost 和内网地址。"),
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item {
                Text(uiText("自动模式探测本机常见端口，执行 HTTP CONNECT 或 SOCKS5 握手，结果缓存 5 分钟。已选择的代理连接失败时不会改走直连。"),
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText(validation ?: state.error ?: state.message ?: "保存后对新请求生效，已有请求继续使用原线路。"),
                fontSize = 12.sp, color = if (validation != null || state.error != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(uiText(if (state.busy) "保存中…" else "保存"), enabled = !state.busy && validation == null,
                onClick = { focus.clearFocus(); viewModel.saveProxy(next) }, modifier = Modifier.fillMaxWidth().testTag("proxy_save"))
        }
    }
}
