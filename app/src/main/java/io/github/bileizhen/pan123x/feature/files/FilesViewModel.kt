package io.github.bileizhen.pan123x.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.transfer.download.DownloadLauncher
import io.github.bileizhen.pan123x.core.transfer.download.DownloadMessages
import io.github.bileizhen.pan123x.core.transfer.download.LaunchOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.UploadEnqueueOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.UploadLauncher
import io.github.bileizhen.pan123x.core.share.ShareLauncher
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidCodec
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidExport
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidFile
import io.github.bileizhen.pan123x.data.share.ShareOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.UploadMessages
import io.github.bileizhen.pan123x.data.file.DirectorySnapshot
import io.github.bileizhen.pan123x.data.file.FileRepository
import io.github.bileizhen.pan123x.data.file.FileOpsRepository
import io.github.bileizhen.pan123x.data.file.OpsOutcome
import io.github.bileizhen.pan123x.data.file.RefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** 排序字段（M2 计划：服务端恒 file_id desc，排序纯客户端）。 */
enum class FileSortField(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    DATE("修改时间"),
}

/**
 * 文件页状态。files 是**当前搜索 + 排序后**的展示列表；
 * 原始目录快照只留在 ViewModel 内。offlineCache 表示"有缓存但最近一次刷新失败"，
 * 此时保留旧列表并提示，不清空。
 */
data class FilesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val files: List<CloudFileEntity> = emptyList(),
    val search: String = "",
    val sortField: FileSortField = FileSortField.NAME,
    val ascending: Boolean = true,
    val grid: Boolean = false,
    val restoring: Boolean = true,
    val loggedOut: Boolean = false,
    val account: AccountEntity? = null,
    val offlineCache: Boolean = false,
    val error: String? = null,
    // M3 多选与文件操作：selected 以 fileId 为稳定 key。
    val selected: Map<Long, CloudFileEntity> = emptyMap(),
    val selectMode: Boolean = false,
    val opsBusy: Boolean = false,
    val opsMessage: String? = null,
    val choosingDownloadLocation: Boolean = false,
    // M6：创建分享成功后的链接（null = 无待展示）；失败走 opsMessage。
    val shareLink: String? = null,
    val sharePassword: String = "",
    // M7：秒传导出数据（null = 无待展示）。
    val rapidExport: RapidExport? = null,
) {
    val empty: Boolean get() = files.isEmpty()

    /** 当前过滤列表是否已被全选（驱动"全选/反选"按钮文案）。 */
    val allSelected: Boolean get() = files.isNotEmpty() && files.all { it.fileId in selected }
}

/**
 * 客户端排序 / 搜索规则（协议真源 file_service.py：服务端只保证 file_id desc 的
 * 分页稳定序，展示顺序完全由客户端决定）：
 * - 文件夹恒置前，两段各自排序；
 * - 段内按 名称（小写比较）/ 大小 / 日期（updateAt，缺失回退 createAt）；
 * - ascending 同时决定两段方向；fileId 作稳定并列断路器。
 * 纯函数、无依赖，供 JVM 单测直接覆盖。
 */
object FileListQuery {

    fun apply(
        files: List<CloudFileEntity>,
        search: String,
        sortField: FileSortField,
        ascending: Boolean,
    ): List<CloudFileEntity> {
        val keyword = search.trim().lowercase()
        val filtered = if (keyword.isEmpty()) {
            files
        } else {
            files.filter { it.fileName.lowercase().contains(keyword) }
        }
        val fieldOrder = when (sortField) {
            FileSortField.NAME -> compareBy<CloudFileEntity> { it.fileName.lowercase() }
            FileSortField.SIZE -> compareBy { it.size }
            FileSortField.DATE -> compareBy { if (it.updateAt > 0) it.updateAt else it.createAt }
        }
        val directed = if (ascending) fieldOrder else fieldOrder.reversed()
        return filtered.sortedWith(
            compareBy<CloudFileEntity> { !it.isFolder }.then(directed).thenBy { it.fileId },
        )
    }
}

/**
 * 单个目录的文件页状态机。每个目录一个实例（PanXApp 按 files-<dirId> key 实例化），
 * 构造即开始观察 Room 缓存：有缓存立即显示；缓存为空且已登录时自动发起一次网络刷新；
 * 手动刷新由下拉 / 工具栏按钮触发。刷新失败保留旧缓存并置 offlineCache。
 *
 * M3 文件操作：多选状态（selected / selectMode）与写操作（新建 / 重命名 / 删除 / 移动 /
 * 复制）经注入的 [FileOpsRepository] 执行；目录缓存刷新由仓库的 refresher 联动，
 * ViewModel 不手动刷（避免双份刷新）。opsBusy 防重复提交；opsMessage 为一次性提示。
 */
