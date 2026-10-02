// Navigation shell adapted from LeiFetch / XBlocker / SukiSU-Ultra integration.
// GPL-3.0; only general UI navigation is retained.
package io.github.bileizhen.pan123x.ui

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.bileizhen.pan123x.AppContainer
import io.github.bileizhen.pan123x.feature.about.AboutScreen
import io.github.bileizhen.pan123x.feature.diagnostics.DiagnosticsScreen
import io.github.bileizhen.pan123x.feature.diagnostics.DiagnosticsViewModel
import io.github.bileizhen.pan123x.feature.about.LicenseScreen
import io.github.bileizhen.pan123x.feature.about.PrivacyScreen
import io.github.bileizhen.pan123x.ui.component.EmptyState
import io.github.bileizhen.pan123x.ui.component.PageBackActions
import io.github.bileizhen.pan123x.ui.component.LocalPageBackActions
import io.github.bileizhen.pan123x.ui.component.LocalPageActive
import io.github.bileizhen.pan123x.feature.account.AccountScreen
import io.github.bileizhen.pan123x.feature.account.AccountsScreen
import io.github.bileizhen.pan123x.feature.account.AccountsViewModel
import io.github.bileizhen.pan123x.feature.account.AccountViewModel
import io.github.bileizhen.pan123x.feature.account.CloudInfoScreen
import io.github.bileizhen.pan123x.feature.files.FileDetailScreen
import io.github.bileizhen.pan123x.feature.files.FileDetailViewModel
import io.github.bileizhen.pan123x.feature.files.DirectoryPickerViewModel
import io.github.bileizhen.pan123x.feature.files.FilesScreen
import io.github.bileizhen.pan123x.feature.files.FilesViewModel
import io.github.bileizhen.pan123x.feature.login.LoginScreen
import io.github.bileizhen.pan123x.feature.login.LoginViewModel
import io.github.bileizhen.pan123x.feature.offline.OfflineScreen
import io.github.bileizhen.pan123x.feature.offline.OfflineViewModel
import io.github.bileizhen.pan123x.feature.offline.RapidImportViewModel
import io.github.bileizhen.pan123x.feature.recycle.RecycleScreen
import io.github.bileizhen.pan123x.feature.recycle.RecycleViewModel
import io.github.bileizhen.pan123x.feature.settings.SettingsScreen
import io.github.bileizhen.pan123x.feature.settings.AppearanceScreen
import io.github.bileizhen.pan123x.feature.settings.SettingsViewModel
import io.github.bileizhen.pan123x.feature.preview.ImagePreviewScreen
import io.github.bileizhen.pan123x.feature.preview.PdfPreviewScreen
import io.github.bileizhen.pan123x.feature.preview.PlayerScreen
import io.github.bileizhen.pan123x.feature.preview.PreviewViewModel
import io.github.bileizhen.pan123x.feature.preview.TextPreviewScreen
import io.github.bileizhen.pan123x.feature.share.ShareScreen
import io.github.bileizhen.pan123x.feature.share.ShareViewModel
import io.github.bileizhen.pan123x.feature.share.SharedFilesScreen
import io.github.bileizhen.pan123x.feature.share.SharedFilesViewModel
import io.github.bileizhen.pan123x.ui.util.FileKind
import io.github.bileizhen.pan123x.feature.transfer.TransferDetailScreen
import io.github.bileizhen.pan123x.feature.transfer.TransferScreen
import io.github.bileizhen.pan123x.feature.transfer.TransferViewModel
import io.github.bileizhen.pan123x.ui.component.HighApiFloatingNavigation
import io.github.bileizhen.pan123x.ui.component.MainSidebar
import io.github.bileizhen.pan123x.ui.component.PlainFloatingBar
import io.github.bileizhen.pan123x.ui.component.ScreenFrame
import io.github.bileizhen.pan123x.ui.component.StandardNavigationBar
import io.github.bileizhen.pan123x.ui.component.LocalContentBottomPadding
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import io.github.bileizhen.pan123x.ui.util.viewModelFactory
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Serializable
private data object Home : NavKey

@Serializable
private data class Page(val kind: String, val id: String = "") : NavKey

/** 面包屑根节点（协议无祖先接口，客户端维护 (dirId, name) 栈，M2 计划）。 */
private val BREADCRUMB_ROOT = 0L to "根目录"

