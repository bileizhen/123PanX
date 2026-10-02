package io.github.bileizhen.pan123x.feature.recycle

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.ui.util.formatBytes
import io.github.bileizhen.pan123x.ui.util.formatDateTime
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.component.PageBackHandler
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Icon
import io.github.bileizhen.pan123x.ui.component.PanIcons

/**
 * 回收站页（M3 配套）：恢复 / 永久删除，照 ShareScreen / FilesScreen 的状态分支骨架。
 * - 普通态条目：名称、大小 / 删除时间（updateAt 优先，缺失回退 createAt）、原位置；
 *   协议只回 parentFileId，目录名反查不做额外请求，M7 完善为目录名。
 * - 多选：长按进入→ 底部操作"恢复 / 永久删除"。
 * - 永久删除双重确认：第二层明示"不可恢复"，确认键红色语义、文字为"永久删除"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecycleScreen(viewModel: RecycleViewModel, onOpenLogin: () -> Unit = {}) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PageBackHandler(enabled = state.selectMode) { viewModel.exitSelectMode() }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    // M7 清空回收站：全部永久删除（与永久删除同级不可恢复操作，双重确认）。
    var confirmClearAll by rememberSaveable { mutableStateOf(false) }
    // 永久删除确认步骤：0 无 / 1 第一层 / 2 最终确认（双重确认）。
    var deleteStep by rememberSaveable { mutableStateOf(0) }

    // 进入页面（或会话从恢复中转为就绪）时按需自动拉取一次。
    LaunchedEffect(state.restoring, state.loggedOut) { viewModel.refreshIfNeeded() }
    // 操作结果等提示只作短期反馈，3 秒后自动清掉。
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3_000)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefresh(isRefreshing = state.loading && !state.empty, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize(),
            refreshTexts = listOf("下拉刷新", "释放立即刷新", "正在刷新…", "刷新完成")) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("recycle_page"),
            contentPadding = PaddingValues(
                start = 16.dp, top = 8.dp, end = 16.dp,
                bottom = if (state.selectMode) 112.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { RecycleHeader(state, onRefresh = viewModel::refresh, onToggleSelectAll = viewModel::toggleSelectAll, onExit = viewModel::exitSelectMode, onClearAll = { confirmClearAll = true }) }
            when {
                state.loggedOut -> item { RecycleLoggedOut(onOpenLogin) }
                state.restoring -> item { RecycleHint("正在恢复登录…", Modifier.testTag("recycle_restoring")) }
                state.error != null && state.empty -> item { RecycleError(state.error.orEmpty(), onRetry = viewModel::refresh) }
                state.empty && state.loading -> item { RecycleHint("正在加载回收站…", Modifier.testTag("recycle_loading")) }
                state.empty -> item { RecycleEmpty() }
                else -> items(state.items, key = { it.fileId }) { file ->
                    RecycleItem(
                        file = file,
                        selectMode = state.selectMode,
                        selected = file.fileId in state.selected,
                        enabled = !state.busy,
                        onClick = { if (state.selectMode) viewModel.toggleSelect(file.fileId) },
                        onLongClick = { if (!state.selectMode) viewModel.enterSelectMode(file.fileId) },
                    )
                }
            }
        }
        }
        if (state.selectMode) {
            RecycleActionBar(
                state = state,
                onRestore = { confirmRestore = true },
                onDelete = { deleteStep = 1 },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }

        // 清空回收站（M7）：全部永久删除，双重确认 + 数量明示。
    SuperDialog(
        show = confirmClearAll,
        title = "清空回收站（${state.items.size} 个项目）？",
        summary = uiText("此操作不可恢复。全部项目将被彻底删除，无法撤销。"),
        onDismissRequest = { confirmClearAll = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { confirmClearAll = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(
                uiText("清空"),
                onClick = { confirmClearAll = false; viewModel.clearAll() },
                enabled = !state.busy && state.items.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("recycle_clear_confirm"),
            )
        }
    }
// 恢复：非破坏性，单层确认避免误触批量操作。
    SuperDialog(
        show = confirmRestore,
        title = "恢复 ${state.selected.size} 个项目？",
        summary = uiText("项目将恢复到删除前的原位置。"),
        onDismissRequest = { confirmRestore = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { confirmRestore = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(
                uiText("恢复"),
                onClick = { confirmRestore = false; viewModel.restoreSelected() },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("recycle_restore_confirm"),
            )
        }
    }

    // 永久删除第一层：告知后果。
    SuperDialog(
        show = deleteStep == 1,
        title = "永久删除 ${state.selected.size} 个项目？",
        summary = uiText("项目将从回收站移除，无法再通过回收站找回。"),
        onDismissRequest = { deleteStep = 0 },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { deleteStep = 0 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(uiText("继续"), onClick = { deleteStep = 2 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        }
    }

    // 永久删除第二层（最终确认）：明示"不可恢复"，确认键红色语义 + "永久删除"文字。
    SuperDialog(
        show = deleteStep == 2,
        title = uiText("确认永久删除？"),
        summary = "此操作不可恢复。${state.selected.size} 个项目将被彻底删除，无法撤销。",
        onDismissRequest = { deleteStep = 0 },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                uiText("永久删除后无法再还原，请确认不再需要这些项目。"),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(uiText("取消"), onClick = { deleteStep = 0 }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                TextButton(
                    uiText("永久删除"),
                    onClick = { deleteStep = 0; viewModel.deleteForeverSelected() },
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error.copy(alpha = 0.12f),
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("recycle_delete_forever_confirm"),
                )
            }
        }
    }
}

/** 工具行（刷新 / 多选控制）+ 项目计数 + 短期反馈与"有缓存但刷新失败"细提示。 */
@Composable
private fun RecycleHeader(
    state: RecycleUiState,
    onRefresh: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    onClearAll: () -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                if (state.loading) "刷新中…" else "刷新",
                onClick = onRefresh,
                enabled = !state.loading && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_refresh"),
            )
            // 非多选态直供"清空"入口（多选态由全选+永久删除覆盖，避免操作歧义）。
            if (!state.selectMode) {
                TextButton(
                    uiText("清空"),
                    onClick = onClearAll,
                    enabled = !state.busy && state.items.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_clear"),
                )
            }
            if (state.selectMode) {
                TextButton(
                    if (state.selected.size >= state.items.size && state.selected.isNotEmpty()) "取消全选" else "全选",
                    onClick = onToggleSelectAll,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_select_all"),
                )
                TextButton(uiText("退出多选"), onClick = onExit, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_exit_select"))
            }
        }
        Text(
            "${state.items.size} 个项目" + if (state.loading) " · 正在刷新…" else "",
            fontSize = 12.sp,
            color = summary,
        )
        state.message?.let { message ->
            Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.testTag("recycle_feedback"))
        }
        // 有旧列表但最近一次刷新失败：保留列表 + 一条细提示（与文件页同策略）。
        if (state.error != null && !state.empty) {
            Text("刷新失败：${state.error}", fontSize = 12.sp, color = summary, modifier = Modifier.testTag("recycle_offline_banner"))
        }
    }
}