class FilesViewModel(
    private val dirId: Long,
    private val repository: FileRepository,
    manager: AccountManager,
    accounts: Flow<List<AccountEntity>>,
    private val logger: AppLogger,
    private val ops: FileOpsRepository,
    private val downloads: DownloadLauncher,
    private val uploads: UploadLauncher,
    private val shares: ShareLauncher,
    private val askDownloadLocation: () -> Boolean = { false },
) : ViewModel() {

    private val mutableState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = mutableState.asStateFlow()
    private val downloadQueued = Channel<String?>(Channel.BUFFERED)
    val downloadQueuedEvents = downloadQueued.receiveAsFlow()
    private val chooseDownloadLocation = Channel<Unit>(Channel.BUFFERED)
    val downloadLocationRequests = chooseDownloadLocation.receiveAsFlow()

    /** 最近一次 Room 快照的原始列表，搜索 / 排序变更时在内存里重放。 */
    private var rawFiles: List<CloudFileEntity> = emptyList()
    private var observedCacheRevision = 0L
    private var pendingDownloads: List<CloudFileEntity> = emptyList()
    private var observedAccount: String? = null

    /** 已为哪个 accountId 自动刷新过：防止同一账户反复触发；换账户 / 重新登录后允许再来一次。 */
    private var autoRefreshedFor: String? = null

    init {
        // 单一 combine 流：会话标志先落、快照后处理，保证 maybeAutoRefresh 触发时
        // restoring/loggedOut 已是终值（拆两条 collector 会有快照先到、restoring 仍为
        // 初始 true 的时序窗口，自动刷新会被误吞）。
        viewModelScope.launch {
            combine(repository.observeDirectory(dirId), manager.state, accounts) { snapshot, session, list ->
                Triple(
                    snapshot,
                    session,
                    (session as? SessionState.Ready)?.let { ready ->
                        list.firstOrNull { it.accountId == ready.accountId }
                    },
                )
            }.collect { (snapshot, session, account) ->
                mutableState.update {
                    it.copy(
                        restoring = session is SessionState.Restoring,
                        loggedOut = session is SessionState.LoggedOut,
                        account = account,
                    )
                }
                onSnapshot(snapshot)
            }
        }
    }

    private fun onSnapshot(snapshot: DirectorySnapshot) {
        val accountId = repository.accountIdOfSession
        val accountChanged = observedAccount != accountId
        observedAccount = accountId
        if (accountChanged) {
            autoRefreshedFor = null
            pendingDownloads = emptyList()
        }
        if (snapshot.cacheRevision != observedCacheRevision) {
            observedCacheRevision = snapshot.cacheRevision
            autoRefreshedFor = null
            val accountId = repository.accountIdOfSession
            if (accountId != null) { autoRefreshedFor = accountId; refresh() }
        }
        rawFiles = snapshot.files.filter { it.accountId == accountId }
        val currentFiles = rawFiles.associateBy { it.fileId }
        mutableState.update { old ->
            val search = if (accountChanged) "" else old.search
            old.copy(
                loading = false,
                search = search,
                files = FileListQuery.apply(rawFiles, search, old.sortField, old.ascending),
                offlineCache = old.offlineCache && snapshot.files.isNotEmpty(),
                selected = if (accountChanged) emptyMap() else old.selected.keys.mapNotNull { id ->
                    currentFiles[id]
                }.associateBy { it.fileId },
                selectMode = old.selectMode && !accountChanged,
                choosingDownloadLocation = old.choosingDownloadLocation && !accountChanged,
                shareLink = if (accountChanged) null else old.shareLink,
                sharePassword = if (accountChanged) "" else old.sharePassword,
                rapidExport = if (accountChanged) null else old.rapidExport,
            )
        }
        maybeAutoRefresh(snapshot)
    }

    /**
     * 缓存为空且该目录从未成功加载过（无 allLoaded 状态行）时自动刷新一次；
     * "确为空目录"（allLoaded=true 且 total=0）不触发，避免空目录反复请求。
     * 失败不清缓存（仓库保证），同一账户也不循环重试。
     */
    private fun maybeAutoRefresh(snapshot: DirectorySnapshot) {
        if ((snapshot.files.isNotEmpty() || snapshot.allLoaded) && snapshot.updatedAt != 0L) return
        val accountId = repository.accountIdOfSession ?: return
        if (autoRefreshedFor == accountId) return
        autoRefreshedFor = accountId
        logger.i(
            LogSource.FILE,
            "目录 $dirId 无缓存，自动刷新（account=${accountId.hashCode()}）",
        )
        refresh()
    }

    fun setSearch(search: String) {
        mutableState.update { old ->
            old.copy(search = search, files = FileListQuery.apply(rawFiles, search, old.sortField, old.ascending))
        }
    }

    fun setSort(sortField: FileSortField) {
        mutableState.update { old ->
            old.copy(sortField = sortField, files = FileListQuery.apply(rawFiles, old.search, sortField, old.ascending))
        }
    }

    fun setAscending(ascending: Boolean) {
        mutableState.update { old ->
            old.copy(ascending = ascending, files = FileListQuery.apply(rawFiles, old.search, old.sortField, ascending))
        }
    }

    fun toggleGrid() {
        mutableState.update { it.copy(grid = !it.grid) }
    }

    /** 下拉 / 工具栏 / 空态重试共用；未登录与恢复中直接忽略，避免无意义请求。 */
    fun refresh() {
        val current = mutableState.value
        if (current.refreshing || current.restoring || current.loggedOut) return
        mutableState.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val outcome = repository.refreshDirectory(dirId)
            mutableState.update { old ->
                when (outcome) {
                    RefreshOutcome.Success -> old.copy(refreshing = false, offlineCache = false, error = null)
                    is RefreshOutcome.Failure -> old.copy(
                        refreshing = false,
                        error = outcome.userMessage,
                        // 有旧缓存时进入"离线浏览"形态：保留列表 + 细提示；无缓存则走错误空态。
                        offlineCache = old.files.isNotEmpty(),
                    )
                }
            }
        }
    }

    // ---------- 多选（快捷菜单显式进入，fileId 为稳定 key） ----------

    /** 多选中点击条目：切换选中态；不在多选模式时不生效（单击语义仍是打开）。 */
    fun toggleSelect(file: CloudFileEntity) {
        val current = currentFile(file) ?: return
        mutableState.update { old ->
            if (!old.selectMode) {
                old
            } else if (old.selected.containsKey(file.fileId)) {
                old.copy(selected = old.selected - file.fileId)
            } else {
                old.copy(selected = old.selected + (current.fileId to current))
            }
        }
    }

    /** 快捷菜单“多选”：进入多选并选中该条目。 */
    fun enterSelectMode(file: CloudFileEntity) {
        val current = currentFile(file) ?: return
        if (mutableState.value.opsBusy) return
        mutableState.update { it.copy(selectMode = true, selected = mapOf(current.fileId to current)) }
    }

    fun beginSelection() {
        if (mutableState.value.opsBusy || repository.accountIdOfSession == null) return
        mutableState.update { it.copy(selectMode = true, selected = emptyMap()) }
    }

    private fun currentFile(file: CloudFileEntity): CloudFileEntity? {
        if (repository.accountIdOfSession != file.accountId) return null
        return rawFiles.firstOrNull { it.accountId == file.accountId && it.fileId == file.fileId }
    }

    /** A context action targets one file without entering the batch-selection UI. */
    fun prepareSingleFileAction(file: CloudFileEntity): Boolean {
        if (mutableState.value.opsBusy || repository.accountIdOfSession != file.accountId) return false
        val current = rawFiles.firstOrNull { it.accountId == file.accountId && it.fileId == file.fileId } ?: return false
        mutableState.update { it.copy(selectMode = false, selected = mapOf(current.fileId to current)) }
        return true
    }

    /** 退出多选并清空选择。 */
    fun clearSelection() {
        mutableState.update { it.copy(selectMode = false, selected = emptyMap()) }
    }

    /** 全选当前过滤后列表（多选作用于展示列表，而非原始快照）。 */
    fun selectAll() {
        mutableState.update { old ->
            if (old.files.isEmpty()) old else old.copy(selectMode = true, selected = old.files.associateBy { it.fileId })
        }
    }

    /** 头部"全选/反选"：已全选则清空选择，否则全选当前过滤列表。 */
    fun invertSelection() {
        mutableState.update { old ->
            if (old.allSelected) old.copy(selected = emptyMap()) else old.copy(selectMode = true, selected = old.files.associateBy { it.fileId })
        }
    }

    /** 一次性提示消费（3 秒自动清除由 UI 的 LaunchedEffect 驱动，AccountScreen 同款）。 */
    fun consumeOpsMessage() {
        mutableState.update { it.copy(opsMessage = null) }
    }

    // ---------- 文件操作（M3：全部经 FileOpsRepository，opsBusy 防重入） ----------

    fun createFolder(name: String) = launchOp { ops.createFolder(dirId, name.trim()) }

    fun rename(file: CloudFileEntity, newName: String) = launchOp { ops.rename(file, newName.trim()) }

    fun deleteSelected() = launchOp { ops.trash(currentSelection()) }

    fun moveSelected(targetDirId: Long) = launchOp { ops.move(currentSelection(), targetDirId) }

    fun copySelected(targetDirId: Long) = launchOp { ops.copy(currentSelection(), targetDirId) }

    /**
     * 把当前多选加入下载队列（M4）。逐项发起：单文件失败不影响其他项，
     * 结果聚合成"成功 N 个，失败 M 个"；全部成功才退出多选（与 [launchOp] 的语义一致）。
     * 下载本体在 DownloadCoordinator 的应用级 scope 上执行，与文件页生命周期无关。
     */
    fun downloadSelected() {
        if (mutableState.value.opsBusy || mutableState.value.choosingDownloadLocation) return
        val files = currentSelection()
        if (files.isEmpty()) return
        if (askDownloadLocation()) {
            pendingDownloads = files
            mutableState.update { it.copy(choosingDownloadLocation = true) }
            viewModelScope.launch { chooseDownloadLocation.send(Unit) }
            return
        }
        enqueueDownloads(files, null)
    }

    fun downloadLocationChosen(tree: String?, error: String? = null) {
        val files = pendingDownloads
        pendingDownloads = emptyList()
        mutableState.update { it.copy(choosingDownloadLocation = false, opsMessage = error) }
        // Cancellation and a changed account must never enqueue the previous selection.
        if (tree != null && files.isNotEmpty() && files.all { it.accountId == repository.accountIdOfSession }) enqueueDownloads(files, tree)
    }

    private fun enqueueDownloads(files: List<CloudFileEntity>, tree: String?) {
        mutableState.update { it.copy(opsBusy = true) }
        viewModelScope.launch {
            val outcomes = files.map { file -> if (tree == null) downloads.launch(file) else downloads.launch(file, tree) }
            val queued = outcomes.count { it is LaunchOutcome.Queued }
            val failure = outcomes.filterIsInstance<LaunchOutcome.Failed>().firstOrNull()
            mutableState.update { old ->
                old.copy(
                    opsBusy = false,
                    opsMessage = when {
                        queued == files.size -> "已加入下载队列：$queued 个文件"
                        queued == 0 -> failure?.userMessage ?: DownloadMessages.ENQUEUE_FAILED
                        else -> "已加入下载队列 $queued 个，失败 ${files.size - queued} 个：${failure?.userMessage.orEmpty()}"
                    },
                    selected = if (queued == files.size) emptyMap() else old.selected,
                    selectMode = if (queued == files.size) false else old.selectMode,
                )
            }
            if (queued > 0) downloadQueued.send(if (queued < files.size) mutableState.value.opsMessage else null)
        }
    }

    private fun currentSelection(): List<CloudFileEntity> = mutableState.value.selected.values.toList()

    /**
     * 把当前多选创建为一条分享链接（M6）。[sharePwd] 空 = 无密码；
     * 成功置 shareLink（文件页弹链接 + 复制），失败转 opsMessage（与下载/上传同语义）。
     * 业务选项经 ShareCreateOptions 验证；协议字段及固定参数由网络层按参考源映射。
     */
    fun shareSelected(sharePwd: String) = shareSelected(io.github.bileizhen.pan123x.core.share.ShareCreateOptions(password = sharePwd))

    fun shareSelected(options: io.github.bileizhen.pan123x.core.share.ShareCreateOptions) {
        if (mutableState.value.opsBusy) return
        options.validationError()?.let { message -> mutableState.update { it.copy(opsMessage = message) }; return }
        val files = currentSelection()
        if (files.isEmpty()) return
        mutableState.update { it.copy(opsBusy = true, opsMessage = null) }
        viewModelScope.launch {
            when (val outcome = shares.launch(files.map { it.fileId }, options)) {
                is ShareOutcome.Created -> mutableState.update { it.copy(opsBusy = false, shareLink = outcome.url, sharePassword = options.password) }
                is ShareOutcome.Failed -> mutableState.update {
                    it.copy(opsBusy = false, opsMessage = outcome.userMessage)
                }
            }
        }
    }

    /** 链接弹窗已关闭（复制或关闭按钮），清掉待展示链接。 */
    fun consumeShareLink() {
        mutableState.update { it.copy(shareLink = null, sharePassword = "") }
    }

    /**
     * 把当前多选生成秒传数据（M7）：path 取文件名（多选来自扁平目录，无层级）、
     * etag/size 取云端字段；文件夹与无有效 etag 的条目被 [RapidCodec.export] 过滤，
     * 全部无效时其 IAE 转为 opsMessage。导出纯客户端，不发任何请求。
     */
    fun exportRapid() {
        if (mutableState.value.opsBusy) return
        val files = currentSelection()
            .filterNot { it.isFolder }
            .map { RapidFile(path = it.fileName, etag = it.etag, size = it.size) }
        if (files.isEmpty()) {
            mutableState.update { it.copy(opsMessage = "请先选择要导出的文件") }
            return
        }
        viewModelScope.launch {
            val outcome = runCatching { RapidCodec.export(files) }
                .getOrElse { error ->
                    mutableState.update { it.copy(opsMessage = error.message ?: "无法生成秒传数据") }
                    return@launch
                }
            mutableState.update { it.copy(rapidExport = outcome) }
        }
    }

    /** 秒传导出弹窗已关闭（复制或关闭），清掉待展示数据。 */
    fun consumeRapidExport() {
        mutableState.update { it.copy(rapidExport = null) }
    }

    /**
     * 把 SAF 选择器返回的本地文件加入上传队列（M5）。逐项发起：单文件失败不影响
     * 其他项，结果聚合成"成功 N 个，失败 M 个"（与 [downloadSelected] 同语义）。uri 以字符串
     * 传入保持 ViewModel 纯 JVM 可测；来源解析（SAF query）在 UploadLauncher 内完成。
     * 上传本体在 UploadCoordinator 的应用级 scope 上执行，与文件页生命周期无关；
     * 同名冲突不在此处理——引擎转 WAITING_USER 后由传输工作台的冲突弹窗让用户选择。
     */
    fun uploadUris(uriStrings: List<String>) {
        if (mutableState.value.opsBusy) return
        if (uriStrings.isEmpty()) return
        mutableState.update { it.copy(opsBusy = true) }
        viewModelScope.launch {
            val outcomes = uriStrings.map { uri -> uploads.launch(uri, dirId) }
            val queued = outcomes.count { it is UploadEnqueueOutcome.Queued }
            val failure = outcomes.filterIsInstance<UploadEnqueueOutcome.Failed>().firstOrNull()
            mutableState.update { old ->
                old.copy(
                    opsBusy = false,
                    opsMessage = when {
                        queued == uriStrings.size -> "已加入上传队列：$queued 个文件"
                        queued == 0 -> failure?.userMessage ?: UploadMessages.ENQUEUE_FAILED
                        else -> "已加入上传队列 $queued 个，失败 ${uriStrings.size - queued} 个：${failure?.userMessage.orEmpty()}"
                    },
                )
            }
        }
    }

    /**
     * 写操作统一入口：进行中直接忽略重复触发；结果映射为一次性 opsMessage
     * （Failure 显示仓库的用户可读文案，Success 显示聚合文案或"操作成功"）。
     * 成功后退出多选（缓存刷新由仓库 refresher 联动，Flow 自动更新列表，
     * 不允许 UI 假成功）；失败保留选择便于重试。意外异常不吞，转用户文案并记日志。
     */
    private fun launchOp(block: suspend () -> OpsOutcome) {
        if (mutableState.value.opsBusy) return
        mutableState.update { it.copy(opsBusy = true) }
        viewModelScope.launch {
            val outcome = try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e(LogSource.FILE, "文件操作异常：${error.javaClass.simpleName}")
                OpsOutcome.Failure("操作失败，请稍后重试")
            }
            mutableState.update { old ->
                old.copy(
                    opsBusy = false,
                    opsMessage = when (outcome) {
                        is OpsOutcome.Success -> outcome.message ?: "操作成功"
                        is OpsOutcome.Failure -> outcome.userMessage
                    },
                    selected = if (outcome is OpsOutcome.Success) emptyMap() else old.selected,
                    selectMode = if (outcome is OpsOutcome.Success) false else old.selectMode,
                )
            }
        }
    }
}