/** (dirId, name) 栈的 Saver：展平成 [id, name, id, name...] 字符串列表过进程重建。 */
private val BreadcrumbSaver: Saver<List<Pair<Long, String>>, Any> = Saver(
    save = { stack -> ArrayList<Any>(stack.size * 2).apply { stack.forEach { (id, name) -> add(id); add(name) } } },
    restore = { flat ->
        (flat as List<*>).chunked(2).mapNotNull { pair ->
            (pair.getOrNull(0) as? Long)?.let { id -> id to (pair.getOrNull(1) as? String).orEmpty() }
        }
    },
)

@Composable
fun PanXApp(container: AppContainer) {
    LifecycleResumeEffect(container) {
        container.synchronizeAccountInfo()
        onPauseOrDispose { }
    }
    val settings by container.settings.state.collectAsStateWithLifecycle()
    PanXTheme(settings) {
        val context = LocalContext.current
        fun copyToClipboard(label: String, text: String) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        }
        val diagnostics: DiagnosticsViewModel = viewModel(key = "diagnostics", factory = viewModelFactory {
            DiagnosticsViewModel(
                snapshotProvider = { container.diagnosticsRepository.snapshot() },
                clearPreview = { container.diagnosticsRepository.clearPreviewCache() },
            )
        })
        val transfers: TransferViewModel = viewModel(factory = viewModelFactory {
            TransferViewModel(
                source = container.transferRepository,
                stopBackground = container::stopTransferBackground,
            )
        })
        val shares: ShareViewModel = viewModel(factory = viewModelFactory {
            ShareViewModel(
                repository = container.shareRepository,
                session = container.shareRepository.sessionState,
                autoRefresh = false,
                copy = { text ->
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("123PanX 分享链接", text),
                    )
                },
            )
        })
        val account: AccountViewModel = viewModel(factory = viewModelFactory {
            AccountViewModel(container.authRepository, container.accountManager, container.database.accountDao().observeAccounts())
        })
        val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory { SettingsViewModel(container.settings, container.logger, container.proxySettings, container.maintenanceRepository) })
        val updateVm: io.github.bileizhen.pan123x.feature.about.UpdateViewModel = viewModel(factory = viewModelFactory { io.github.bileizhen.pan123x.feature.about.UpdateViewModel(container.appUpdates, container.updateDownloader, container.updateInstaller) })
        LaunchedEffect(updateVm) {
            val stored = container.settings.awaitLoaded()
            container.proxySettings.awaitLoaded()
            updateVm.checkAtStartup(container.automaticUpdates && stored.autoCheckUpdates)
        }
        LifecycleResumeEffect(updateVm) {
            updateVm.onResume()
            onPauseOrDispose { }
        }
        var showLogExport by rememberSaveable { mutableStateOf(false) }
        val recycleVm: RecycleViewModel = viewModel(factory = RecycleViewModel.Factory(container.recycleRepository))
        val backStack = rememberNavBackStack(Home)
        val pageBackActions = remember { PageBackActions() }
        val usePredictiveBack = settings.predictiveBack && Build.VERSION.SDK_INT >= 34
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        // 面包屑栈属于导航层：进入目录 push、返回/面包屑截断 pop，文件页只读消费。
        var breadcrumbStack by rememberSaveable(stateSaver = BreadcrumbSaver) { mutableStateOf(listOf(BREADCRUMB_ROOT)) }
        val navigateBack = {
            val leaving = backStack.lastOrNull()
            if (leaving is Page && leaving.kind == "directory" && breadcrumbStack.size > 1) {
                breadcrumbStack = breadcrumbStack.dropLast(1)
            }
            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            Unit
        }
        fun open(kind: String, id: String = "") {
            if (kind == "export-logs") showLogExport = true else backStack.add(Page(kind, id))
        }
        fun openDirectory(id: Long, name: String) {
            breadcrumbStack = breadcrumbStack + (id to name)
            open("directory", id.toString())
        }
        /** 面包屑截断：栈裁到目标层，返回栈同步弹到对应页面（根即 Home）。 */
        fun ancestor(id: Long) {
            val depth = breadcrumbStack.indexOfFirst { it.first == id }
            if (depth >= 0 && breadcrumbStack.lastIndex > depth) breadcrumbStack = breadcrumbStack.take(depth + 1)
            val target: NavKey = if (id == 0L) Home else Page("directory", id.toString())
            val position = backStack.indexOfLast { it == target }
            if (position >= 0) while (backStack.lastIndex > position) backStack.removeAt(backStack.lastIndex)
        }
        // 移动 / 复制的目标目录选择器工厂：FilesScreen 打开选择器时经 viewModel(key="directory-picker")
        // 实例化，打开即 reset 回根目录。
        val directoryPickerFactory = remember {
            viewModelFactory { DirectoryPickerViewModel(container.fileRepository, container.logger) }
        }
        val filesViewModel: @Composable (Long) -> FilesViewModel = { directoryId ->
            viewModel(
                key = "files-$directoryId",
                factory = viewModelFactory {
                    FilesViewModel(
                        dirId = directoryId,
                        repository = container.fileRepository,
                        manager = container.accountManager,
                        accounts = container.database.accountDao().observeAccounts(),
                        logger = container.logger,
                        ops = container.fileOpsRepository,
                        downloads = container.downloadLauncher,
                        uploads = container.uploadLauncher,
                        shares = container.shareLauncher,
                        askDownloadLocation = { container.settings.state.value.askDownloadLocation },
                    )
                },
            )
        }
        val filesContent: @Composable (Long) -> Unit = { directoryId ->
            val directoryVm = filesViewModel(directoryId)
            FilesScreen(
                viewModel = directoryVm,
                directoryPickerFactory = directoryPickerFactory,
                directoryId = directoryId,
                breadcrumbs = breadcrumbStack.drop(1),
                onOpenDirectory = ::openDirectory,
                onOpenFile = { id, kind ->
                    // M6 预览分发：按文件类型进对应预览页，其余落详情。
                    when (kind) {
                        FileKind.IMAGE -> open("image", id.toString())
                        FileKind.VIDEO, FileKind.AUDIO -> open("player", id.toString())
                        FileKind.PDF -> open("pdf", id.toString())
                        FileKind.TEXT -> open("text", id.toString())
                        else -> open("file", id.toString())
                    }
                },
                onOpenAncestor = ::ancestor,
                onBack = if (directoryId == 0L) null else { { (pageBackActions.current ?: navigateBack).invoke() } },
                onOpenInfo = { open("cloud-info") },
                onOpenLogin = { open("login") },
                onDownloadsQueued = { message ->
                    transfers.showQueuedDownloads(message)
                    selectedTab = 1
                    breadcrumbStack = listOf(BREADCRUMB_ROOT)
                    while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                },
            )
        }
        Scaffold {
            CompositionLocalProvider(LocalPageBackActions provides pageBackActions) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                        onBack = navigateBack,
                        entryProvider = entryProvider {
                            entry<Home> {
                                CompositionLocalProvider(LocalPageActive provides (backStack.lastOrNull() == Home)) {
                                    val rootFiles = filesViewModel(0L)
                                    val rootFileState by rootFiles.uiState.collectAsStateWithLifecycle()
                                    MainPages(selectedTab, settings.floatingBar, settings.blur, settings.liquidGlass, settings.uiScale,
                                        hideBottomBar = selectedTab == 0 && rootFileState.selectMode,
                                        onSelect = {
                                            if (it != 0) rootFiles.clearSelection()
                                            selectedTab = it
                                            // 回到文件一级页即回到根目录视图（一级页 = 根）。
                                            if (it == 0) breadcrumbStack = listOf(BREADCRUMB_ROOT)
                                        }) {
                                        tab ->
                                        if (tab == 0) filesContent(0L)
                                        else if (tab == 1) TransferScreen(transfers, onOpenDetail = { open("transfer", it) })
                                        else ScreenFrame(listOf("123PanX", "传输", "分享", "我的")[tab]) {
                                            when (tab) {
                                                0 -> filesContent(0L)
                                                2 -> ShareScreen(shares, onOpenLogin = { open("login") })
                                                else -> AccountScreen(account, onOpen = { open(it) }, updates = updateVm)
                                            }
                                        }
                                    }
                                }
                            }
                            entry<Page> { page ->
                                val title = when (page.kind) {
                                    "proxy" -> "代理"; "settings" -> "设置"; "appearance" -> "外观"; "about" -> "关于"
                                    "accounts" -> "账户管理"; "offline" -> "离线下载"; "recycle" -> "回收站"
                                    "cloud-info" -> "云盘信息"
                                    "shared-files" -> "分享文件"
                                    "license" -> "开源许可"; "notices" -> "第三方声明"
                                    "privacy" -> "隐私"
                                    "transfer" -> "传输详情"; "login" -> "登录"; "diagnostics" -> "诊断"
                                    "image" -> "图片预览"; "player" -> "播放"; "pdf" -> "PDF 预览"; "text" -> "文本预览"
                                    "directory" -> breadcrumbStack.lastOrNull()?.second?.takeIf { it != BREADCRUMB_ROOT.second } ?: "文件"
                                    else -> "文件详情"
                                }
                                CompositionLocalProvider(LocalPageActive provides (backStack.lastOrNull() == page)) {
                                    if (page.kind == "about") {
                                        AboutScreen(onBack = navigateBack, onLicense = { open("license") }, onNotices = { open("notices") }, onPrivacy = { open("privacy") }, enableBlur = settings.blur)
                                    } else if (page.kind == "directory") filesContent(page.id.toLong())
                                    else if (page.kind == "shared-files") {
                                        // id 携带 "url|password"（SharedLink.url 由解析器规范化，不含 '|'）。
                                        val sharedVm: SharedFilesViewModel = viewModel(
                                            key = "shared-files-${page.id}",
                                            factory = viewModelFactory {
                                                SharedFilesViewModel(
                                                    link = io.github.bileizhen.pan123x.core.share.SharedLink(page.id.substringBefore('|'), page.id.substringAfter('|', "")),
                                                    actions = container.sharedFilesRepository,
                                                )
                                            },
                                        )
                                        SharedFilesScreen(
                                            viewModel = sharedVm,
                                            directoryPickerFactory = directoryPickerFactory,
                                            onBack = { (pageBackActions.current ?: navigateBack).invoke() },
                                            onOpenLogin = { open("login") },
                                            onDownloadsQueued = {
                                                transfers.showQueuedDownloads()
                                                selectedTab = 1
                                                breadcrumbStack = listOf(BREADCRUMB_ROOT)
                                                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                            },
                                        )
                                    }
                                    else ScreenFrame(title, { (pageBackActions.current ?: navigateBack).invoke() }) {
                                        when (page.kind) {
                                            "directory" -> filesContent(page.id.toLong())
                                            "file" -> {
                                                val detailVm: FileDetailViewModel = viewModel(
                                                    key = "file-${page.id}",
                                                    factory = viewModelFactory {
                                                        FileDetailViewModel(
                                                            fileId = page.id.toLong(),
                                                            cloudFileDao = container.database.cloudFileDao(),
                                                            manager = container.accountManager,
                                                        )
                                                    },
                                                )
                                                FileDetailScreen(detailVm)
                                            }
                                            // M6 预览页：共用 PreviewViewModel（取缓存行 → 解析直链），
                                            // 差异只在渲染层；返回键由 ScreenFrame 统一提供。
                                            "image", "player", "pdf", "text" -> {
                                                val previewVm: PreviewViewModel = viewModel(
                                                    key = "preview-${page.kind}-${page.id}",
                                                    factory = viewModelFactory {
                                                        PreviewViewModel(
                                                            fileId = page.id.toLong(),
                                                            cloudFileDao = container.database.cloudFileDao(),
                                                            manager = container.accountManager,
                                                            resolver = container.panDownloadResolver,
                                                        )
                                                    },
                                                )
                                                when (page.kind) {
                                                    "image" -> ImagePreviewScreen(previewVm.state)
                                                    "player" -> PlayerScreen(previewVm.state, container.previewHttpClient)
                                                    // 文本/PDF 上限与拉流客户端从容器注入。
                                                    "pdf" -> PdfPreviewScreen(
                                                        state = previewVm.state,
                                                        maxBytes = settings.maxTextPreviewBytes,
                                                        loader = { url, expectedSize ->
                                                            container.pdfPreviewCache.ensure(
                                                                page.id.toLong(), url, expectedSize, settings.maxTextPreviewBytes,
                                                            )
                                                        },
                                                    )
                                                    else -> TextPreviewScreen(
                                                        state = previewVm.state,
                                                        maxBytes = settings.maxTextPreviewBytes,
                                                        client = container.previewHttpClient,
                                                    )
                                                }
                                            }
                                            "transfer" -> TransferDetailScreen(transfers, page.id)
                                            "settings" -> SettingsScreen(settingsVm, onAppearance = { open("appearance") }, onAccounts = { open("accounts") }, onProxy = { open("proxy") })
                                            "proxy" -> io.github.bileizhen.pan123x.feature.settings.ProxyScreen(settingsVm)
                                            "cloud-info" -> CloudInfoScreen(account, onOpen = { open(it) })
                                            "appearance" -> AppearanceScreen(settingsVm)
                                            "license" -> LicenseScreen("LICENSE")
                                            "notices" -> LicenseScreen("THIRD_PARTY_NOTICES.md")
                                            "privacy" -> PrivacyScreen()
                                            // 登录成功后返回上一页（"我的"/文件页），登录态经 SessionState 自动切换，
                                            // 文件页检测到会话就绪且无缓存时会自动刷新。
                                            "login" -> {
                                                val loginVm: LoginViewModel = viewModel(key = "login", factory = LoginViewModel.Factory(container.authRepository))
                                                LoginScreen(loginVm, onSuccess = navigateBack)
                                            }
                                            // M7：离线下载（含秒传导入双 Tab）与账户管理真身。
                                            "offline" -> {
                                                val offlineVm: OfflineViewModel = viewModel(
                                                    key = "offline",
                                                    factory = viewModelFactory { OfflineViewModel(container.offlineRepository) },
                                                )
                                                val rapidVm: RapidImportViewModel = viewModel(
                                                    key = "rapid-import",
                                                    factory = viewModelFactory { RapidImportViewModel(container.rapidRepository) },
                                                )
                                                OfflineScreen(offlineVm, rapidVm, directoryPickerFactory)
                                            }
                                            "accounts" -> {
                                                val accountsVm: AccountsViewModel = viewModel(
                                                    key = "accounts-manage",
                                                    factory = viewModelFactory {
                                                        AccountsViewModel(
                                                            auth = container.authRepository,
                                                            credentials = container.credentialStore,
                                                            accounts = container.database.accountDao().observeAccounts(),
                                                            manager = container.accountManager,
                                                        )
                                                    },
                                                )
                                                AccountsScreen(accountsVm, onAddAccount = { open("login") })
                                            }
                                            "diagnostics" -> DiagnosticsScreen(diagnostics, copy = { text -> copyToClipboard("123PanX 诊断日志", text) })
                                            "recycle" -> RecycleScreen(recycleVm, onOpenLogin = { open("login") })
                                            else -> EmptyState("页面不可用", "请返回上一页后重试。")
                                        }
                                    }
                                }
                            }
                        },
                    )
                    io.github.bileizhen.pan123x.feature.logs.SendLogDialog(showLogExport, container.diagnosticReport) { showLogExport = false }
                    io.github.bileizhen.pan123x.feature.about.UpdateDialog(updateVm)
                    io.github.bileizhen.pan123x.ui.component.ClipboardSharePrompt(settings.recognizeShareClipboard, onOpenInApp = { link ->
                        // 应用内查看分享：页面 id 携带规范化 URL 与提取码。
                        open("shared-files", "${link.url}|${link.password}")
                    })
                    NavigationBackHandler(
                        state = rememberNavigationEventState(NavigationEventInfo.None),
                        isBackEnabled = pageBackActions.current != null || (backStack.size > 1 && !usePredictiveBack),
                        onBackCompleted = { (pageBackActions.current ?: navigateBack).invoke() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MainPages(
    selected: Int, floating: Boolean, blur: Boolean, liquid: Boolean, uiScale: Float,
    onSelect: (Int) -> Unit, hideBottomBar: Boolean = false, content: @Composable (Int) -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val labels = listOf("文件", "传输", "分享", "我的").map(uiText)
    val icons = listOf(PanIcons.Folder, PanIcons.Transfer, PanIcons.Share, PanIcons.Account)
    val pageStates = rememberSaveableStateHolder()
    val animatedPages: @Composable () -> Unit = {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(280)) { direction * it / 12 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { -direction * it / 12 }) using SizeTransform(clip = false)
            },
            modifier = Modifier.fillMaxSize(), label = "primaryPages",
        ) { page ->
            CompositionLocalProvider(LocalPageActive provides (LocalPageActive.current && page == selected)) {
                pageStates.SaveableStateProvider(page) { content(page) }
            }
        }
    }
    val hardwareAccelerated = LocalView.current.isHardwareAccelerated
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Keep the 840dp window threshold independent of the user's visual scale.
        if (maxWidth * uiScale >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                MainSidebar(selected, labels, icons, onSelect)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CompositionLocalProvider(LocalContentBottomPadding provides 24.dp) { animatedPages() }
                }
            }
        } else if (floating && Build.VERSION.SDK_INT >= 33 && hardwareAccelerated) {
            CompositionLocalProvider(LocalContentBottomPadding provides if (hideBottomBar) 16.dp else 100.dp) {
                HighApiFloatingNavigation(selected, labels, icons, onSelect, blur, liquid, visible = !hideBottomBar, content = animatedPages)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalContentBottomPadding provides if (hideBottomBar) 16.dp else if (floating) 100.dp else 88.dp) { animatedPages() }
                androidx.compose.animation.AnimatedVisibility(!hideBottomBar, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it }, exit = fadeOut(tween(100)) + slideOutVertically(tween(160)) { it }) {
                    if (floating) Box(Modifier.navigationBarsPadding().padding(horizontal = 26.dp, vertical = 12.dp).widthIn(max = 480.dp)) {
                        PlainFloatingBar(selected, labels, icons, onSelect)
                    } else StandardNavigationBar(selected, labels, icons, onSelect)
                }
            }
        }
    }
}