/** 多选时的底部操作条：恢复 / 永久删除。 */
@Composable
private fun RecycleActionBar(state: RecycleUiState, onRestore: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 ${state.selected.size} 项",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                if (state.busy) "处理中…" else "恢复",
                onClick = onRestore,
                enabled = !state.busy && state.selected.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_restore"),
            )
            TextButton(
                uiText("永久删除"),
                onClick = onDelete,
                enabled = !state.busy && state.selected.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_delete_forever"),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleItem(
    file: CloudFileEntity,
    selectMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val date = formatDateTime(if (file.updateAt > 0) file.updateAt else file.createAt)
    val metadata = if (file.isFolder) {
        listOfNotNull("文件夹", date.ifBlank { null }).joinToString(" · ")
    } else {
        listOfNotNull(formatBytes(file.size), date.ifBlank { null }).joinToString(" · ")
    }
    Card(
        Modifier.fillMaxWidth()
            .testTag("recycle_item_${file.fileId}")
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        cornerRadius = 22.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (selectMode) SelectBadge(selected)
            Icon(if (file.isFolder) PanIcons.Folder else PanIcons.File, null, tint = MiuixTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(file.fileName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(metadata, fontSize = 12.sp, color = summary)
                Text("原位置：${originalLocation(file.parentFileId)}", fontSize = 12.sp, color = summary)
            }
        }
    }
}

/** 多选徽标：选中实心圆打勾，未选中浅色空心圆。 */
@Composable
private fun SelectBadge(selected: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * 原位置展示：协议只回 parentFileId，客户端不做目录名反查（避免逐项额外请求），
 * 非根目录暂显示 ID，M7 结合目录缓存 / 接口完善为目录名。
 */
private fun originalLocation(parentFileId: Long): String =
    if (parentFileId == 0L) "根目录" else "ID $parentFileId"

@Composable
private fun RecycleHint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth().padding(24.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun RecycleLoggedOut(onOpenLogin: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth().testTag("recycle_logged_out"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("登录后查看回收站"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(uiText("登录 123 云盘账户后，即可在这里恢复或彻底删除已删除的文件。"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(uiText("去登录"), onClick = onOpenLogin, modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_login_entry"))
        }
    }
}

@Composable
private fun RecycleError(error: String, onRetry: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth().testTag("recycle_error"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("无法加载回收站"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(error, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(uiText("重试"), onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("recycle_retry"))
        }
    }
}

@Composable
private fun RecycleEmpty() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth().testTag("recycle_empty"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("回收站为空"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(uiText("已删除的文件会先进入回收站，可在这里恢复或彻底删除。"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
