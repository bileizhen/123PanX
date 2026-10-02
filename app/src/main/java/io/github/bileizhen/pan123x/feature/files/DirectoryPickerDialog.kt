package io.github.bileizhen.pan123x.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.data.file.DirectorySnapshot
import io.github.bileizhen.pan123x.data.file.FileRepository
import io.github.bileizhen.pan123x.data.file.RefreshOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import io.github.bileizhen.pan123x.ui.component.ScreenFrame
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 目标选择器的用途：决定底部确认键文案与标题。 */
enum class PickerMode(val title: String, val confirmLabel: String) {
    MOVE("移动到", "移到此处"),
    COPY("复制到", "复制到此处"),
    // M7 秒传导入（feature/offline OfflineScreen）复用同一选择器；仅追加枚举值，不影响既有两态。
    IMPORT("选择目标目录", "导入到此处"),
    // 分享文件"保存至云盘"（feature/share SharedFilesScreen）复用同一选择器。
    SAVE("保存至云盘", "保存到此处"),
}

data class DirectoryPickerUiState(
    /** 当前所在目录；根目录为 0。 */
    val dirId: Long = 0L,
    /** 选择器内部导航栈（不含根）：末项即当前目录，名称用于面包屑。 */
    val trail: List<CloudFileEntity> = emptyList(),
    /** 当前目录下的子文件夹（仅文件夹，文件不可作为目标）。 */
    val folders: List<CloudFileEntity> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * 目标目录选择器状态机（M3）：只读复用 [FileRepository.observeDirectory]，按当前目录
 * 订阅；目录从未加载（缓存为空且无 allLoaded 状态行）时自动刷新一次，失败保留旧缓存
 * 并给错误文案。对话框每次打开经 [reset] 回到根目录重新开始。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryPickerViewModel(
    private val repository: FileRepository,
    private val logger: AppLogger,
) : ViewModel() {

    private val mutableState = MutableStateFlow(DirectoryPickerUiState())
    val uiState: StateFlow<DirectoryPickerUiState> = mutableState.asStateFlow()
    private val resetRevision = MutableStateFlow(0L)

    /** 已自动刷新过的目录：同一目录失败不循环重试；reset 后允许重来。 */
    private val autoRefreshed = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            combine(mutableState.map { it.dirId }.distinctUntilChanged(), resetRevision) { dirId, revision ->
                dirId to revision
            }
                .flatMapLatest { (dirId, revision) ->
                    repository.observeDirectory(dirId).map { snapshot -> Triple(dirId, revision, snapshot) }
                }
                .collect { (dirId, revision, snapshot) ->
                    if (revision != resetRevision.value) return@collect
                    mutableState.update { old ->
                        if (old.dirId != dirId) return@update old
                        // 目录尚未加载过（空缓存 + 未 allLoaded）时保持 loading，等待自动刷新落库。
                        val pendingLoad = snapshot.files.isEmpty() && !snapshot.allLoaded
                        old.copy(
                            folders = pickerFolders(snapshot),
                            loading = old.loading && pendingLoad,
                            error = if (pendingLoad) old.error else null,
                        )
                    }
                    maybeAutoRefresh(dirId, snapshot)
                }
        }
    }

    private fun pickerFolders(snapshot: DirectorySnapshot): List<CloudFileEntity> =
        snapshot.files.filter { it.isFolder }.sortedBy { it.fileName.lowercase() }

    private fun maybeAutoRefresh(dirId: Long, snapshot: DirectorySnapshot) {
        if (snapshot.files.isNotEmpty() || snapshot.allLoaded) return
        if (repository.accountIdOfSession == null) return
        if (!autoRefreshed.add(dirId)) return
        logger.i(LogSource.FILE, "选择器进入目录 $dirId 无缓存，自动刷新")
        viewModelScope.launch {
            val outcome = repository.refreshDirectory(dirId)
            if (outcome is RefreshOutcome.Failure) {
                logger.w(LogSource.FILE, "选择器目录 $dirId 刷新失败：${outcome.userMessage}")
                mutableState.update { old ->
                    if (old.dirId != dirId) old else old.copy(loading = false, error = outcome.userMessage)
                }
            }
        }
    }

    /** 对话框每次打开回到根目录；自动刷新记录一并清空。 */
    fun reset() {
        autoRefreshed.clear()
        mutableState.value = DirectoryPickerUiState()
        // 根目录未改变时 distinctUntilChanged 不会重订阅；复位后也必须重新读取缓存。
        resetRevision.value += 1
    }

    /** 进入子文件夹：压栈并清空旧列表，避免短暂显示上一个目录的内容。 */
    fun enterFolder(folder: CloudFileEntity) {
        if (!folder.isFolder) return
        navigate(folder, trail = mutableState.value.trail + folder)
    }

    /** 面包屑跳转：回到栈中第 [index] 层（该层即当前目录，其子级被截断）。 */
    fun openTrail(index: Int) {
        val trail = mutableState.value.trail
        if (index !in trail.indices) return
        navigate(trail[index], trail = trail.take(index + 1))
    }

    fun openRoot() {
        mutableState.update { old ->
            old.copy(
                dirId = 0L,
                trail = emptyList(),
                folders = emptyList(),
                loading = true,
                error = null,
            )
        }
    }

    private fun navigate(target: CloudFileEntity, trail: List<CloudFileEntity>) {
        mutableState.update { old ->
            old.copy(
                dirId = target.fileId,
                trail = trail,
                folders = emptyList(),
                loading = true,
                error = null,
            )
        }
    }
}

