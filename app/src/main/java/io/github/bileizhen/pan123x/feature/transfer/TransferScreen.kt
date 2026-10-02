// Visual structure inspired by bileizhen/LeiFetch TransferWorkspace (GPL-3.0).
// M5: real upload + download tasks unified in one workspace ; no local simulation.
package io.github.bileizhen.pan123x.feature.transfer

import androidx.compose.foundation.Canvas
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import io.github.bileizhen.pan123x.ui.component.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.data.transfer.TransferPartView
import io.github.bileizhen.pan123x.ui.component.ConfirmActionDialog
import io.github.bileizhen.pan123x.ui.component.EmptyState
import io.github.bileizhen.pan123x.ui.component.PageHeading
import io.github.bileizhen.pan123x.ui.util.formatBytes
import io.github.bileizhen.pan123x.ui.util.formatDateTime
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** UI 侧可"继续"的停止态（不含 WAITING_USER：冲突要走选择弹窗；不含 CANCELED：重试语义不明）。 */
private val RESUMABLE_STATES = setOf(
    TransferState.PAUSED,
    TransferState.WAITING_NETWORK,
    TransferState.FAILED,
)

/** 传输工作台：上传/下载共用，速度汇总为采样估算值。 */
@Composable
fun TransferScreen(viewModel: TransferViewModel, onOpenDetail: (String) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bottomPadding = LocalContentBottomPadding.current
    var revealedRow by rememberSaveable { mutableStateOf("") }
    var showActions by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.searchOpen) {
        if (!state.searchOpen) { focus.clearFocus(); keyboard?.hide() }
    }
    PageBackHandler(enabled = state.searchOpen) { viewModel.setSearchOpen(false) }
    LaunchedEffect(state.filter, state.search) { revealedRow = "" }
    ScreenFrame("传输", actions = {
        IconButton(onClick = { viewModel.setSearchOpen(!state.searchOpen) }, modifier = Modifier.size(48.dp).testTag("transfer_search_toggle")) {
            Crossfade(state.searchOpen, animationSpec = tween(150), label = "transfer-search-action") { open ->
                Icon(if (open) PanIcons.Close else PanIcons.Search, if (open) "关闭搜索" else "搜索传输任务")
            }
        }
        Box {
            WorkspaceAction(PanIcons.More, uiText("更多传输操作"), { showActions = true }, "transfer_more")
            TransferActionsMenu(showActions, state, onDismiss = { showActions = false },
                onPause = viewModel::pauseVisible, onResume = viewModel::resumeVisible, onClear = viewModel::clearFinished)
        }
    }, topBarBottom = {
        AnimatedVisibility(state.searchOpen,
            enter = expandVertically(expandFrom = Alignment.Top, animationSpec = spring(dampingRatio = .88f, stiffness = 700f)) + fadeIn(tween(150)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(100))) {
            TransferSearchBar(state.search, viewModel::setSearch)
        }
    }) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("transfer-page"),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") { TransferSummary(state) }
        item(key = "filters") { FilterRow(state, viewModel::selectFilter) }
        state.actionError?.let { error -> item(key = "error") { Text(error, color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("transfer_action_error")) } }
        when (state.status) {
            TransferContentStatus.LOADING -> item(key = "loading") { Text(uiText("正在加载任务…")) }
            TransferContentStatus.EMPTY -> item(key = "empty-${state.filter}") {
              StaggeredEntrance(0) {
                EmptyState("暂无${state.filter.label}任务", "切换分类查看其他任务，或从文件页发起传输。")
              }
            }

            TransferContentStatus.CONTENT -> itemsIndexed(state.visible, key = { _, task -> task.taskId }) { index, task ->
              Box(Modifier.animateItem()) {
               key(state.filter) {
                StaggeredEntrance(index) {
                val colors = MiuixTheme.colorScheme
                val canPause = task.state in ACTIVE_TRANSFER_STATES
                val canResume = task.state in RESUMABLE_STATES
                SwipeActionRow(
                    start = if (canPause || canResume) SwipeAction(if (canPause) "暂停" else "继续", if (canPause) PanIcons.Pause else PanIcons.Play,
                        listOf(colors.primary.copy(alpha = .6f), colors.primary)) else null,
                    end = if (task.state !in setOf(TransferState.COMPLETED, TransferState.CANCELED)) SwipeAction("取消", PanIcons.Trash, SwipeDeleteColors) else null,
                    revealed = revealedRow == task.taskId, onRevealChange = { revealedRow = if (it) task.taskId else "" }, removing = false,
                    onStart = { revealedRow = ""; if (canPause) viewModel.pause(task.taskId) else viewModel.resume(task.taskId) },
                    onEnd = { revealedRow = ""; viewModel.requestCancel(task.taskId) },
                ) {
                    TransferTaskCard(
                    task = task,
                    speed = state.speeds[task.taskId] ?: 0L,
                    onOpen = { onOpenDetail(task.taskId) },
                    onPause = { viewModel.pause(task.taskId) },
                    onResume = { viewModel.resume(task.taskId) },
                    )
                }
                }
               }
              }
            }
        }
    }
    }
    // 取消的二次确认：取消会终止传输并丢弃断点数据，必须让用户明确意图。
        ConfirmActionDialog(
            "取消任务？",
            "取消会终止传输并丢弃断点数据；云端与本地原文件不受影响。",
            "取消任务",
            viewModel::dismissCancel,
            viewModel::confirmCancel,
            show = state.cancelTaskId != null,
        )
    state.conflictTask?.let { ConflictDialog(it, viewModel) }
}

