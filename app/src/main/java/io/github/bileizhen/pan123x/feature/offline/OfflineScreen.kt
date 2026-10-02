// 离线下载 / 秒传导入页。
// 视觉沿用 bileizhen/LeiFetch TransferWorkspace（GPL-3.0）的 Miuix 卡片语言与 TransferScreen 的布局先例。
package io.github.bileizhen.pan123x.feature.offline
import io.github.bileizhen.pan123x.ui.component.WorkspaceTabs
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.network.OfflineResolvedFile
import io.github.bileizhen.pan123x.core.network.OfflineResolvedItem
import io.github.bileizhen.pan123x.data.transfer.RapidReport
import io.github.bileizhen.pan123x.feature.files.DirectoryPickerDialog
import io.github.bileizhen.pan123x.ui.component.EmptyState
import io.github.bileizhen.pan123x.ui.component.PageHeading
import io.github.bileizhen.pan123x.feature.files.PickerMode
import io.github.bileizhen.pan123x.ui.util.formatBytes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 页内 Tab 下标：0 = 离线下载，1 = 秒传导入（rememberSaveable 持有，两个 Tab 状态互不干扰）。 */
private const val TAB_OFFLINE = 0
private const val TAB_RAPID = 1

/** 单个资源卡最多展开展示的文件清单行数；超出仅提示，「全选 / 全不选」仍对全部文件生效。 */
private const val MAX_VISIBLE_FILES = 50

/** 秒传预览最多列出的 path 条数，其余折叠为计数行。 */
private const val MAX_PREVIEW_PATHS = 8

/**
 * 离线下载 / 秒传导入页：单页两 Tab。
 *
 * 离线 Tab：多行输入 → 解析 → 资源卡（名称 / 大小 / 类型徽标 / 失败原因 / 文件清单勾选默认全选）
 * → 提交任务 → 结果提示（数秒后自动清空回 Idle）。秒传 Tab：粘贴内容 → 解析预览
 * （条数 / 总大小 / path 摘要）→ 选择目标目录（复用 [DirectoryPickerDialog]）→ 开始导入
 * → 进度 + 取消 → 完成汇总（失败明细可展开）。
 */
@Composable
fun OfflineScreen(
    offline: OfflineViewModel,
    rapid: RapidImportViewModel,
    pickerFactory: ViewModelProvider.Factory,
) {
    var tab by rememberSaveable { mutableStateOf(TAB_OFFLINE) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val offlineState by offline.uiState.collectAsStateWithLifecycle()
    val rapidState by rapid.uiState.collectAsStateWithLifecycle()
    val bottomPadding = LocalContentBottomPadding.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("offline-page"),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "heading") {
            PageHeading("离线下载", "解析离线链接提交任务，或粘贴秒传内容导入云盘")
        }
        item(key = "tabs") { OfflineTabRow(current = tab, onSelect = { tab = it }) }
        if (tab == TAB_OFFLINE) {
            offlineContent(offlineState, offline)
        } else {
            rapidContent(rapidState, rapid, onRequestPicker = { showPicker = true })
        }
    }
    if (showPicker) {
        DirectoryPickerDialog(
            pickerFactory = pickerFactory,
            mode = PickerMode.IMPORT,
            onDismiss = { showPicker = false },
            onConfirm = { dirId ->
                rapid.setTargetDirectory(dirId)
                showPicker = false
            },
        )
    }
}

@Composable
private fun OfflineTabRow(current: Int, onSelect: (Int) -> Unit) {
    WorkspaceTabs(listOf("离线下载", "秒传导入"), current, onSelect, listOf("offline_tab_dl", "offline_tab_rapid"))
}

// ---- 离线 Tab ----

