package io.github.bileizhen.pan123x.feature.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.bileizhen.pan123x.ui.component.ScreenFrame
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import io.github.bileizhen.pan123x.ui.component.PageBackHandler
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.WorkspaceAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.bileizhen.pan123x.ui.component.StaggeredEntrance
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.bileizhen.pan123x.ui.component.LocalPageActive
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.ui.util.FileKind
import io.github.bileizhen.pan123x.ui.util.fileKindOf
import io.github.bileizhen.pan123x.ui.util.formatBytes
import io.github.bileizhen.pan123x.ui.util.formatDateTime
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog as SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文件页：Room 缓存立即显示 + 网络刷新（下拉 / 工具栏），目录导航
 * 面包屑由导航层维护并经 breadcrumbs 传入。列表 / 网格切换只改本地布局，不触发请求
 * （稳定 key = fileId）。
 *
 * 文件操作：长按在按压处打开快捷菜单，通过菜单的“多选”进入批量操作。
 * （移动 / 复制 / 重命名 / 删除）；删除二次确认；新建文件夹与重命名走命名对话框；
 * 移动 / 复制打开 [DirectoryPickerDialog]。写操作经 FilesViewModel 调 FileOpsRepository，
 * 结果以一次性 opsMessage 在页面顶部展示，3 秒自动清除。
 */
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    directoryPickerFactory: ViewModelProvider.Factory,
    directoryId: Long = 0,
    breadcrumbs: List<Pair<Long, String>> = emptyList(),
    onOpenDirectory: (Long, String) -> Unit,
    /** [FileKind] 由列表行现算（fileKindOf），导航层据此分发预览页。 */
    onOpenFile: (Long, FileKind) -> Unit,
    onOpenAncestor: (Long) -> Unit,
    onOpenLogin: () -> Unit,
    onDownloadsQueued: (String?) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenInfo: () -> Unit = {},
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bottomPadding = LocalContentBottomPadding.current
    PageBackHandler(enabled = state.selectMode) { viewModel.clearSelection() }
    val directoryName = if (directoryId == 0L) "123PanX" else breadcrumbs.lastOrNull()?.second ?: "文件"
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var sortVisible by rememberSaveable { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() { searchVisible = false; viewModel.setSearch(""); focus.clearFocus(); keyboard?.hide() }
    PageBackHandler(enabled = searchVisible && !state.selectMode) { closeSearch() }
    LaunchedEffect(searchVisible, state.selectMode) {
        if (!searchVisible || state.selectMode) { focus.clearFocus(); keyboard?.hide() }
        if (state.selectMode) { showMore = false; showAdd = false }
    }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showShareDialog by rememberSaveable { mutableStateOf(false) }
    var pickerMode by rememberSaveable { mutableStateOf<PickerMode?>(null) }
    var menuTarget by remember { mutableStateOf<FileMenuTarget?>(null) }
    var renameTarget by remember { mutableStateOf<CloudFileEntity?>(null) }
    val pageActive = LocalPageActive.current
    val openDownloads by rememberUpdatedState(onDownloadsQueued)
    LaunchedEffect(viewModel, pageActive) {
        if (pageActive) viewModel.downloadQueuedEvents.collect { openDownloads(it) }
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var pendingLayoutAnchor by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(state.search, state.sortField, state.ascending, state.account?.accountId) {
        pendingLayoutAnchor = null
        // An unmounted layout must not leave a suspended scroll that resets a later switch.
        listState.requestScrollToItem(0)
        gridState.requestScrollToItem(0)
    }
    fun toggleLayout() {
        // Capture the source position before Crossfade mounts the other layout.
        val anchor = if (state.grid) gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.key
            else listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.key
        pendingLayoutAnchor = (anchor as? Long)?.let { !state.grid to it }
        viewModel.toggleGrid()
    }
    var retainedPickerMode by remember { mutableStateOf<PickerMode?>(null) }
    LaunchedEffect(pickerMode) { pickerMode?.let { retainedPickerMode = it } }
    LaunchedEffect(pageActive, state.account?.accountId, directoryId, state.grid) { menuTarget = null; showMore = false; showAdd = false }
    LaunchedEffect(state.account?.accountId) { searchVisible = false; showCreateDialog = false; showRenameDialog = false; confirmDelete = false; showShareDialog = false; pickerMode = null }
    val context = LocalContext.current
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) viewModel.downloadLocationChosen(null)
        else try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.downloadLocationChosen(uri.toString())
        } catch (_: SecurityException) { viewModel.downloadLocationChosen(null, "无法取得下载目录权限，请重新选择") }
    }
    LaunchedEffect(viewModel, pageActive) {
        if (pageActive) viewModel.downloadLocationRequests.collect { downloadPicker.launch(null) }
    }
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)

    // M5 上传入口：SAF 多选文件，统一 Uri / ContentResolver，不依赖真实路径。
    // 结果取 persistable 授权，供进程重启后断点续传按 uri 重建来源；个别 provider 不支持
    // 持久化授权时静默放弃（续传退化为重新选择，不影响本次上传）。
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            viewModel.uploadUris(uris.map { it.toString() })
        }
    }

    // 一次性操作提示：3 秒后自动清掉，避免过期文案常驻（AccountScreen message 同款）。
    LaunchedEffect(state.opsMessage) {
        if (state.opsMessage != null) {
            delay(3_000)
            viewModel.consumeOpsMessage()
        }
    }

    ScreenFrame(directoryName, onBack = onBack?.let { { if (state.selectMode) viewModel.clearSelection() else if (searchVisible) closeSearch() else it() } },
        translateTitle = directoryId == 0L, actions = {
        if (!state.loggedOut && !state.restoring && !state.selectMode) {
            WorkspaceAction(if (searchVisible) PanIcons.Close else PanIcons.Search, uiText(if (searchVisible) "关闭搜索" else "搜索"),
                { if (searchVisible) closeSearch() else searchVisible = true }, "files_search")
            Box {
                WorkspaceAction(PanIcons.More, uiText("更多文件操作"), { showMore = true }, "files_more")
                BrowserActionsMenu(showMore, listOf(
                    BrowserMenuAction("选择文件", PanIcons.List, "files_select", state.files.isNotEmpty() && !state.opsBusy, onClick = viewModel::beginSelection),
                    BrowserMenuAction("刷新", PanIcons.Transfer, "files_refresh", !state.refreshing, onClick = viewModel::refresh)
                ), { showMore = false })
            }
        }
    }, topBarBottom = {
        Column {
            AnimatedVisibility(searchVisible && !state.selectMode, enter = expandVertically(spring(dampingRatio = .88f, stiffness = 700f)) + fadeIn(tween(150)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(100))) { FileSearchBar(state.search, viewModel::setSearch) }
            if (!state.loggedOut && !state.restoring) {
                AnimatedContent(state.selectMode, label = "file-toolbar", transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }) { selecting ->
                    if (selecting) SelectionBar(state, viewModel::invertSelection, viewModel::clearSelection)
                    else DirectoryToolbar(state, breadcrumbs, onOpenAncestor, { sortVisible = true }, ::toggleLayout)
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    AnimatedVisibility(state.offlineCache) { OfflineBanner() }
                    AnimatedVisibility(state.opsMessage != null) { OpsMessageLine(state.opsMessage.orEmpty()) }
                }
            }
        }
    }) {
    Box(Modifier.fillMaxSize()) {
        // Miuix 0.9.3 的 PullToRefresh：逻辑 refreshing 状态由 ViewModel 持有（hoisted），
        // contentPadding 与列表顶部内边距对齐，指示器出现在列表内容之上。
        PullToRefresh(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 16.dp),
            refreshTexts = listOf("下拉刷新", "释放立即刷新", "正在刷新…", "刷新完成").map(uiText),
        ) {
            Crossfade(targetState = state.grid, animationSpec = tween(220), label = "file-layout") { grid ->
              CompositionLocalProvider(LocalPageActive provides (pageActive && grid == state.grid)) {
              if (grid) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize().testTag("files_grid"),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding + 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (directoryId == 0L && state.account != null && !state.selectMode && state.search.isBlank()) item(key = "files_space", span = { GridItemSpan(maxLineSpan) }) {
                        SpaceCard(state, onOpenInfo)
                    }
                    if (state.loggedOut || state.empty) item(key = "placeholder", span = { GridItemSpan(maxLineSpan) }) { FilesPlaceholder(state, onOpenLogin, viewModel::refresh) }
                    itemsIndexed(state.files, key = { _, file -> file.fileId }) { index, file ->
                      Box(Modifier.animateItem()) {
                       StaggeredEntrance(index, baseDelayMs = if (directoryId == 0L) 0 else 450) {
                        FileTile(
                            file = file,
                            grid = true,
                            selectMode = state.selectMode,
                            selected = file.fileId in state.selected,
                            onOpen = { if (file.isFolder) onOpenDirectory(file.fileId, file.fileName) else onOpenFile(file.fileId, fileKindOf(file.isFolder, file.fileName)) },
                            onContextMenu = { point -> menuTarget = FileMenuTarget(file, point) },
                            onToggle = { viewModel.toggleSelect(file) },
                        )
                       }
                      }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("files_list"),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding + 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (directoryId == 0L && state.account != null && !state.selectMode && state.search.isBlank()) item(key = "files_space") {
                        SpaceCard(state, onOpenInfo)
                    }
                    if (state.loggedOut || state.empty) item(key = "placeholder") { FilesPlaceholder(state, onOpenLogin, viewModel::refresh) }
                    itemsIndexed(state.files, key = { _, file -> file.fileId }) { index, file ->
                      Box(Modifier.animateItem()) {
                       StaggeredEntrance(index, baseDelayMs = if (directoryId == 0L) 0 else 450) {
                        FileTile(
                            file = file,
                            grid = false,
                            selectMode = state.selectMode,
                            selected = file.fileId in state.selected,
                            onOpen = { if (file.isFolder) onOpenDirectory(file.fileId, file.fileName) else onOpenFile(file.fileId, fileKindOf(file.isFolder, file.fileName)) },
                            onContextMenu = { point -> menuTarget = FileMenuTarget(file, point) },
                            onToggle = { viewModel.toggleSelect(file) },
                        )
                       }
                      }
                    }
                }
            }
              if (grid == state.grid) {
                  LaunchedEffect(pendingLayoutAnchor, state.files) {
                      val pending = pendingLayoutAnchor ?: return@LaunchedEffect
                      if (pending.first != grid) return@LaunchedEffect
                      val index = state.files.indexOfFirst { it.fileId == pending.second }
                      if (index >= 0) {
                          val overview = if (directoryId == 0L && state.account != null && !state.selectMode && state.search.isBlank()) 1 else 0
                          // The target must be measured first; an unmounted lazy layout has no items.
                          snapshotFlow { (if (grid) gridState.layoutInfo.totalItemsCount else listState.layoutInfo.totalItemsCount) > index + overview }
                              .first { it }
                          if (grid) gridState.scrollToItem(index + overview) else listState.scrollToItem(index + overview)
                      }
                      pendingLayoutAnchor = null
                  }
              }
              }
            }
        }
        androidx.compose.animation.AnimatedVisibility(!state.selectMode && !state.loggedOut && !state.restoring && !searchVisible,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = bottomPadding + 12.dp),
            enter = fadeIn(tween(160)) + scaleIn(spring(stiffness = 500f)), exit = fadeOut(tween(100)) + scaleOut(tween(140))) {
            FileAddButton(showAdd, state.opsBusy, { showAdd = !showAdd }, { showAdd = false },
                { uploadPicker.launch(arrayOf("*/*")) }, { showCreateDialog = true })
        }
        // 多选底部操作栏替代手机主导航，并预留列表底部空间。
        androidx.compose.animation.AnimatedVisibility(state.selectMode, modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(150)) + slideInVertically(spring(dampingRatio = .9f, stiffness = 500f)) { it / 2 },
            exit = fadeOut(tween(100)) + slideOutVertically(tween(180)) { it / 2 }) {
            SelectionActionBar(
                busy = state.opsBusy,
                canAct = state.selected.isNotEmpty(),
                canRename = state.selected.size == 1,
                onMove = { pickerMode = PickerMode.MOVE },
                onCopy = { pickerMode = PickerMode.COPY },
                onDownload = { viewModel.downloadSelected() },
                onShare = { showShareDialog = true },
                onRapid = { viewModel.exportRapid() },
                onRename = { renameTarget = state.selected.values.singleOrNull(); showRenameDialog = true },
                onDelete = { confirmDelete = true },
                modifier = Modifier.padding(bottom = bottomPadding).testTag("files_batch_actions"),
            )
        }
    }

    }
    SortDialog(state, sortVisible, { sortVisible = false }, viewModel::setSort, viewModel::setAscending)

    menuTarget?.let { target ->
        FileContextMenu(target, state.opsBusy, onClosed = { menuTarget = null }) { action ->
            when (action) {
                FileQuickAction.SELECT -> viewModel.enterSelectMode(target.file)
                FileQuickAction.DETAILS -> onOpenFile(target.file.fileId, FileKind.OTHER)
                else -> {
                    if (viewModel.prepareSingleFileAction(target.file)) when (action) {
                        FileQuickAction.DOWNLOAD -> viewModel.downloadSelected()
                        FileQuickAction.SHARE -> showShareDialog = true
                        FileQuickAction.RENAME -> { renameTarget = target.file; showRenameDialog = true }
                        FileQuickAction.MOVE -> pickerMode = PickerMode.MOVE
                        FileQuickAction.COPY -> pickerMode = PickerMode.COPY
                        FileQuickAction.RAPID -> viewModel.exportRapid()
                        FileQuickAction.DELETE -> confirmDelete = true
                        else -> Unit
                    }
                }
            }
        }
    }

        NamePromptDialog(
            show = showCreateDialog,
            title = uiText("新建文件夹"),
            hint = uiText("文件夹名称"),
            initialValue = "",
            confirmLabel = uiText("创建"),
            inputTag = "dialog_create_input",
            confirmTag = "dialog_create_ok",
            busy = state.opsBusy,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                viewModel.createFolder(name)
            },
        )
    renameTarget?.let { target ->
            NamePromptDialog(
                show = showRenameDialog,
                title = uiText("重命名"),
                hint = uiText("新名称"),
                initialValue = target.fileName,
                confirmLabel = uiText("重命名"),
                inputTag = "dialog_rename_input",
                confirmTag = "dialog_rename_ok",
                busy = state.opsBusy,
                onDismiss = { showRenameDialog = false },
                onConfirm = { name ->
                    showRenameDialog = false
                    viewModel.rename(target, name)
                },
            )
    }
    // 删除必须二次确认（硬性）：语义是移入回收站，可在回收站恢复。
    SuperDialog(
        show = confirmDelete,
        title = "将 ${state.selected.size} 个项目移入回收站？",
        summary = uiText("删除的项目会进入回收站，可随时恢复。"),
        onDismissRequest = { confirmDelete = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { confirmDelete = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(
                uiText("删除"),
                onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                },
                enabled = !state.opsBusy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("files_action_delete_confirm"),
            )
        }
    }
    // M6 分享入口：弹窗收集可选密码 → ShareLauncher 创建 →
    // 成功弹链接（可复制），失败走 opsMessage。
        ShareCreateDialog(
            show = showShareDialog && state.shareLink == null,
            count = state.selected.size,
            initialName = state.selected.values.singleOrNull()?.fileName ?: "分享 ${state.selected.size} 个文件",
            busy = state.opsBusy,
            submissionError = state.opsMessage,
            onDismiss = { showShareDialog = false },
            onConfirm = { pwd ->
                viewModel.shareSelected(pwd)
            },
        )
    state.shareLink?.let { link ->
        LaunchedEffect(link) { showShareDialog = false }
        SuperDialog(
            show = true,
            title = uiText("分享链接已创建"),
            summary = uiText("链接可在分享页管理"),
            onDismissRequest = { viewModel.consumeShareLink() },
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(link, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            }
            if (state.sharePassword.isNotBlank()) Text("提取码：${state.sharePassword}", modifier = Modifier.padding(bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    uiText("复制"),
                    onClick = {
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("123PanX 分享链接", io.github.bileizhen.pan123x.core.share.SharedLink(link, state.sharePassword).text))
                        viewModel.consumeShareLink()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("files_share_copy"),
                )
                TextButton(
                    uiText("关闭"),
                    onClick = { viewModel.consumeShareLink() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
            }
        }
    }
    // M7 秒传导出：纯客户端生成，JSON 与 123FLCPV2 链接均可复制。
    state.rapidExport?.let { export ->
        SuperDialog(
            show = true,
            title = "秒传数据（${export.jsonText.length} 字符）",
            summary = uiText("可用秒传工具在其他 123 账号导入相同文件。"),
            onDismissRequest = { viewModel.consumeRapidExport() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    "复制 JSON",
                    onClick = {
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("123PanX 秒传 JSON", export.jsonText))
                        viewModel.consumeRapidExport()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("files_rapid_copy_json"),
                )
                TextButton(
                    uiText("复制链接"),
                    onClick = {
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("123PanX 秒传链接", export.linkText))
                        viewModel.consumeRapidExport()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("files_rapid_copy_link"),
                )
                TextButton(
                    uiText("关闭"),
                    onClick = { viewModel.consumeRapidExport() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
            }
        }
    }
    retainedPickerMode?.let { mode ->
        DirectoryPickerDialog(
            show = pickerMode != null,
            pickerFactory = directoryPickerFactory,
            mode = mode,
            onDismiss = { pickerMode = null },
            onConfirm = { targetDirId ->
                pickerMode = null
                when (mode) {
                    PickerMode.MOVE -> viewModel.moveSelected(targetDirId)
                    PickerMode.COPY -> viewModel.copySelected(targetDirId)
                    // IMPORT 供离线页、SAVE 供分享查看页复用同一选择器，文件页不出现。
                    PickerMode.IMPORT, PickerMode.SAVE -> Unit
                }
            },
        )
    }
}

@Composable
private fun OpsMessageLine(message: String) {
    Text(message, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().testTag("files_ops_message"))
}

/**
 * 新建文件夹 / 重命名共用的命名对话框：非空且不含 "/" 才可确认（客户端守门，
 * 服务端路径分隔符禁止出现在名称中）。
 */
@Composable
private fun NamePromptDialog(
    show: Boolean,
    title: String,
    hint: String,
    initialValue: String,
    confirmLabel: String,
    inputTag: String,
    confirmTag: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    var name by rememberSaveable { mutableStateOf(initialValue) }
    LaunchedEffect(show) { if (show) name = initialValue }
    val valid = name.isNotBlank() && !name.contains('/')
    SuperDialog(
        show = show,
        title = title,
        summary = uiText("名称不能为空，且不能包含“/”。"),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = hint,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(inputTag),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(uiText("取消"), onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                TextButton(
                    confirmLabel,
                    onClick = { onConfirm(name.trim()) },
                    enabled = valid && !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(confirmTag),
                )
            }
        }
    }
}

/** 有缓存但最近一次刷新失败：保留旧列表，仅给一条细提示。 */
@Composable
private fun OfflineBanner() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Text(
        uiText("网络不可用，正在显示缓存"),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().testTag("files_offline_banner"),
    )
}

