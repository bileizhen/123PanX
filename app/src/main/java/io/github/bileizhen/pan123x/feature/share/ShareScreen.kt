// Share management page (M6): real list + copy + revoke, replacing the M0 local demo.
// Visual skeleton keeps the M0 Miuix card language (large radius, summary-gray typography).
package io.github.bileizhen.pan123x.feature.share

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.network.ShareItemDto
import io.github.bileizhen.pan123x.data.share.ShareListStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 分享管理页（M6）：状态显式区分 Loading / Empty / Error / Content，
 * 有旧列表时刷新失败不清空列表，只加横幅（与文件页/回收站同策略）。
 * 撤销是破坏性操作，走 Miuix SuperDialog 二次确认。
 */
@Composable
fun ShareScreen(viewModel: ShareViewModel, onOpenLogin: () -> Unit = {}) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.accountId) { if (state.accountId != null) viewModel.refresh() }
    Box(Modifier.fillMaxSize()) {
        // 下拉刷新沿用 FilesScreen 的 Miuix 0.9.3 PullToRefresh 用法：
        // isRefreshing 由仓库状态（REFRESHING）驱动，contentPadding 与列表顶部对齐。
        if (state.loggedOut || state.restoring) {
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("share-page")) {
                Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(PanIcons.Share, null, tint = MiuixTheme.colorScheme.primary)
                        Text(if (state.restoring) "正在恢复登录…" else "登录后管理分享", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        if (state.loggedOut) TextButton(uiText("登录"), onClick = onOpenLogin, modifier = Modifier.fillMaxWidth().testTag("share-login"))
                    }
                }
            }
        } else PullToRefresh(
            isRefreshing = state.status == ShareListStatus.REFRESHING,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp),
            refreshTexts = listOf("下拉刷新", "释放立即刷新", "正在刷新…", "刷新完成"),
        ) {
            ShareList(state, viewModel)
        }
    }
    ShareRevokeDialog(state.revokingId, viewModel)
}

@Composable
private fun ShareList(state: ShareViewModel.UiState, viewModel: ShareViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val bottomPadding = LocalContentBottomPadding.current
    LazyColumn(
        Modifier.fillMaxSize().testTag("share-page"),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "share_header") { ShareHeader(state) }
        if (state.copiedId != null) {
            item(key = "share_feedback") {
                Text(
                    uiText("链接已复制"),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().testTag("share-feedback"),
                )
            }
        }
        when {
            // LOADING 只在"一条数据都没有"时出现；已有内容时的刷新由 PullToRefresh 指示器表达。
            state.status == ShareListStatus.LOADING -> item(key = "share_loading") { ShareLoading() }
            state.status == ShareListStatus.ERROR && state.shares.isEmpty() ->
                item(key = "share_error") { ShareError(state.error.orEmpty(), onRetry = viewModel::refresh) }

            state.shares.isEmpty() -> item(key = "share_empty") { ShareEmpty() }
            else -> {
                // 此分支 status 已不可能为 LOADING（上方分支拦截），按钮显隐只看 canLoadMore。
                if (state.status == ShareListStatus.ERROR) {
                    item(key = "share_error_banner") { ShareErrorBanner(state.error.orEmpty()) }
                }
                items(state.shares, key = { it.shareId }) { share ->
                    ShareRow(
                        share = share,
                        justCopied = state.copiedId == share.shareId,
                        onCopy = viewModel::copyLink,
                        onRevoke = viewModel::requestRevoke,
                    )
                }
                if (state.canLoadMore) {
                    item(key = "share_load_more") {
                        ShareLoadMore(busy = state.status == ShareListStatus.REFRESHING, onClick = viewModel::loadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareHeader(state: ShareViewModel.UiState) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(uiText("管理我创建的分享链接"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (state.shares.isNotEmpty()) {
            Text(
                "${state.shares.size} 个分享" + if (state.status == ShareListStatus.REFRESHING) " · 正在刷新…" else "",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 分享行：名称（单行省略）+ 徽标 + 计数 + 过期时间（原样展示）+ 操作。 */
@Composable
private fun ShareRow(
    share: ShareItemDto,
    justCopied: Boolean,
    onCopy: (ShareItemDto) -> Unit,
    onRevoke: (Long) -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth().testTag("share-item_${share.shareId}"), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PanIcons.Share, null, tint = MiuixTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    share.shareName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (share.sharePwd.isNotBlank()) ShareBadge("密码保护", MiuixTheme.colorScheme.primary)
                if (share.expired) ShareBadge("已过期", MiuixTheme.colorScheme.error)
            }
            Text(
                "下载 ${share.downloadCount} · 浏览 ${share.previewCount} · 转存 ${share.saveCount}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (share.expiration.isNotBlank()) {
                // expiration 为服务端返回的 ISO 字符串，原样展示、不做时区换算。
                Text("到期：${share.expiration}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    if (justCopied) "已复制" else "复制链接",
                    onClick = { onCopy(share) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("share-copy-${share.shareId}"),
                )
                TextButton(
                    uiText("撤销"),
                    onClick = { onRevoke(share.shareId) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("share-revoke-${share.shareId}"),
                )
            }
        }
    }
}

/** 圆角小徽标：密码保护用主色、已过期用错误色，浅底深字不抢视觉。 */
@Composable
private fun ShareBadge(text: String, tint: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

@Composable
private fun ShareLoading() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Text(
        uiText("正在加载分享列表…"),
        modifier = Modifier.fillMaxWidth().testTag("share-loading").padding(vertical = 24.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun ShareEmpty() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth().testTag("share-empty"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("还没有分享链接"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                uiText("去文件页长按选择文件创建分享，创建的链接会显示在这里。"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 整页错误（无任何旧列表可展示）：文案来自仓库的用户可读信息 + 重试。 */
@Composable
private fun ShareError(message: String, onRetry: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth().testTag("share-error"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("无法加载分享"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(message.ifBlank { "网络异常，请稍后重试" }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(
                uiText("重试"),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("share-retry"),
            )
        }
    }
}

/** 有旧列表但最近一次刷新失败：保列表 + 一条细横幅。 */
@Composable
private fun ShareErrorBanner(message: String) {
    Text(
        "刷新失败：${message.ifBlank { "网络异常" }}",
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().testTag("share-error-banner"),
    )
}

@Composable
private fun ShareLoadMore(busy: Boolean, onClick: () -> Unit) {
    TextButton(
        if (busy) "加载中…" else "加载更多",
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("share-load-more"),
    )
}

/** 撤销二次确认：明示"撤销后链接立即失效"，云端文件本身不受影响。 */
@Composable
private fun ShareRevokeDialog(revokingId: Long?, viewModel: ShareViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    SuperDialog(
        show = revokingId != null,
        title = uiText("撤销分享？"),
        summary = uiText("撤销后链接立即失效，任何人无法再通过该链接访问；云端文件不受影响。"),
        onDismissRequest = viewModel::dismissRevoke,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = viewModel::dismissRevoke, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(
                uiText("撤销分享"),
                onClick = viewModel::confirmRevoke,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("share-revoke-confirm"),
            )
        }
    }
}
