package io.github.bileizhen.pan123x.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding

/**
 * 账户管理页（M7 多账户）：列出本机保存的账户（头像 / 名称 / UID、
 * "当前"与"可切换"徽标），行操作"切换 / 移除"均二次确认，底部"添加账户"跳转登录页。
 * busy 期间所有操作按钮禁用，防止并发切换 / 移除（可靠性优先）。
 */
@Composable
fun AccountsScreen(viewModel: AccountsViewModel, onAddAccount: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bottomPadding = LocalContentBottomPadding.current
    var pendingSwitchId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoveId by rememberSaveable { mutableStateOf<String?>(null) }
    // 操作结果 / 错误只作一次性反馈，3 秒后自动清掉（与 AccountScreen 同策略）。
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3_000)
            viewModel.consumeMessage()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("accounts_page"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "${state.rows.size} 个账户" + if (state.busy) " · 处理中…" else "",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        state.message?.let { message ->
            item {
                Text(
                    message,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.testTag("accounts_feedback"),
                )
            }
        }
        if (state.rows.isEmpty()) item { AccountsEmpty() }
        items(state.rows, key = { it.accountId }) { row ->
            AccountRowCard(
                row = row,
                isCurrent = row.accountId == state.activeAccountId,
                busy = state.busy,
                onSwitch = { pendingSwitchId = row.accountId },
                onRemove = { pendingRemoveId = row.accountId },
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                TextButton(
                    uiText("添加账户"),
                    onClick = onAddAccount,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_add"),
                )
            }
        }
    }

    val pendingSwitch = state.rows.firstOrNull { it.accountId == pendingSwitchId }
    // 切换确认：非破坏性，说明浏览上下文立即切换、本地缓存保留。
    SuperDialog(
        show = pendingSwitch != null,
        title = "切换到「${pendingSwitch?.displayName.orEmpty()}」？",
        summary = uiText("浏览上下文将立即切换到该账户，各账户的本地缓存会保留。"),
        onDismissRequest = { pendingSwitchId = null },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { pendingSwitchId = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(
                uiText("切换"),
                onClick = {
                    pendingSwitchId?.let(viewModel::switchTo)
                    pendingSwitchId = null
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("account_switch_confirm"),
            )
        }
    }

    val pendingRemove = state.rows.firstOrNull { it.accountId == pendingRemoveId }
    // 移除确认：不可逆，明示删除范围；确认键红色语义（与回收站永久删除同风格）。
    SuperDialog(
        show = pendingRemove != null,
        title = "移除「${pendingRemove?.displayName.orEmpty()}」？",
        summary = uiText("将删除该账户的本地缓存与凭据，云端文件不受影响。"),
        onDismissRequest = { pendingRemoveId = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("移除后需重新登录才能再次使用该账户。"), fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(uiText("取消"), onClick = { pendingRemoveId = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                TextButton(
                    uiText("移除"),
                    onClick = {
                        pendingRemoveId?.let(viewModel::removeAccount)
                        pendingRemoveId = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error.copy(alpha = 0.12f),
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("account_remove_confirm"),
                )
            }
        }
    }
}

/** 单个账户行：头像 + 名称 / UID + 徽标 + 行操作"切换 / 移除"。 */
@Composable
private fun AccountRowCard(
    row: AccountRow,
    isCurrent: Boolean,
    busy: Boolean,
    onSwitch: () -> Unit,
    onRemove: () -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Card(
        Modifier.fillMaxWidth().testTag("account_item_${row.accountId}"),
        cornerRadius = 22.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AvatarBadge(
                avatarUri = row.avatarUri?.takeIf { it.isNotBlank() },
                initial = row.displayName.trim().firstOrNull()?.uppercase() ?: "?",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        row.displayName,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        isCurrent -> AccountBadge("当前", filled = true)
                        row.hasCredential -> AccountBadge("可切换", filled = false)
                    }
                }
                if (row.uid.isNotBlank()) {
                    Text("UID ${row.uid}", fontSize = 12.sp, color = summary)
                }
                if (!row.hasCredential) {
                    Text(uiText("本机没有保存登录凭据，仅可移除"), fontSize = 12.sp, color = summary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    uiText("切换"),
                    onClick = onSwitch,
                    enabled = !busy && row.hasCredential && !isCurrent,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("account_switch_${row.accountId}"),
                )
                TextButton(
                    uiText("移除"),
                    onClick = onRemove,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("account_remove_${row.accountId}"),
                )
            }
        }
    }
}

/** 账户徽标：当前账户实心主色，可切换账户浅色描边感。 */
@Composable
private fun AccountBadge(text: String, filled: Boolean) {
    Box(
        Modifier
            .background(
                if (filled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = if (filled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary,
        )
    }
}

/** 账户头像：优先加载远端签名 URL，加载中/失败/无 URL 时回退昵称首字占位圆（同 AccountScreen）。 */
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

@Composable
private fun AccountsEmpty() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(
        Modifier.fillMaxWidth().testTag("accounts_empty"),
        cornerRadius = 24.dp,
        insideMargin = PaddingValues(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("还没有保存的账户"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                uiText("登录 123 云盘账户后会自动保存在本机，可在这里切换或移除。"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
