package io.github.bileizhen.pan123x.feature.share

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.SharedInfoDto
import io.github.bileizhen.pan123x.feature.files.DirectoryPickerDialog
import io.github.bileizhen.pan123x.feature.files.PickerMode
import io.github.bileizhen.pan123x.ui.component.*
import io.github.bileizhen.pan123x.ui.util.formatBytes
import io.github.bileizhen.pan123x.ui.util.formatDateTime
import java.time.Instant
import java.time.ZoneId
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SharedFilesScreen(
    viewModel: SharedFilesViewModel,
    directoryPickerFactory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit = {},
    onDownloadsQueued: () -> Unit = {},
    active: Boolean = true,
    loggedIn: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var showSavePicker by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var passwordDraft by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<FileItemDto?>(null) }
    val back: () -> Unit = {
        if (!state.busy) when {
            state.selected.isNotEmpty() -> viewModel.clearSelection()
            state.trail.size > 1 -> viewModel.ancestor(state.trail.lastIndex - 1)
            else -> onBack()
        }
    }
    PageBackHandler(enabled = active && (state.busy || state.selected.isNotEmpty() || state.trail.size > 1), onBack = back)
    ScreenFrame(title = "分享文件", onBack = back, actions = {
        IconButton(onClick = { showInfo = true }, modifier = Modifier.size(48.dp).testTag("shared-files-info")) {
            Icon(PanIcons.Info, "分享信息")
        }
    }) {
        val openPassword = { passwordDraft = state.password; showPassword = true }
        val click: (FileItemDto) -> Unit = { file ->
            if (!state.busy) when {
                state.selected.isNotEmpty() -> viewModel.toggle(file.fileId)
                file.isFolder && file.available && state.info?.expired != true -> viewModel.enter(file)
                else -> detail = file
            }
        }
        AnimatedContent(targetState = state.grid, modifier = Modifier.weight(1f), label = "shared-file-layout") { grid ->
            LazyColumn(Modifier.fillMaxSize().testTag("shared-files-list"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("summary") { ShareSummary(state, { showInfo = true }, viewModel::refreshInfo) }
                item("search") {
                    TextField(state.search, viewModel::search, label = "搜索当前目录", singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("shared-files-search"))
                }
                stickyHeader("toolbar") {
                    Column(Modifier.background(MiuixTheme.colorScheme.surface)) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            state.trail.forEachIndexed { index, (_, name) ->
                                if (index > 0) Text(" › ", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 4.dp))
                                Text(name, fontSize = 14.sp, fontWeight = if (index == state.trail.lastIndex) FontWeight.Medium else FontWeight.Normal,
                                    color = if (index == state.trail.lastIndex) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.primary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.heightIn(min = 44.dp).widthIn(max = 180.dp)
                                        .clickable(enabled = !state.busy && index < state.trail.lastIndex) { viewModel.ancestor(index) }
                                        .wrapContentHeight(Alignment.CenterVertically)
                                        .then(if (index == 0) Modifier.testTag("shared-files-root") else Modifier))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(fileCount(state), fontSize = 12.sp, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextButton(if (state.selected.isNotEmpty()) "取消选择" else "全选", onClick = {
                                if (state.selected.isNotEmpty()) viewModel.clearSelection() else viewModel.selectAll()
                            }, enabled = !state.busy && state.info?.expired != true && state.visibleFiles.any { it.available },
                                modifier = Modifier.testTag("shared-files-select-all").heightIn(min = 44.dp))
                            IconButton(onClick = { if (!state.busy) showSort = true }, modifier = Modifier.size(44.dp).testTag("shared-files-sort")) {
                                Icon(PanIcons.Sort, "排序：${state.sort.label}，${if (state.ascending) "升序" else "降序"}")
                            }
                            IconButton(onClick = viewModel::toggleLayout, modifier = Modifier.size(44.dp).testTag("shared-files-layout")) {
                                Icon(if (grid) PanIcons.List else PanIcons.Grid, if (grid) "列表视图" else "网格视图")
                            }
                        }
                    }
                }
                if (state.loading && state.files.isEmpty()) item("loading") { Hint("正在加载分享文件…", "shared-files-loading") }
                else if (state.error != null && state.files.isEmpty()) item("error") {
                    Card(Modifier.fillMaxWidth().testTag("shared-files-error-page"), insideMargin = PaddingValues(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("无法打开分享", fontWeight = FontWeight.Medium)
                            Text(state.error.orEmpty(), fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton("重试", onClick = viewModel::refresh, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("shared-files-retry"))
                                TextButton("修改提取码", onClick = openPassword, enabled = !state.busy, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                else if (state.visibleFiles.isEmpty()) item("empty") {
                    Hint(if (state.search.isBlank()) "此目录没有文件" else "已加载的文件中没有匹配项", "shared-files-empty")
                }
                if (grid) {
                    items(state.visibleFiles.chunked(2), key = { "grid-${it.first().fileId}" }) { pair ->
                        Row(Modifier.fillMaxWidth().animateItem(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { file -> FileEntry(file, state, true, { click(file) }, { viewModel.toggle(file.fileId) }, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(state.visibleFiles, key = { it.fileId }) { file ->
                        FileEntry(file, state, false, { click(file) }, { viewModel.toggle(file.fileId) }, Modifier.animateItem())
                    }
                }
                if (state.next != "-1") item("more") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还有文件未加载，搜索与全选仅作用于已加载项目", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        TextButton(if (state.loading) "加载中…" else "加载更多", onClick = viewModel::more, enabled = !state.busy && !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shared-files-more"))
                    }
                }
                item("refresh") {
                    TextButton("刷新分享", onClick = viewModel::refresh, enabled = !state.busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("shared-files-refresh"))
                }
            }
        }
        if (state.files.isNotEmpty() || state.message != null) Feedback(state, viewModel::dismissFeedback, onOpenLogin)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalContentBottomPadding.current),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = state.selectedFiles
            val bytes = selected.filterNot { it.isFolder }.sumOf { it.size.coerceAtLeast(0) }
            Text(if (selected.isEmpty()) "选择文件后保存或下载 · 长按可多选" else
                "已选 ${selected.size} 项 · ${formatBytes(bytes)}${if (selected.any { it.isFolder }) "（文件夹大小未计入）" else ""}",
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.testTag("shared-files-selected-count"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton("保存至云盘", onClick = { if (loggedIn) showSavePicker = true else onOpenLogin() }, enabled = selected.isNotEmpty() && !state.busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("shared-files-save"))
                TextButton("下载", onClick = { if (loggedIn) viewModel.download() else onOpenLogin() }, enabled = selected.isNotEmpty() && !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("shared-files-download"))
            }
        }
    }
    if (showSavePicker) DirectoryPickerDialog(directoryPickerFactory, PickerMode.SAVE,
        onDismiss = { showSavePicker = false }, onConfirm = { showSavePicker = false; viewModel.save(it) })
    OverlayDialog(show = showSort, title = "排序", onDismissRequest = { showSort = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SharedSort.entries.forEach { sort ->
                TextButton("${sort.label}${if (sort == state.sort) " ✓" else ""}", onClick = { viewModel.sort(sort, state.ascending); showSort = false },
                    modifier = Modifier.fillMaxWidth().testTag("shared-sort-${sort.name}"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to "升序", false to "降序").forEach { (asc, label) ->
                    TextButton(label + if (asc == state.ascending) " ✓" else "", onClick = { viewModel.sort(state.sort, asc); showSort = false }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    OverlayDialog(show = showPassword, title = "修改提取码", onDismissRequest = { showPassword = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(passwordDraft, { passwordDraft = it.take(4) }, label = "提取码（4 位，无密码可留空）", singleLine = true)
            TextButton("重新加载", onClick = { viewModel.password(passwordDraft); viewModel.refresh(); showPassword = false }, modifier = Modifier.fillMaxWidth())
        }
    }
    OverlayDialog(show = showInfo, title = "分享信息", onDismissRequest = { showInfo = false }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.info?.name?.ifBlank { "名称未提供" } ?: "名称未提供", fontWeight = FontWeight.Medium)
            Text("分享者：${state.info?.owner?.ifBlank { "未提供" } ?: "未提供"}", fontSize = 14.sp)
            Text(shareExpiry(state.info), fontSize = 14.sp)
            Text("分享时间：${formatDateTime(state.info?.createdAt ?: 0).ifBlank { "未提供" }}", fontSize = 14.sp)
            Text("当前目录：${state.trail.joinToString(" / ") { it.second }}", fontSize = 14.sp)
            Text(fileCount(state), fontSize = 14.sp)
            TextButton("修改提取码", onClick = { showInfo = false; passwordDraft = state.password; showPassword = true },
                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("shared-password-edit"))
            TextButton("关闭", onClick = { showInfo = false }, modifier = Modifier.fillMaxWidth())
        }
    }
    val file = detail
    OverlayDialog(show = file != null, title = "文件详情", onDismissRequest = { detail = null }) {
        if (file != null) Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(file.fileName, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("shared-file-detail-name"))
            Text("${fileType(file)} · ${if (file.isFolder) "大小未提供" else formatBytes(file.size)}", fontSize = 14.sp)
            Text("修改时间：${formatDateTime(file.updateAt).ifBlank { "未提供" }}", fontSize = 14.sp)
            Text("创建时间：${formatDateTime(file.createAt).ifBlank { "未提供" }}", fontSize = 14.sp)
            Text("状态：${if (state.info?.expired == true) "分享已失效" else file.statusLabel}", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton("保存至云盘", onClick = { viewModel.selectOnly(file.fileId); detail = null; if (loggedIn) showSavePicker = true else onOpenLogin() },
                    enabled = file.available && !state.busy && state.info?.expired != true, modifier = Modifier.weight(1f))
                TextButton("下载", onClick = { viewModel.selectOnly(file.fileId); detail = null; if (loggedIn) viewModel.download() else onOpenLogin() },
                    enabled = file.available && !state.busy && state.info?.expired != true, modifier = Modifier.weight(1f))
            }
            TextButton("关闭", onClick = { detail = null }, modifier = Modifier.fillMaxWidth().testTag("shared-detail-close"))
        }
    }
    LaunchedEffect(state.queuedRevision, active) {
        if (active && state.queuedRevision > 0) { viewModel.consumeQueuedRevision(); onDownloadsQueued() }
    }
}

private fun fileCount(state: SharedFilesState): String = when {
    state.loading && state.files.isEmpty() -> "正在读取目录…"
    state.error != null && state.files.isEmpty() -> "目录未加载"
    state.search.isNotBlank() -> "匹配 ${state.visibleFiles.size} / 已加载 ${state.files.size} 项"
    state.next != "-1" -> "已加载 ${state.files.size}${if (state.total > 0) " / ${state.total}" else ""} 项"
    else -> "共 ${state.files.size} 项"
}

internal fun shareExpiry(info: SharedInfoDto?): String = when {
    info == null -> "有效期未提供"
    info.expired -> "分享已失效"
    info.expiration <= 0 -> "有效期未提供"
    Instant.ofEpochMilli(info.expiration).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate() == java.time.LocalDate.of(2099, 12, 12) -> "永久分享"
    else -> "有效至 ${formatDateTime(info.expiration)}"
}

@Composable
private fun ShareSummary(state: SharedFilesState, onInfo: () -> Unit, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("shared-summary"), cornerRadius = 22.dp, insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val avatar = state.info?.avatar.orEmpty().takeIf { runCatching { java.net.URI(it).scheme == "https" }.getOrDefault(false) }
                var avatarLoaded by remember(avatar) { mutableStateOf(false) }
                SubcomposeAsyncImage(model = avatar, contentDescription = "分享者头像", contentScale = ContentScale.Crop,
                    onSuccess = { avatarLoaded = true }, onError = { avatarLoaded = false },
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MiuixTheme.colorScheme.surfaceVariant)
                        .testTag(if (avatarLoaded) "shared-avatar-online" else "shared-avatar-placeholder"),
                    loading = { AvatarFallback() }, error = { AvatarFallback() })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(state.info?.owner?.ifBlank { "分享者信息未提供" } ?: if (state.infoLoading) "正在读取分享信息…" else "分享者信息未提供",
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("shared-owner"))
                    Text(shareExpiry(state.info) + if (state.info?.vip == true) " · VIP" else "", fontSize = 12.sp,
                        color = if (state.info?.expired == true) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.testTag("shared-expiry"))
                }
                IconButton(onClick = onInfo, modifier = Modifier.size(44.dp)) { Icon(PanIcons.More, "更多分享信息") }
            }
            state.info?.name?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("shared-title"))
            }
            Text((if (state.info?.hasPassword == true) "提取码保护" else if (state.info?.hasPassword == false) "无需提取码" else "保护方式未提供") +
                (state.info?.createdAt?.takeIf { it > 0 }?.let { " · 分享于 ${formatDateTime(it)}" } ?: ""),
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (state.infoError != null) TextButton("分享信息未加载 · 重试", onClick = onRetry, enabled = !state.busy,
                modifier = Modifier.testTag("shared-info-retry"))
        }
    }
}

@Composable
private fun FileEntry(file: FileItemDto, state: SharedFilesState, grid: Boolean, onClick: () -> Unit, onToggle: () -> Unit, modifier: Modifier) {
    Card(modifier.fillMaxWidth().testTag("shared-file_${file.fileId}"), cornerRadius = 18.dp, insideMargin = PaddingValues(0.dp)) {
        val clickModifier = Modifier.fillMaxWidth().combinedClickable(enabled = !state.busy, onClick = onClick, onLongClick = onToggle, onLongClickLabel = "选择文件")
        val info: @Composable ColumnScope.() -> Unit = {
            Text(file.fileName, fontSize = if (grid) 14.sp else 15.sp, fontWeight = FontWeight.Medium, minLines = if (grid) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${fileType(file)}${if (!file.isFolder) " · ${formatBytes(file.size)}" else ""}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(formatDateTime(file.updateAt).ifBlank { "修改时间未提供" }, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (grid) Column(clickModifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FileIcon(file); SelectionCheck(file, state, onToggle)
            }
            info()
            FileStatus(file, state)
        } else Row(clickModifier.padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionCheck(file, state, onToggle)
            FileIcon(file)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), content = info)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FileStatus(file, state)
                Icon(if (file.isFolder) PanIcons.Forward else PanIcons.Info, null, modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun FileStatus(file: FileItemDto, state: SharedFilesState) {
    Text(if (state.info?.expired == true) "已失效" else file.statusLabel, fontSize = 11.sp,
        color = if (!file.available || state.info?.expired == true) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun AvatarFallback() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(PanIcons.Account, null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun SelectionCheck(file: FileItemDto, state: SharedFilesState, onToggle: () -> Unit) {
    val selected = file.fileId in state.selected
    val tint by animateColorAsState(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant, label = "shared-selection")
    Box(Modifier.size(44.dp).toggleable(selected, enabled = !state.busy && file.available && state.info?.expired != true,
        role = Role.Checkbox, onValueChange = { onToggle() }).testTag("shared-file-check_${file.fileId}"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(tint)
            .border(1.5.dp, if (selected) tint else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(PanIcons.Check, null, tint = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

private fun fileType(file: FileItemDto): String = if (file.isFolder) "文件夹" else file.fileName.substringAfterLast('.', "").takeIf { it.length in 1..8 }
    ?.uppercase(java.util.Locale.ROOT)?.plus(" 文件") ?: "文件"

@Composable
private fun FileIcon(file: FileItemDto) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.09f)), contentAlignment = Alignment.Center) {
        Icon(if (file.isFolder) PanIcons.Folder else PanIcons.File, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Feedback(state: SharedFilesState, dismiss: () -> Unit, login: () -> Unit) {
    val text = state.error ?: state.message ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f).testTag(if (state.error == null) "shared-files-message" else "shared-files-error"),
            color = if (state.error == null) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error)
        if (state.error?.contains("登录") == true) TextButton("登录", login, modifier = Modifier.testTag("shared-files-login"))
        if (!state.busy) IconButton(onClick = dismiss, modifier = Modifier.size(44.dp).testTag("shared-files-dismiss")) { Icon(PanIcons.Close, "关闭提示") }
    }
}

@Composable
private fun Hint(text: String, tag: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.testTag(tag))
    }
}
