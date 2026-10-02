// In-app viewer for other people's share links: browse folders, pick files,
// save them to the signed-in account's drive, or queue downloads.
// Visual language follows ShareScreen / FilesScreen (Miuix cards, 24dp radius).
package io.github.bileizhen.pan123x.feature.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.feature.files.DirectoryPickerDialog
import io.github.bileizhen.pan123x.feature.files.PickerMode
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.PageBackHandler
import io.github.bileizhen.pan123x.ui.component.ScreenFrame
import io.github.bileizhen.pan123x.ui.util.formatBytes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 他人分享的应用内查看页：浏览目录、多选后"保存至云盘"（目录选择器定目标）
 * 或"下载"（默认保存位置）。返回优先级与文件页一致：多选 → 目录层级 → 离开页面。
 */
@Composable
fun SharedFilesScreen(
    viewModel: SharedFilesViewModel,
    directoryPickerFactory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit = {},
    onDownloadsQueued: () -> Unit = {},
    active: Boolean = true,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSavePicker by remember { mutableStateOf(false) }
    val back: () -> Unit = {
        when {
            state.selected.isNotEmpty() -> viewModel.clearSelection()
            state.trail.size > 1 -> viewModel.ancestor(state.trail.lastIndex - 1)
            else -> onBack()
        }
    }
    PageBackHandler(enabled = active && (state.selected.isNotEmpty() || state.trail.size > 1), onBack = back)
    ScreenFrame(title = "分享文件", onBack = back) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SharedTrail(state, onOpenRoot = { viewModel.ancestor(0) }, onOpenAncestor = viewModel::ancestor)
                when {
                    state.loading && state.files.isEmpty() -> SharedHint(uiText("正在加载分享文件…"), tag = "shared-files-loading")
                    state.error != null && state.files.isEmpty() -> SharedError(state.error.orEmpty(), onRetry = viewModel::refresh)
                    state.files.isEmpty() -> SharedHint(uiText("此目录没有文件"), tag = "shared-files-empty")
                    else -> SharedFileList(state, viewModel)
                }
            }
            if (state.selected.isNotEmpty()) {
                SharedActionBar(
                    busy = state.busy,
                    selected = state.selected.size,
                    onSave = { showSavePicker = true },
                    onDownload = { viewModel.download() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalContentBottomPadding.current + 12.dp),
                )
            }
            SharedFeedback(
                state = state,
                onDismiss = viewModel::dismissFeedback,
                onLogin = onOpenLogin,
                // 操作条可见时提示条上移，避免同底重叠。
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (state.selected.isNotEmpty()) LocalContentBottomPadding.current + 130.dp else 24.dp),
            )
        }
    }
    if (showSavePicker) {
        DirectoryPickerDialog(
            pickerFactory = directoryPickerFactory,
            mode = PickerMode.SAVE,
            onDismiss = { showSavePicker = false },
            onConfirm = { targetId ->
                showSavePicker = false
                viewModel.save(targetId)
            },
        )
    }
    // 下载入队成功即跳传输页（与文件页 onDownloadsQueued 行为一致）；
    // queuedRevision 是单调计数，只在真正入队时变化。
    LaunchedEffect(state.queuedRevision) { if (state.queuedRevision > 0) onDownloadsQueued() }
}

/** 面包屑：全部文件 + 各级目录名，点击截断到对应层级。 */
@Composable
private fun SharedTrail(state: SharedFilesState, onOpenRoot: () -> Unit, onOpenAncestor: (Int) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            uiText("全部文件"),
            onClick = onOpenRoot,
            modifier = Modifier.heightIn(min = 44.dp).testTag("shared-files-root"),
        )
        state.trail.drop(1).forEachIndexed { index, (_, name) ->
            Text("/", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(
                name,
                onClick = { onOpenAncestor(index + 1) },
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }
    }
}

@Composable
private fun SharedFileList(state: SharedFilesState, viewModel: SharedFilesViewModel) {
    val bottomPadding = LocalContentBottomPadding.current
    // 分享接口按 file_id 升序返回；文件夹前置更符合浏览习惯，其余保持稳定序。
    val files = remember(state.files) { state.files.sortedWith(compareBy({ !it.isFolder }, { it.fileName })) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("shared-files-list"),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomPadding + 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(files, key = { it.fileId }) { file ->
            SharedFileRow(
                file = file,
                selected = file.fileId in state.selected,
                onClick = { if (file.isFolder) viewModel.enter(file) else viewModel.toggle(file.fileId) },
                onToggle = { viewModel.toggle(file.fileId) },
            )
        }
        if (state.next != "-1") {
            item(key = "shared-files-more") {
                TextButton(
                    if (state.loading) "加载中…" else "加载更多",
                    onClick = viewModel::more,
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-files-more"),
                )
            }
        }
        item(key = "shared-files-select-all") {
            TextButton(
                if (state.selected.size == state.files.size) "取消全选" else "全选",
                onClick = viewModel::selectAll,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-files-select-all"),
            )
        }
    }
}

/** 单行：文件夹整行进入；文件整行切换选中，前部圆框表达选中态。 */
@Composable
private fun SharedFileRow(file: FileItemDto, selected: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("shared-file_${file.fileId}"), cornerRadius = 20.dp, insideMargin = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onToggle)
                    .testTag("shared-file-check_${file.fileId}"),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(PanIcons.Check, null, tint = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (file.isFolder) PanIcons.Folder else PanIcons.File, null, tint = MiuixTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(file.fileName, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!file.isFolder) {
                    Text(
                        formatBytes(file.size),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (file.isFolder) Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 20.sp)
        }
    }
}

/** 多选底部操作条：保存至云盘 + 下载，样式对齐文件页 SelectionActionBar。 */
@Composable
private fun SharedActionBar(busy: Boolean, selected: Int, onSave: () -> Unit, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), cornerRadius = 28.dp, insideMargin = PaddingValues(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple("保存至云盘", PanIcons.Copy, onSave),
                    Triple("下载", PanIcons.Download, onDownload),
                ).forEach { (label, icon, action) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                            .clickable(enabled = !busy, onClick = action)
                            .testTag("shared-files-${if (label == "下载") "download" else "save"}")
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (busy) 0.4f else 1f)
                        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(uiText(label), fontSize = 12.sp, color = tint)
                    }
                }
            }
        }
        Text(
            uiText("已选 $selected 项"),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 6.dp).testTag("shared-files-selected-count"),
        )
    }
}

/**
 * 操作结果浮层：成功/进行中 message 与失败 error 互斥；未登录时附登录入口。
 * 不遮列表主操作，仅作提示条。
 */
@Composable
private fun SharedFeedback(state: SharedFilesState, onDismiss: () -> Unit, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    val text = state.error ?: state.message ?: return
    val needsLogin = state.error == "请先登录后保存至云盘" || state.error == "请先登录后下载"
    Card(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag(if (state.error != null) "shared-files-error" else "shared-files-message"),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text,
                fontSize = 14.sp,
                color = if (state.error != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (needsLogin) {
                    TextButton("登录", onClick = onLogin, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("shared-files-login"))
                }
                if (state.error != null) {
                    TextButton("知道了", onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("shared-files-dismiss"))
                }
            }
        }
    }
}

@Composable
private fun SharedHint(text: String, tag: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun SharedError(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp).testTag("shared-files-error-page"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("无法打开分享", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(message.ifBlank { "网络异常，请稍后重试" }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton("重试", onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-files-retry"))
        }
    }
}
