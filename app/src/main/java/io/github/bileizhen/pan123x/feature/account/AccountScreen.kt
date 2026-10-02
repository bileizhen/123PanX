package io.github.bileizhen.pan123x.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import coil3.compose.SubcomposeAsyncImage
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.ui.util.formatBytes
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.LocalPageActive

/**
 * 我的页：顶部用户卡按 [SessionState] 三分支渲染，
 * 下方"云盘 / 应用"分组保持 M0 的列表项结构（ShellNavigationTest 依赖第 5 项为应用卡片）。
 */
@Composable
fun AccountScreen(viewModel: AccountViewModel, onOpen: (String) -> Unit, updates: io.github.bileizhen.pan123x.feature.about.UpdateViewModel? = null) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var updateLinkError by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val bottomPadding = LocalContentBottomPadding.current
    val active = LocalPageActive.current
    LaunchedEffect(active, (state.session as? SessionState.Ready)?.accountId) {
        if (active) viewModel.onVisible()
    }
    var confirmingLogout by rememberSaveable { mutableStateOf(false) }
    // 刷新结果等提示只作短期反馈，3 秒后自动清掉，避免过期文案常驻页面。
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3_000)
            viewModel.consumeMessage()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("account_screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(uiText("账户与云盘"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            AccountUserCard(
                state,
                onOpenLogin = { onOpen("login") },
                onRequestLogout = { confirmingLogout = true },
            )
        }
        item { SmallTitle(uiText("云盘")) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = uiText("云盘信息"), summary = uiText("会员、空间明细与登录设备"), onClick = { onOpen("cloud-info") }, modifier = Modifier.testTag("account_cloud_info"))
                ArrowPreference(title = uiText("账户管理"), summary = uiText("切换 / 移除已保存的账户"), onClick = { onOpen("accounts") })
                ArrowPreference(title = uiText("离线下载"), summary = uiText("添加链接与秒传导入"), onClick = { onOpen("offline") })
                ArrowPreference(title = uiText("回收站"), summary = uiText("恢复或永久删除文件"), onClick = { onOpen("recycle") })
            }
        }
        item { SmallTitle(uiText("应用")) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(modifier = Modifier.testTag("open_settings"), title = uiText("设置"), summary = uiText("主题、外观与显示"), onClick = { onOpen("settings") })
                ArrowPreference(title = uiText("导出日志"), summary = uiText("保存或分享诊断压缩包"), onClick = { onOpen("export-logs") }, modifier = Modifier.testTag("account_export_logs"))
                ArrowPreference(title = uiText("检查更新"), summary = uiText("查看最新正式版本"), onClick = { updates?.check() }, modifier = Modifier.testTag("account_check_update"))
                ArrowPreference(title = uiText("关于"), summary = "123PanX", onClick = { onOpen("about") }, modifier = Modifier.testTag("account_about"))
            }
        }
    }
    updates?.let { updateVm ->
        io.github.bileizhen.pan123x.feature.about.UpdateDialog(updateVm) { link ->
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, link.toUri())) }
            catch (_: android.content.ActivityNotFoundException) { updateLinkError = "当前设备没有可打开链接的应用" }
            catch (_: SecurityException) { updateLinkError = "无法打开链接，请检查系统设置" }
        }
    }
    SuperDialog(show = updateLinkError != null, title = uiText("无法打开链接"), onDismissRequest = { updateLinkError = null }) {
        Text(uiText(updateLinkError.orEmpty()))
        TextButton(uiText("关闭"), onClick = { updateLinkError = null })
    }
    // 退出登录与删除同类：不可逆操作必须二次确认。
    SuperDialog(
        show = confirmingLogout,
        title = uiText("退出登录？"),
        summary = uiText("仅清理保存在本机的登录凭据，云端文件不受影响。"),
        onDismissRequest = { confirmingLogout = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { confirmingLogout = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(
                uiText("退出登录"),
                onClick = { confirmingLogout = false; viewModel.logout() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("account_logout_confirm"),
            )
        }
    }
}

@Composable
private fun AccountUserCard(state: AccountUiState, onOpenLogin: () -> Unit, onRequestLogout: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val session = state.session) {
                SessionState.Restoring -> {
                    Icon(PanIcons.Account, null, modifier = Modifier.size(48.dp), tint = MiuixTheme.colorScheme.primary)
                    Text(uiText("正在恢复登录…"), fontSize = 24.sp)
                    Text(uiText("正在读取本机保存的登录状态"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }

                SessionState.LoggedOut -> {
                    Icon(PanIcons.Account, null, modifier = Modifier.size(48.dp), tint = MiuixTheme.colorScheme.primary)
                    Text(uiText("未登录"), fontSize = 24.sp)
                    Text(uiText("登录后即可浏览云端文件"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextButton(
                        uiText("登录"),
                        onClick = onOpenLogin,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("account_login_entry"),
                    )
                }

                is SessionState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AvatarBadge(
                            avatarUri = state.account?.avatarUri?.takeIf { it.isNotBlank() },
                            initial = session.displayName.trim().firstOrNull()?.uppercase() ?: "?",
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(session.displayName, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (session.uid.isNotBlank()) {
                                Text("UID ${session.uid}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            state.account?.takeIf { it.hasCloudInfo }?.let {
                                Text(if (it.vip) "VIP${it.vipLevel.takeIf { level -> level > 0 } ?: ""}" else "非会员", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                            Text(
                                state.account?.takeIf { it.totalBytes > 0 }
                                    ?.let { "空间 ${formatBytes(it.usedBytes)} / ${formatBytes(it.totalBytes)}" }
                                    ?: "空间用量待刷新",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    state.account?.takeIf { it.totalBytes > 0 }?.let { account ->
                        LinearProgressIndicator(progress = (account.usedBytes.toDouble() / account.totalBytes).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                    }
                    state.message?.let { message ->
                        Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
                    }
                    TextButton(
                        uiText("退出登录"),
                        onClick = onRequestLogout,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("account_logout"),
                    )
                }
            }
        }
    }
}

/** 账户头像：优先加载远端签名 URL，加载中/失败/无 URL 时回退昵称首字占位圆。 */
@Composable
private fun AvatarBadge(avatarUri: String?, initial: String) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    if (avatarUri == null) {
        AvatarInitial(initial)
        return
    }
    SubcomposeAsyncImage(
        model = avatarUri,
        contentDescription = uiText("账户头像"),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(56.dp).clip(CircleShape),
        loading = { AvatarInitial(initial) },
        error = { AvatarInitial(initial) },
    )
}

@Composable
private fun AvatarInitial(initial: String) {
    Box(
        Modifier.size(56.dp).background(MiuixTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, fontSize = 22.sp, color = MiuixTheme.colorScheme.onPrimary)
    }
}