private fun LazyListScope.offlineContent(state: OfflineUiState, viewModel: OfflineViewModel) {
    item(key = "offline_input") { OfflineInputCard(state, viewModel) }
    when {
        state.resolving -> item(key = "offline_resolving") {
            Text(
                "正在解析链接…",
                modifier = Modifier.fillMaxWidth().testTag("offline_resolving").padding(vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        state.items.isEmpty() -> item(key = "offline_empty") {
            EmptyState("尚未解析资源", "粘贴离线链接（HTTP/HTTPS/磁力/迅雷，每行一个）后点击「解析」。")
        }

        else -> itemsIndexed(state.items, key = { index, item -> "resolved_${index}_${item.resourceId}" }) { _, item ->
            ResolvedResourceCard(state, item, viewModel)
        }
    }
    item(key = "offline_footer") { OfflineFooterCard(state, viewModel) }
}

@Composable
private fun OfflineInputCard(state: OfflineUiState, viewModel: OfflineViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("离线链接"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            TextField(
                value = state.input,
                onValueChange = viewModel::updateInput,
                label = uiText("每行一个链接，支持 HTTP/HTTPS/磁力/迅雷"),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("offline_input"),
            )
            state.notice?.let {
                Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("offline_notice"))
            }
            state.error?.let {
                Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("offline_error"))
            }
            TextButton(
                if (state.resolving) "解析中…" else "解析",
                onClick = viewModel::resolve,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("offline_resolve"),
            )
        }
    }
}

@Composable
private fun ResolvedResourceCard(state: OfflineUiState, item: OfflineResolvedItem, viewModel: OfflineViewModel) {
    Card(
        Modifier.fillMaxWidth().testTag("offline_card_${item.resourceId}"),
        cornerRadius = 22.dp,
        insideMargin = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        item.name.ifBlank { item.url },
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${offlineTypeLabel(item.type)} · ${formatBytes(item.size)}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                TypeBadge(offlineTypeLabel(item.type))
            }
            if (!item.ok) {
                // 解析失败的资源：红字原因，不可勾选 / 不随提交上送。
                Text(
                    item.errMessage.ifBlank { "解析失败" },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                )
            } else if (item.files.size > 1) {
                FileCheckList(state, item, viewModel)
            }
        }
    }
}

/** 文件清单勾选列表（fileNums > 1 时展开，默认全选）。 */
@Composable
private fun FileCheckList(state: OfflineUiState, item: OfflineResolvedItem, viewModel: OfflineViewModel) {
    val selected = state.selection[item.resourceId] ?: emptySet()
    val allSelected = selected.size >= item.files.size
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "包含 ${item.files.size} 个文件，已选 ${selected.size} 个（默认全选）",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                if (allSelected) "全不选" else "全选",
                onClick = { viewModel.setAllFiles(item.resourceId, !allSelected) },
                modifier = Modifier.heightIn(min = 44.dp).testTag("offline_file_all_${item.resourceId}"),
            )
        }
        // 大清单裁剪展示；勾选状态与「全选 / 全不选」仍对全部文件生效。
        item.files.take(MAX_VISIBLE_FILES).forEach { file ->
            FileCheckRow(item.resourceId, file, file.fileId in selected, viewModel::toggleFile)
        }
        if (item.files.size > MAX_VISIBLE_FILES) {
            Text(
                "仅展示前 $MAX_VISIBLE_FILES 个文件；「全选 / 全不选」对全部文件生效。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun FileCheckRow(
    resourceId: Long,
    file: OfflineResolvedFile,
    checked: Boolean,
    onToggle: (Long, Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle(resourceId, file.fileId) }
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (checked) "✓" else "○",
            color = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
        Text(
            file.name,
            modifier = Modifier.weight(1f).testTag("offline_file_${file.fileId}"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
        )
        Text(
            formatBytes(file.size),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun OfflineFooterCard(state: OfflineUiState, viewModel: OfflineViewModel) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                offlineSubmissionSummary(state),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                if (state.submitting) "提交中…" else "提交任务",
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("offline_submit"),
            )
            state.resultMessage?.let {
                Text(it, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().testTag("offline_result"))
            }
        }
    }
}

