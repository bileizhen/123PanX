// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.data.diagnostics.DiagnosticsSnapshot
import io.github.bileizhen.pan123x.ui.component.ConfirmActionDialog
import io.github.bileizhen.pan123x.ui.component.EmptyState
import io.github.bileizhen.pan123x.ui.component.PageHeading
import io.github.bileizhen.pan123x.feature.transfer.label
import io.github.bileizhen.pan123x.ui.util.formatBytes
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志只读区的限高：足够看末尾几十条，又不把整页撑成长滚动块。 */
private val LOG_TAIL_MAX_HEIGHT = 240.dp

/** 一次性提示的停留时长；到期由 VM 清除，避免"已释放 X"常驻页面。 */
private const val MESSAGE_TIMEOUT_MS = 2_500L

/**
 * 诊断页（的 Miuix 分组语言）：环境卡 / 存储卡 /
 * 传输统计卡 / 日志卡。进入页面由 ViewModel 自动 snapshot 一次，「刷新」按钮重取。
 *
 * [copy] 由宿主注入（PanXApp 的 ClipboardManager 回调，ShareScreen 同款接缝），
 * 屏幕自身不碰系统服务。
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, copy: (String) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("diagnostics_screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeading("诊断", "环境信息、缓存占用与最近日志") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    uiText("刷新"),
                    onClick = viewModel::refresh,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("diag_refresh"),
                )
            }
        }
        when {
            snapshot == null && state.loading -> item { Text("正在加载诊断信息…") }
            snapshot == null -> item {
                EmptyState("暂无诊断信息", "加载失败或数据不可用，点击「刷新」重新加载。")
            }

            else -> {
                item { EnvironmentCard(snapshot) }
                item {
                    StorageCard(
                        snapshot = snapshot,
                        clearing = state.clearing,
                        onClear = { confirmClear = true },
                    )
                }
                item { TransferStatsCard(snapshot.taskCounts) }
                item {
                    val logText = snapshot.logTail.joinToString("\n")
                    LogsCard(
                        logCount = snapshot.logTail.size,
                        logText = logText,
                        onCopy = { copy(logText) },
                    )
                }
            }
        }
        state.message?.let { message ->
            item {
                Text(
                    message,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().testTag("diag_message"),
                )
            }
        }
    }
    // 一次性提示自动消失：内容变化重启计时，避免吞掉紧随其后的新提示。
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(MESSAGE_TIMEOUT_MS)
            viewModel.consumeMessage()
        }
    }
    // 清除预览缓存的二次确认（删除类操作必须二次确认）。
        ConfirmActionDialog(
            "清除预览缓存？",
            "删除 PDF 预览等临时缓存文件，不影响已下载文件与云盘数据；再次预览时会重新生成。",
            "清除",
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearPreviewCache()
            },
            show = confirmClear,
        )
}

/** 环境卡：应用版本 / 系统版本（API 级别）/ 设备型号。 */
@Composable
private fun EnvironmentCard(snapshot: DiagnosticsSnapshot) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(uiText("环境"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            DetailLine("版本", snapshot.appVersion)
            DetailLine("系统版本", "API ${snapshot.sdkInt}")
            DetailLine("设备", snapshot.deviceModel)
        }
    }
}

/** 存储卡：缓存总量 / 预览缓存 + 「清除预览缓存」（确认后执行，释放量以一次性提示反馈）。 */
@Composable
private fun StorageCard(snapshot: DiagnosticsSnapshot, clearing: Boolean, onClear: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(uiText("存储"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            DetailLine("缓存总大小", formatBytes(snapshot.cacheBytes))
            DetailLine("预览缓存", formatBytes(snapshot.previewCacheBytes))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    if (clearing) "清除中…" else "清除预览缓存",
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("diag_clear_cache"),
                )
            }
        }
    }
}

/**
 * 传输统计卡：各状态任务计数。
 *
 * 计数口径见 [io.github.bileizhen.pan123x.data.diagnostics.RoomDiagnosticsSource]（仅活跃态，
 * 停止态不进 map）：缺失状态按 0 展示，保证十个状态完整可见。中文文案复用传输工作台的
 * `TransferState.label`（单一来源，不另写映射）。
 */
@Composable
private fun TransferStatsCard(taskCounts: Map<String, Int>) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(uiText("传输统计"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            TransferState.entries.forEach { status ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(status.label(), modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Text(
                        (taskCounts[status.name] ?: 0).toString(),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

/** 日志卡：末尾日志只读区（等宽小字、可滚动、限高）+ 「复制日志」（复制经注入回调）。 */
@Composable
private fun LogsCard(logCount: Int, logText: String, onCopy: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("日志（末尾 $logCount 条）", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(
                    uiText("复制日志"),
                    onClick = onCopy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("diag_copy_logs"),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = LOG_TAIL_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (logText.isEmpty()) {
                    Text(uiText("暂无日志"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                } else {
                    Text(
                        logText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(
                uiText("仅内存保存约 600 条，已自动脱敏；退出应用即清空。"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DetailLine(labelText: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(labelText, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        Text(value, fontSize = 15.sp)
    }
}
