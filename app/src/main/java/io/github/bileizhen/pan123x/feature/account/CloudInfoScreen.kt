package io.github.bileizhen.pan123x.feature.account

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding
import io.github.bileizhen.pan123x.ui.component.LocalPageActive
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.util.formatBytes
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix grouped details; all values come from the current account's API metadata. */
@Composable
fun CloudInfoScreen(viewModel: AccountViewModel, onOpen: (String) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val active = LocalPageActive.current
    val accountId = (state.session as? SessionState.Ready)?.accountId
    LaunchedEffect(active, accountId) { if (active) viewModel.onCloudInfoVisible() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (active) viewModel.onCloudInfoVisible() }
    var confirmLogout by rememberSaveable(accountId) { mutableStateOf(false) }
    val account = state.account?.takeIf { it.hasCloudInfo }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("cloud_info_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = LocalContentBottomPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.session !is SessionState.Ready) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    BasicComponent(title = if (state.session == SessionState.Restoring) "正在恢复登录…" else "未登录", summary = uiText("登录后查看账户、空间与设备信息"))
                    if (state.session == SessionState.LoggedOut) ArrowPreference(title = uiText("登录"), onClick = { onOpen("login") })
                }
            }
        } else {
            item { SmallTitle(uiText("账户信息")) }
            item {
                Card(Modifier.fillMaxWidth().animateContentSize()) {
                    InfoRow("账户", account?.maskedPassport?.ifBlank { state.account?.displayName.orEmpty() } ?: state.account?.displayName.orEmpty().ifBlank { "待同步" }, PanIcons.Account, "cloud_account")
                    InfoRow("UID", (state.session as SessionState.Ready).uid.ifBlank { "待同步" }, PanIcons.Account, "cloud_uid")
                    InfoRow("VIP", uiText(account?.let(::membership) ?: "待同步"), PanIcons.Info, "cloud_vip")
                    ArrowPreference(title = uiText("切换登录账号"), summary = uiText("选择已保存的账户或添加账户"), onClick = { onOpen("accounts") }, modifier = Modifier.testTag("cloud_switch_account"))
                    ArrowPreference(title = uiText("退出登录"), onClick = { confirmLogout = true }, modifier = Modifier.testTag("cloud_logout"))
                }
            }
            if (state.refreshing || state.message != null || account == null) item {
                Text(state.message ?: if (state.refreshing) "正在同步账户信息…" else "账户信息暂不可用，稍后进入页面会自动重试", modifier = Modifier.padding(horizontal = 16.dp).testTag("cloud_sync_status"), color = if (state.message != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            item { SmallTitle(uiText("存储信息")) }
            item {
                Card(Modifier.fillMaxWidth().animateContentSize()) {
                    InfoRow("空间用量", account?.let { spaceUsage(it.usedBytes, it.permanentBytes) } ?: "待同步", PanIcons.Folder, "cloud_space")
                    InfoRow("专业空间", account?.let { spaceUsage(it.professionalUsedBytes, it.professionalTotalBytes) } ?: "待同步", PanIcons.Folder, "cloud_professional_space")
                    InfoRow("标准空间", account?.let { spaceUsage(it.standardUsedBytes, it.standardTotalBytes) } ?: "待同步", PanIcons.Folder, "cloud_standard_space")
                    if (account != null && account.temporaryBytes > 0) InfoRow("临期空间", formatBytes(account.temporaryBytes), PanIcons.Folder, "cloud_temporary_space")
                    InfoRow("文件数量", account?.fileCount?.toString() ?: "待同步", PanIcons.File, "cloud_file_count")
                    InfoRow("直链流量", account?.let { formatBytes(it.directTrafficBytes) } ?: "待同步", PanIcons.Share, "cloud_direct_traffic")
                }
            }
            item { SmallTitle(uiText("登录设备")) }
            if (state.devicesError != null) item {
                Card(Modifier.fillMaxWidth().animateContentSize()) {
                    BasicComponent(title = uiText("设备信息获取失败"), summary = state.devicesError)
                    ArrowPreference(title = uiText("重试"), onClick = { viewModel.onCloudInfoVisible(forceDevices = true) }, modifier = Modifier.testTag("cloud_devices_retry"))
                }
            }
            if (state.devicesLoading) item { Card(Modifier.fillMaxWidth()) { BasicComponent(title = uiText("正在获取登录设备…")) } }
            if (state.devices.isEmpty() && state.devicesLoaded && !state.devicesLoading && state.devicesError == null) item {
                Card(Modifier.fillMaxWidth()) { BasicComponent(title = uiText("未获取到设备信息"), summary = uiText("服务端返回的登录设备列表为空")) }
            }
            itemsIndexed(state.devices, key = { index, device -> "$accountId-$index-${device.name}-${device.lastLoginTime}" }) { index, device ->
                Card(Modifier.fillMaxWidth().animateItem().semantics(mergeDescendants = true) {}.testTag("cloud_device_$index")) {
                    BasicComponent(
                        title = buildString {
                            append("${index + 1}. ${device.name.ifBlank { "未知设备" }}")
                            if (device.type.isNotBlank()) append(" (${device.type})")
                            if (device.current) append(" · 当前")
                        },
                        summary = "平台：${device.platform.ifBlank { "未提供" }}\nIP：${device.ip.ifBlank { "未提供" }}\n登录：${deviceLoginTime(device.lastLoginTime)}\n方式：${device.loginType.ifBlank { "未提供" }}",
                        startAction = { Icon(PanIcons.Device, null, modifier = Modifier.padding(end = 6.dp)) },
                    )
                }
            }
        }
    }
    OverlayDialog(show = confirmLogout, title = uiText("退出登录？"), summary = uiText("仅清理保存在本机的登录凭据，云端文件不受影响。"), onDismissRequest = { confirmLogout = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), { confirmLogout = false }, Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(uiText("退出登录"), { confirmLogout = false; viewModel.logout() }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("cloud_logout_confirm"))
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    BasicComponent(title = uiText(title), summary = value, modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag), startAction = { Icon(icon, null, modifier = Modifier.padding(end = 6.dp)) })
}