/** 未登录提示 / 恢复中 / 错误（带重试）/ 空目录 / 搜索无结果的占位块。 */
@Composable
private fun FilesPlaceholder(state: FilesUiState, onOpenLogin: () -> Unit, onRefresh: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    when {
        state.loggedOut -> Card(Modifier.fillMaxWidth().testTag("files_empty"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("登录后查看云端文件", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text("登录 123 云盘账户后，即可在这里浏览与管理云端文件。", color = summary)
                TextButton(uiText("去登录"), onClick = onOpenLogin, modifier = Modifier.heightIn(min = 48.dp).testTag("files_login_entry"))
            }
        }

        state.restoring -> Text(uiText("正在恢复登录…"), modifier = Modifier.testTag("files_empty").padding(24.dp), color = summary)

        state.error != null -> Card(Modifier.fillMaxWidth().testTag("files_empty"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiText("无法加载目录"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(state.error.orEmpty(), color = summary)
                TextButton(uiText("重试"), onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp).testTag("files_retry"))
            }
        }

        state.refreshing || state.loading -> Text(uiText("正在加载云端文件…"), modifier = Modifier.testTag("files_empty").padding(24.dp), color = summary)

        else -> Card(Modifier.fillMaxWidth().testTag("files_empty"), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.search.isBlank()) {
                    Text(uiText("目录为空"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(uiText("这里暂时没有文件。"), color = summary)
                } else {
                    Text(uiText("没有匹配的文件"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Text(uiText("试试其他关键词，搜索范围是当前目录。"), color = summary)
                }
            }
        }
    }
}

@Composable
private fun SortDialog(
    state: FilesUiState,
    show: Boolean,
    onDismiss: () -> Unit,
    onSort: (FileSortField) -> Unit,
    onAscending: (Boolean) -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    SuperDialog(show = show, title = uiText("排序方式"), onDismissRequest = onDismiss) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FileSortField.entries.forEach { field ->
                    TextButton(
                        "${if (field == state.sortField) "✓ " else ""}${uiText(field.label)}",
                        onClick = { onSort(field); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(uiText("方向"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextButton(
                    "${if (state.ascending) "✓ " else ""}${uiText("升序")}",
                    onClick = { onAscending(true); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    "${if (!state.ascending) "✓ " else ""}${uiText("降序")}",
                    onClick = { onAscending(false); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
    }
}

/** 文件详情（暂不支持预览的类型先展示元数据）。数据来自 Room 缓存行。 */
@Composable
fun FileDetailScreen(viewModel: FileDetailViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("file_detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(uiText("文件信息"), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        val file = state.file
        when {
            state.loading -> item { Text(uiText("正在加载文件信息…")) }
            file == null -> item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(uiText("文件不在缓存中"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Text(uiText("返回文件页刷新目录后重试。"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }

            else -> {
                item {
                    val kind = fileKindOf(file.isFolder, file.fileName)
                    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            FileSymbol(kind)
                            Text(file.fileName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (file.isFolder) "文件夹" else "${kind.label} · ${formatBytes(file.size)}",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            DetailLine("创建时间", formatDateTime(file.createAt).ifBlank { "未知" })
                            DetailLine("修改时间", formatDateTime(if (file.updateAt > 0) file.updateAt else file.createAt).ifBlank { "未知" })
                            DetailLine("ETag", file.etag.truncateEtag())
                            DetailLine("大小", if (file.isFolder) "—" else formatBytes(file.size))
                        }
                    }
                }
            }
        }
    }
}

/** etag 只截断展示，避免长串占满卡片；空值显示占位符。 */
private fun String.truncateEtag(keep: Int = 16): String =
    if (isBlank()) "—" else if (length <= keep) this else take(keep) + "…"

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 15.sp)
    }
}