@Composable
private fun TypeBadge(label: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

/**
 * resolve data.type 的展示映射（C 侧假设，合并时与 B/协议核对：0=HTTP/HTTPS、1=磁力、2=迅雷；
 * 参考源对 type 透传不解析，未知值统一显示「链接」）。
 */
private fun offlineTypeLabel(type: Int): String = when (type) {
    0 -> "HTTP"
    1 -> "磁力"
    2 -> "迅雷"
    else -> "链接"
}

private fun offlineSubmissionSummary(state: OfflineUiState): String {
    val valid = state.items.count { item ->
        item.ok && (item.files.isEmpty() || state.selection[item.resourceId]?.isNotEmpty() == true)
    }
    return if (valid == 0) "解析结果中没有可提交的资源" else "将提交 $valid 个有效资源；部分勾选只提交勾选的文件"
}

// ---- 秒传 Tab ----

private fun LazyListScope.rapidContent(
    state: RapidUiState,
    viewModel: RapidImportViewModel,
    onRequestPicker: () -> Unit,
) {
    item(key = "rapid_input") { RapidInputCard(state, viewModel) }
    item(key = "rapid_preview") { RapidPreviewCard(state) }
    item(key = "rapid_action") { RapidActionCard(state, viewModel, onRequestPicker) }
    state.report?.let { report ->
        item(key = "rapid_report") { RapidReportCard(report, onReset = viewModel::reset) }
    }
}

@Composable
private fun RapidInputCard(state: RapidUiState, viewModel: RapidImportViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("秒传内容"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            TextField(
                value = state.input,
                onValueChange = viewModel::updateInput,
                label = uiText("粘贴秒传 JSON 或 123FLCPV2 链接"),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("rapid_input"),
            )
            state.parseError?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.testTag("rapid_parse_error"),
                )
            }
            TextButton(
                uiText("解析预览"),
                onClick = viewModel::parse,
                enabled = !state.importing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rapid_parse"),
            )
        }
    }
}

@Composable
private fun RapidPreviewCard(state: RapidUiState) {
    if (state.files.isEmpty()) {
        if (state.parseError == null) {
            EmptyState("尚未解析秒传内容", "粘贴秒传 JSON 或 123FLCPV2 链接后点击「解析预览」。")
        }
        return
    }
    Card(Modifier.fillMaxWidth().testTag("rapid_preview"), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "共 ${state.files.size} 个文件 · 总大小 ${formatBytes(state.totalSize)}",
                fontWeight = FontWeight.Medium,
            )
            state.files.take(MAX_PREVIEW_PATHS).forEach { file ->
                Text(
                    file.path,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (state.files.size > MAX_PREVIEW_PATHS) {
                Text(
                    "…其余 ${state.files.size - MAX_PREVIEW_PATHS} 个文件",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun RapidActionCard(
    state: RapidUiState,
    viewModel: RapidImportViewModel,
    onRequestPicker: () -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(uiText("目标目录"), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(if (state.parentDirId == 0L) "根目录" else "目录 id=${state.parentDirId}", fontSize = 15.sp)
                }
                TextButton(
                    uiText("选择目标目录"),
                    onClick = onRequestPicker,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("rapid_pick_dir"),
                )
            }
            if (state.importing) {
                val progress = state.progress
                val fraction = if (progress == null || progress.total <= 0) {
                    0f
                } else {
                    (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                }
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().height(5.dp).testTag("rapid_progress"),
                )
                Text(
                    "${progress?.done ?: 0} / ${progress?.total ?: state.files.size} 个文件",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                progress?.currentPath?.takeIf { it.isNotBlank() }?.let { path ->
                    Text(
                        path,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().testTag("rapid_current_path"),
                    )
                }
                TextButton(
                    if (state.cancelRequested) "正在取消…" else "取消",
                    onClick = viewModel::requestCancel,
                    enabled = !state.cancelRequested,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rapid_cancel"),
                )
            } else {
                TextButton(
                    uiText("开始导入"),
                    onClick = viewModel::startImport,
                    enabled = state.files.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rapid_import"),
                )
            }
        }
    }
}

/** 完成汇总：成功 N / 失败 M，失败明细（path + 原因）可展开。 */
@Composable
private fun RapidReportCard(report: RapidReport, onReset: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("rapid_report"), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "导入完成：成功 ${report.success.size} 个，失败 ${report.failed.size} 个",
                fontWeight = FontWeight.Medium,
            )
            if (report.failed.isNotEmpty()) {
                TextButton(
                    if (expanded) "收起失败明细" else "展开失败明细（${report.failed.size}）",
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("rapid_report_toggle"),
                )
                if (expanded) {
                    report.failed.forEach { (path, reason) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(path, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(reason, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                        }
                    }
                }
            }
            TextButton(
                uiText("重新开始"),
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("rapid_reset"),
            )
        }
    }
}
