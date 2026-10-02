// SPDX-License-Identifier: GPL-3.0-only
// Search, grouped cards and spring menus follow LeiFetch TransferWorkspace.
package io.github.bileizhen.pan123x.feature.files

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.WorkspaceAction
import io.github.bileizhen.pan123x.ui.util.formatBytes
import io.github.bileizhen.pan123x.ui.util.rememberUiTranslator
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FileSearchBar(search: String, onSearch: (String) -> Unit) {
    val uiText = rememberUiTranslator()
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    TextField(search, onValueChange = onSearch, label = uiText("搜索当前目录"), singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(requester).testTag("files_query"),
        trailingIcon = { if (search.isNotEmpty()) WorkspaceAction(PanIcons.Close, uiText("清除搜索"), { onSearch("") }, "files_clear_search") })
}

/** The path and view controls stay reachable when the list is scrolled. */
@Composable
internal fun DirectoryToolbar(state: FilesUiState, breadcrumbs: List<Pair<Long, String>>, onOpenAncestor: (Long) -> Unit,
    onSort: () -> Unit, onToggleGrid: () -> Unit) {
    val uiText = rememberUiTranslator()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (breadcrumbs.isNotEmpty()) {
            val scroll = rememberScrollState()
            LaunchedEffect(breadcrumbs) { scroll.animateScrollTo(scroll.maxValue) }
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                TextButton(uiText("全部文件"), onClick = { onOpenAncestor(0) }, modifier = Modifier.heightIn(min = 48.dp).testTag("breadcrumb_root"))
                breadcrumbs.forEachIndexed { index, (id, name) ->
                    Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (index == breadcrumbs.lastIndex) Text(name, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                    else TextButton(name, onClick = { onOpenAncestor(id) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().testTag("files_directory_toolbar"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(uiText(if (state.search.isBlank()) "全部文件" else "搜索结果"), fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    modifier = if (breadcrumbs.isEmpty()) Modifier.testTag("breadcrumb_root").clickable { onOpenAncestor(0) } else Modifier)
                Text(uiText("${state.files.size} 个项目"), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            WorkspaceAction(PanIcons.Sort, uiText("排序方式"), onSort, "files_sort")
            WorkspaceAction(if (state.grid) PanIcons.List else PanIcons.Grid, uiText(if (state.grid) "列表" else "网格"), onToggleGrid, "files_layout")
        }
    }
}

@Composable
internal fun SelectionBar(state: FilesUiState, onToggleAll: () -> Unit, onClose: () -> Unit) {
    val uiText = rememberUiTranslator()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("files_selection_header"), verticalAlignment = Alignment.CenterVertically) {
        Text(uiText("已选 ${state.selected.size} 项"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).testTag("files_select_count"))
        TextButton(uiText(if (state.allSelected) "全不选" else "全选"), onClick = onToggleAll, enabled = state.files.isNotEmpty() && !state.opsBusy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("files_select_all"))
        WorkspaceAction(PanIcons.Close, uiText("退出多选"), onClose, "files_select_close", !state.opsBusy)
    }
}

/** Capacity is a compact summary, rather than the dominant element of a file browser. */
@Composable
internal fun SpaceCard(state: FilesUiState, onOpenInfo: () -> Unit) {
    val uiText = rememberUiTranslator()
    val account = state.account ?: return
    Card(Modifier.fillMaxWidth().testTag("files_space_summary"), cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenInfo).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PanIcons.Account, uiText("云盘信息"), tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.displayName.ifBlank { uiText("当前账户") }, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (account.totalBytes > 0) "${formatBytes(account.usedBytes)} / ${formatBytes(account.totalBytes)}" else uiText("空间用量待刷新"),
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                }
                if (account.totalBytes > 0) LinearProgressIndicator(
                    progress = (account.usedBytes.toDouble() / account.totalBytes).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            Icon(PanIcons.Forward, null, modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

internal data class BrowserMenuAction(val title: String, val icon: ImageVector, val tag: String, val enabled: Boolean = true,
    val destructive: Boolean = false, val onClick: () -> Unit)

/** Dismiss first, then execute: menus and subsequent dialogs never overlap. */
@Composable
internal fun BrowserActionsMenu(show: Boolean, actions: List<BrowserMenuAction>, onDismiss: () -> Unit, above: Boolean = false) {
    val uiText = rememberUiTranslator()
    val visible = remember { MutableTransitionState(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(show) { if (show) pending = null; visible.targetState = show }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) { val action = pending; pending = null; action?.invoke() }
    }
    if (!visible.currentState && !visible.targetState) return
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val window = LocalWindowInfo.current.containerDpSize
    val maximumHeight = (window.height - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() -
        WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() - 16.dp).coerceAtLeast(48.dp)
    val offset = with(LocalDensity.current) { IntOffset(0, if (above) -60.dp.roundToPx() else 48.dp.roundToPx()) }
    Popup(alignment = if (above) Alignment.BottomEnd else Alignment.TopEnd, offset = offset, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visible, modifier = Modifier.padding(8.dp),
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = .9f, stiffness = 600f), initialScale = .92f,
                transformOrigin = TransformOrigin(1f, if (above) 1f else 0f)), exit = fadeOut(tween(100)) + scaleOut(tween(130), targetScale = .96f)) {
            Column(Modifier.width(minOf(220.dp, (window.width - 32.dp).coerceAtLeast(120.dp))).heightIn(max = maximumHeight)
                .shadow(8.dp, shape).clip(shape).background(colors.surfaceContainer)
                .border(.5.dp, colors.onSurface.copy(alpha = .08f), shape).verticalScroll(rememberScrollState()).padding(6.dp).testTag("files_actions_menu")) {
                actions.forEach { item ->
                    val enabled = show && item.enabled
                    val tint = (if (item.destructive) colors.error else colors.onSurface).copy(alpha = if (enabled) 1f else .4f)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) {
                        pending = item.onClick; onDismiss()
                    }.testTag(item.tag).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(uiText(item.title), fontSize = 14.sp, color = tint, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileAddButton(show: Boolean, busy: Boolean, onToggle: () -> Unit, onDismiss: () -> Unit, onUpload: () -> Unit, onCreate: () -> Unit) {
    val uiText = rememberUiTranslator()
    val colors = MiuixTheme.colorScheme
    Box {
        Row(Modifier.heightIn(min = 52.dp).shadow(4.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
            .background(colors.primary).clickable(enabled = !busy, onClick = onToggle).testTag("files_add")
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (show) PanIcons.Close else PanIcons.Add, null, tint = colors.onPrimary)
            Text(uiText("添加"), color = colors.onPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        BrowserActionsMenu(show, listOf(
            BrowserMenuAction("上传文件", PanIcons.Upload, "files_upload", !busy, onClick = onUpload),
            BrowserMenuAction("新建文件夹", PanIcons.Folder, "files_create", !busy, onClick = onCreate)), onDismiss, above = true)
    }
}

@Composable
internal fun SelectionActionBar(busy: Boolean, canAct: Boolean, canRename: Boolean, onMove: () -> Unit, onCopy: () -> Unit,
    onDownload: () -> Unit, onShare: () -> Unit, onRapid: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val uiText = rememberUiTranslator()
    var more by remember { mutableStateOf(false) }
    val enabled = canAct && !busy
    Card(modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp), cornerRadius = 28.dp, insideMargin = PaddingValues(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple("下载", PanIcons.Download, onDownload), Triple("分享", PanIcons.Share, onShare), Triple("移动", PanIcons.Folder, onMove)).forEach { (label, icon, action) ->
                BatchButton(label, icon, "files_action_${when (label) { "下载" -> "download"; "分享" -> "share"; else -> "move" }}", enabled, action, Modifier.weight(1f))
            }
            Box(Modifier.weight(1f)) {
                BatchButton("更多", PanIcons.More, "files_action_more", enabled, { more = true }, Modifier.fillMaxWidth())
                BrowserActionsMenu(more, listOf(
                    BrowserMenuAction("复制", PanIcons.Copy, "files_action_copy", enabled, onClick = onCopy),
                    BrowserMenuAction("重命名", PanIcons.Rename, "files_action_rename", canRename && !busy, onClick = onRename),
                    BrowserMenuAction("秒传", PanIcons.Transfer, "files_action_rapid", enabled, onClick = onRapid),
                    BrowserMenuAction("删除", PanIcons.Trash, "files_action_delete", enabled, true, onDelete)), { more = false }, above = true)
            }
        }
    }
}

@Composable
private fun BatchButton(title: String, icon: ImageVector, tag: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val uiText = rememberUiTranslator()
    val color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f)
    Column(modifier.clip(RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = onClick).testTag(tag).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Text(uiText(title), fontSize = 12.sp, color = color)
    }
}