/**
 * 目标目录选择器（M3 移动 / 复制）：全屏对话框，面包屑 + 当前目录的子文件夹列表，
 * 底部"移到此处 / 复制到此处"确认并回调目标 dirId。起始为根目录（0）。
 * ViewModel 以固定 key 挂在当前页面的 ViewModelStore 上，打开时经 [DirectoryPickerViewModel.reset]
 * 复位，避免残留上一次的导航栈。
 */
@Composable
fun DirectoryPickerDialog(
    pickerFactory: ViewModelProvider.Factory,
    mode: PickerMode,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    show: Boolean = true,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val visible = remember { MutableTransitionState(false).apply { targetState = show } }
    LaunchedEffect(show) { visible.targetState = show }
    if (!visible.currentState && !visible.targetState) return
    val viewModel: DirectoryPickerViewModel = viewModel(key = "directory-picker", factory = pickerFactory)
    LaunchedEffect(show) { if (show) viewModel.reset() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val back: () -> Unit = {
        when {
            state.trail.size > 1 -> viewModel.openTrail(state.trail.lastIndex - 1)
            state.trail.isNotEmpty() -> viewModel.openRoot()
            else -> onDismiss()
        }
    }

    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
      AnimatedVisibility(visibleState = visible,
          enter = fadeIn(tween(150)) + slideInVertically(spring(dampingRatio = .9f, stiffness = 500f)) { it / 12 },
          exit = fadeOut(tween(100)) + slideOutVertically(tween(160)) { it / 12 }) {
        ScreenFrame(mode.title, onBack = back) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                uiText("选择目标文件夹；仅文件夹可作为目标，文件不会显示。"),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            PickerBreadcrumb(state, onOpenRoot = viewModel::openRoot, onOpenTrail = viewModel::openTrail)
            when {
                state.loading -> PickerHint("正在加载…", Modifier.fillMaxWidth().weight(1f).testTag("picker_loading"))
                state.folders.isEmpty() -> PickerHint(
                    text = state.error?.let { "目录加载失败：$it" } ?: "无子文件夹",
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("picker_empty"),
                )

                else -> LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("picker_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.folders, key = { it.fileId }) { folder ->
                        PickerFolderRow(folder, onClick = { viewModel.enterFolder(folder) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    uiText("取消"),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                )
                TextButton(
                    mode.confirmLabel,
                    onClick = { onConfirm(state.dirId) },
                    enabled = !state.loading,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("picker_confirm"),
                )
            }
        }
        }
      }
    }
}

@Composable
private fun PickerHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** 选择器内部面包屑：根目录 + 导航栈中的各层文件夹名。 */
@Composable
private fun PickerBreadcrumb(
    state: DirectoryPickerUiState,
    onOpenRoot: () -> Unit,
    onOpenTrail: (Int) -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            uiText("根目录"),
            onClick = onOpenRoot,
            modifier = Modifier.heightIn(min = 48.dp).testTag("picker_root"),
        )
        state.trail.forEachIndexed { index, folder ->
            Text("/", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(
                folder.fileName,
                onClick = { onOpenTrail(index) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("picker_crumb_${folder.fileId}"),
            )
        }
    }
}

@Composable
private fun PickerFolderRow(folder: CloudFileEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("picker_folder_${folder.fileId}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.primary.copy(alpha = .09f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PanIcons.Folder, null, tint = MiuixTheme.colorScheme.primary)
        }
        Text(
            folder.fileName,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 20.sp)
    }
}