@Composable
private fun TransferSummary(state: TransferUiState) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("transfer_summary"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText("${state.tasks.size} 项 · ${state.activeCount} 进行中"), fontSize = 12.sp, color = muted,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("↓ ${formatBytes(state.downloadSpeed)}/s", fontSize = 12.sp, color = muted)
            Text("↑ ${formatBytes(state.uploadSpeed)}/s", fontSize = 12.sp, color = muted)
        }
    }
}

@Composable
private fun TransferSearchBar(search: String, onSearch: (String) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    TextField(value = search, onValueChange = onSearch, label = uiText("搜索传输任务"), singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).focusRequester(requester).testTag("transfer_search"),
        trailingIcon = { if (search.isNotEmpty()) WorkspaceAction(PanIcons.Close, uiText("清除搜索"), { onSearch("") }) })
}

@Composable
private fun FilterRow(state: TransferUiState, onSelect: (TransferFilter) -> Unit) {
    TransferFilterBar(state.filter.ordinal, TransferFilter.entries.map { filter -> state.tasks.count { it.matches(filter) } }, { onSelect(TransferFilter.entries[it]) })
}

@Composable
private fun TransferTaskCard(
    task: TransferTaskEntity,
    speed: Long,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val progress by animateFloatAsState(task.progressFraction(), tween(200), label = "transfer-progress")
    // LeiFetch TransferRow: icon tile, primary action, thin progress and a compact state footer.
    Card(Modifier.fillMaxWidth().animateContentSize(tween(200)), cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag("transfer-${task.taskId}")
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                    Icon(if (task.direction == TransferDirection.DOWNLOAD) PanIcons.Download else PanIcons.Upload, task.direction.label(), tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(task.fileName, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        "${formatBytes(task.size)} · ${uiText(task.direction.label())}",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                }
                when {
                    task.state in ACTIVE_TRANSFER_STATES ->
                        TransferPrimaryAction(PanIcons.Pause, "暂停", onPause)

                    task.state in RESUMABLE_STATES ->
                        TransferPrimaryAction(PanIcons.Play, "继续", onResume)
                    else -> TransferPrimaryAction(PanIcons.Info, "任务详情", onOpen)
                }
            }
            AnimatedVisibility(task.state in ACTIVE_TRANSFER_STATES || task.state == TransferState.PAUSED) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Crossfade(task.state, animationSpec = tween(180), label = "transfer-status") { status ->
                    Text(uiText(status.label()), fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                }
                Text(when {
                    task.state == TransferState.RUNNING -> "${(progress * 100).toInt()}% · ${formatBytes(speed)}/s"
                    task.state == TransferState.COMPLETED -> "${formatBytes(task.downloadedBytes)} · 查看详情"
                    else -> "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.size)}"
                }, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 10.dp))
            }
            AnimatedVisibility(task.state == TransferState.FAILED) {
                task.error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
private fun TransferPrimaryAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .09f))) {
        Icon(icon, label, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
}

/** 任务详情：任务卡 + 信息卡 + 统一分片点阵（[TransferPartView]）。 */
@Composable
fun TransferDetailScreen(viewModel: TransferViewModel, taskId: String) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val task = state.tasks.firstOrNull { it.taskId == taskId }
    LazyColumn(
        Modifier.fillMaxSize().testTag("transfer-detail"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageHeading("任务详情", "上传与下载共用的任务视图") }
        if (task == null) {
            item { EmptyState("任务未找到", "返回传输工作台查看其他任务。") }
        } else {
            item {
                TransferTaskCard(
                    task = task,
                    speed = state.speeds[task.taskId] ?: 0L,
                    onOpen = {},
                    onPause = { viewModel.pause(task.taskId) },
                    onResume = { viewModel.resume(task.taskId) },
                )
            }
            item { InfoCard(task, state.speeds[task.taskId] ?: 0L, viewModel) }
            item {
                // observeParts 按 (accountId, taskId) 订阅：accountId 从任务行取，VM 不依赖 AccountManager。
                val parts by remember(task.accountId, task.taskId) {
                    viewModel.observeParts(task.accountId, task.taskId)
                }.collectAsStateWithLifecycle(initialValue = emptyList())
                PartsCard(task.direction, parts)
            }
            if (task.state != TransferState.COMPLETED && task.state != TransferState.CANCELED) {
                item {
                    TextButton(
                        uiText("取消任务"),
                        onClick = { viewModel.requestCancel(task.taskId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
        ConfirmActionDialog(
            "取消任务？",
            "取消会终止传输并丢弃断点数据；云端与本地原文件不受影响。",
            "取消任务",
            viewModel::dismissCancel,
            viewModel::confirmCancel,
            show = state.cancelTaskId != null,
        )
}

@Composable
private fun InfoCard(task: TransferTaskEntity, speed: Long, viewModel: TransferViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DetailLine("任务类型", task.direction.label())
            DetailLine("当前状态", task.state.label())
            if (task.state == TransferState.RUNNING && speed > 0) DetailLine("估算速度", "${formatBytes(speed)}/s")
            DetailLine("已传输", "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.size)}")
            DetailLine("创建时间", formatDateTime(task.createTime).ifEmpty { "未知" })
            DetailLine("目标位置", destinationLabel(task))
            if (task.isConflict) {
                Text(
                    "云盘中已存在同名文件「${task.fileName}」，请选择处理方式。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        uiText("保留两者"),
                        onClick = { viewModel.resolveConflictKeepBoth(task.taskId) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                    TextButton(
                        uiText("覆盖"),
                        onClick = { viewModel.resolveConflictOverwrite(task.taskId) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PartsCard(direction: TransferDirection, parts: List<TransferPartView>) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val primary = MiuixTheme.colorScheme.primary
    val partial = primary.copy(alpha = 0.45f)
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
    val columns = 16
    val rows = ((parts.size + columns - 1) / columns).coerceAtLeast(1)
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                if (direction == TransferDirection.DOWNLOAD) "分段进度" else "分片进度",
                fontWeight = FontWeight.Bold,
            )
            if (parts.isEmpty()) {
                Text(
                    uiText("任务开始后这里会显示实时进度。"),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            } else {
                // 分片点阵：沿用 M4 下载详情的点阵思路，上传/下载统一消费 TransferPartView。
                Canvas(Modifier.fillMaxWidth().aspectRatio(columns.toFloat() / rows)) {
                    val gap = 5.dp.toPx()
                    val side = (size.width - (columns - 1) * gap) / columns
                    parts.forEachIndexed { position, part ->
                        val x = (position % columns) * (side + gap)
                        val y = (position / columns) * (side + gap)
                        val color = when {
                            part.done -> primary
                            part.transferred > 0 -> partial
                            else -> muted
                        }
                        drawRoundRect(color, Offset(x, y), Size(side, side), CornerRadius(3.dp.toPx()))
                    }
                }
                Text(
                    "${parts.count { it.done }} / ${parts.size} " +
                        if (direction == TransferDirection.DOWNLOAD) "个分段完成 · 浅色为进行中" else "个分片完成 · 浅色为进行中",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ConflictDialog(task: TransferTaskEntity, viewModel: TransferViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Dialog(onDismissRequest = viewModel::dismissConflict) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 28.dp, insideMargin = PaddingValues(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(uiText("存在同名文件"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "云盘中已存在与「${task.fileName}」同名的文件，请选择处理方式。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        uiText("保留两者"),
                        onClick = { viewModel.resolveConflictKeepBoth(task.taskId) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                    TextButton(
                        uiText("覆盖"),
                        onClick = { viewModel.resolveConflictOverwrite(task.taskId) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
                TextButton(uiText("取消"), onClick = viewModel::dismissConflict, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        Text(value, fontSize = 15.sp)
    }
}

private fun TransferTaskEntity.progressFraction(): Float = when {
    size > 0 -> (downloadedBytes.toDouble() / size).toFloat().coerceIn(0f, 1f)
    state == TransferState.COMPLETED -> 1f
    else -> 0f
}

private fun destinationLabel(task: TransferTaskEntity): String = when (task.direction) {
    TransferDirection.DOWNLOAD -> if (task.destinationTree.isBlank()) "应用内下载目录" else "用户选择的下载目录"
    TransferDirection.UPLOAD -> if (task.parentFileId > 0) "云盘目录（id=${task.parentFileId}）" else "云盘根目录"
}
