package io.github.bileizhen.pan123x.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.database.TransferDirection
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.database.TransferTaskEntity
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.data.transfer.TransferPartView
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 传输工作台的数据接缝（feature 层不直接碰 DAO / OkHttp）。
 *
 * 为什么抽接口：[TransferRepository] 是具体类且携带 android Context，而传输页状态机必须在
 * 纯 JVM 单测中验证。VM 只依赖本接口，测试注入假实现；接口刻意定义在 VM 文件内，
 * 由仓库反向实现——消费者定义接口是 seams 的惯用放法（接口方法随 VM 需求演进，不随仓库演进）。
 */
interface TransferTasksSource {
    fun observeTasks(): Flow<List<TransferTaskEntity>>

    fun observeParts(accountId: String, taskId: String): Flow<List<TransferPartView>>

    suspend fun pause(accountId: String, taskId: String)

    suspend fun resume(accountId: String, taskId: String)

    suspend fun cancel(accountId: String, taskId: String)

    suspend fun resolveConflict(accountId: String, taskId: String, policy: ConflictPolicy)

    suspend fun clearFinished(accountId: String)
}

/** 工作台筛选（进行中/已停止/已完成/全部）。 */
enum class TransferFilter(val label: String) {
    ACTIVE("进行中"),
    STOPPED("已停止"),
    COMPLETED("已完成"),
    ALL("全部"),
}

/** 页面内容状态（显式区分 Loading / Empty / Content，不加载中冒充空态）。 */
enum class TransferContentStatus { LOADING, EMPTY, CONTENT }

/** "进行中"= 会自己推进的状态；"已停止"= 需要用户/网络才能继续的状态。 */
val ACTIVE_TRANSFER_STATES: Set<TransferState> = setOf(
    TransferState.QUEUED,
    TransferState.RESOLVING,
    TransferState.RUNNING,
    TransferState.COMPLETING,
)

private val STOPPED_TRANSFER_STATES: Set<TransferState> = setOf(
    TransferState.PAUSED,
    TransferState.WAITING_NETWORK,
    TransferState.WAITING_USER,
    TransferState.FAILED,
)

/** 筛选分类 → 任务可见性。CANCELED 只出现在"全部"（的分类映射）。 */
fun TransferTaskEntity.matches(filter: TransferFilter): Boolean = when (filter) {
    TransferFilter.ACTIVE -> state in ACTIVE_TRANSFER_STATES
    TransferFilter.STOPPED -> state in STOPPED_TRANSFER_STATES
    TransferFilter.COMPLETED -> state == TransferState.COMPLETED
    TransferFilter.ALL -> true
}

/** 方向的中文展示（状态机是英文枚举，只在展示层转中文）。 */
fun TransferDirection.label(): String = when (this) {
    TransferDirection.DOWNLOAD -> "下载"
    TransferDirection.UPLOAD -> "上传"
}

/**
 * WAITING_USER 且 error 指向同名冲突（[io.github.bileizhen.pan123x.core.transfer.upload.UploadMessages]
 * `.CONFLICT_TITLE` 的"同名"字样）→ 需要用户选择保留两者/覆盖。
 *
 * 不能只看 WAITING_USER：进程重启恢复也会把任务转成 WAITING_USER，那不是冲突。
 */
val TransferTaskEntity.isConflict: Boolean
    get() = state == TransferState.WAITING_USER && error?.contains(CONFLICT_MARKER) == true

/** 冲突判别标记；显示层与上传域文案（UploadMessages.CONFLICT_TITLE"存在同名文件"）保持同口径。 */
private const val CONFLICT_MARKER = "同名"

/** 速度采样拍间隔（UI 高频状态与持久化分离，1s 差分足够展示且开销可忽略）。 */
private const val SAMPLE_INTERVAL_MS = 1_000L

/** 状态的中文展示文案（展示层转换，状态机本身保持英文枚举）。 */
fun TransferState.label(): String = when (this) {
    TransferState.QUEUED -> "排队中"
    TransferState.RESOLVING -> "解析中"
    TransferState.RUNNING -> "进行中"
    TransferState.PAUSED -> "已暂停"
    TransferState.WAITING_NETWORK -> "等待网络"
    TransferState.WAITING_USER -> "等待确认"
    TransferState.COMPLETING -> "正在完成"
    TransferState.COMPLETED -> "已完成"
    TransferState.FAILED -> "失败"
    TransferState.CANCELED -> "已取消"
}

data class TransferUiState(
    val tasks: List<TransferTaskEntity> = emptyList(),
    val visible: List<TransferTaskEntity> = emptyList(),
    val filter: TransferFilter = TransferFilter.ACTIVE,
    val search: String = "",
    val searchOpen: Boolean = false,
    val actionError: String? = null,
    /** taskId → 速度估算（字节/秒）。拍间差分的展示值，非权威数据（图表仅消费状态）。 */
    val speeds: Map<String, Long> = emptyMap(),
    val downloadSpeed: Long = 0,
    val uploadSpeed: Long = 0,
    val activeCount: Int = 0,
    val status: TransferContentStatus = TransferContentStatus.LOADING,
    val cancelTaskId: String? = null,
    /** WAITING_USER 且 error 含"同名"的上传任务，驱动冲突弹窗；为 null 表示无待处理冲突。 */
    val conflictTask: TransferTaskEntity? = null,
)

private data class TransferQuery(val filter: TransferFilter = TransferFilter.ACTIVE, val search: String = "", val error: String? = null, val searchOpen: Boolean = false)

/**
 * 传输工作台 ViewModel。
 *
 * 为什么不是 AndroidViewModel：纯 JVM 单测无法构造 Application/Context（android.jar 为 stub，
 *），而 VM 只需要"通知后台收尾"这一个副作用。因此 [stopBackground] 由应用注入
 * （PanXApp 里转发 `TransferBackgroundLauncher.stop(appContext, taskId)`），VM 保持零 android.* 依赖。
 *
 * 速度估算：observeTasks 的 Room 流本身按写入节流（协调器 400ms），但写入只在字节变化时发生，
 * 频率不稳定；速度改为固定 1s 采样拍对 downloadedBytes 做差分并指数平滑（α=0.5）。这是
 * **展示用估算**，与传输线程零耦合——图表/汇总只消费状态，不会反向阻塞传输。
 */
class TransferViewModel(
    private val source: TransferTasksSource,
    private val stopBackground: (String) -> Unit = {},
    private val sampleIntervalMs: Long = SAMPLE_INTERVAL_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val query = MutableStateFlow(TransferQuery())
    private val cancelId = MutableStateFlow<String?>(null)
    private val speeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** 最新任务快照；null = 尚未收到首帧（对应旧 ready 标志，折叠进来以保住 combine 的 5 流强类型重载）。 */
    private val latestTasks = MutableStateFlow<List<TransferTaskEntity>?>(null)

    /**
     * 用户已点"取消"关闭的冲突任务；任务离开 WAITING_USER 后自动清除豁免（避免旧豁免遮蔽新冲突）。
     * 必须是 combine 的**真实输入**：StateFlow 对 equals 相等的值会跳过发射，若只把它当普通字段、
     * 靠"重灌任务列表"触发重算，弹窗将永远不消失（列表内容没变 → 发射被吞）。
     */
    private val dismissedConflicts = MutableStateFlow<Set<String>>(emptySet())

    /** 上一采样拍快照；null 表示尚未建立基线，下一拍才有差分。 */
    private var lastSample: Sample? = null

    /** 上一帧的活跃任务集合，用于"离开活跃态"跳变沿检测。 */
    private val previouslyActive = mutableSetOf<String>()

    val uiState: StateFlow<TransferUiState> = combine(
        latestTasks,
        query,
        cancelId,
        speeds,
        dismissedConflicts,
    ) { tasks, query, cancelId, speeds, dismissed ->
        val list = tasks.orEmpty()
        val visible = list.filter { it.matches(query.filter) && it.fileName.contains(query.search.trim(), ignoreCase = true) }
        val active = list.filter { it.state in ACTIVE_TRANSFER_STATES }
        val conflictCandidate = list.firstOrNull { it.isConflict && it.taskId !in dismissed }
        // 冲突豁免清理：任务已离开等待态（含被删除）时丢弃豁免，任务重新冲突时弹窗能再次出现。
        // 只在集合真正缩小时回写——值未变时 StateFlow 会跳过发射，不会形成重算循环。
        val stillWaiting = dismissed.filterTo(mutableSetOf()) { id ->
            list.any { it.taskId == id && it.state == TransferState.WAITING_USER }
        }
        if (stillWaiting.size != dismissed.size) dismissedConflicts.value = stillWaiting
        TransferUiState(
            tasks = list,
            visible = visible,
            filter = query.filter,
            search = query.search,
            searchOpen = query.searchOpen,
            actionError = query.error,
            speeds = speeds,
            downloadSpeed = active
                .filter { it.direction == TransferDirection.DOWNLOAD }
                .sumOf { speeds[it.taskId] ?: 0L },
            uploadSpeed = active
                .filter { it.direction == TransferDirection.UPLOAD }
                .sumOf { speeds[it.taskId] ?: 0L },
            activeCount = active.size,
            status = when {
                tasks == null -> TransferContentStatus.LOADING
                visible.isEmpty() -> TransferContentStatus.EMPTY
                else -> TransferContentStatus.CONTENT
            },
            cancelTaskId = cancelId,
            conflictTask = conflictCandidate?.takeIf { it.taskId !in dismissed },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TransferUiState())

    init {
        viewModelScope.launch {
            source.observeTasks().collect { tasks ->
                latestTasks.value = tasks
                releaseDeparted(tasks)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(sampleIntervalMs)
                sample()
            }
        }
    }

    // ---- 查询 ----

    /** 详情页分片点阵数据流（DOWNLOAD=分段 / UPLOAD=分片，统一为 [TransferPartView]）。 */
    fun observeParts(accountId: String, taskId: String): Flow<List<TransferPartView>> =
        source.observeParts(accountId, taskId)

    // ---- 用户动作 ----

    fun selectFilter(value: TransferFilter) {
        query.update { it.copy(filter = value) }
    }

    fun setSearch(value: String) { query.update { it.copy(search = value) } }
    fun setSearchOpen(open: Boolean) { query.update { it.copy(searchOpen = open, search = if (open) it.search else "") } }
    fun showQueuedDownloads(message: String? = null) { query.update { it.copy(filter = TransferFilter.ACTIVE, search = "", searchOpen = false, error = message) } }
    fun dismissError() { query.update { it.copy(error = null) } }
    fun pauseVisible() { uiState.value.visible.filter { it.state in ACTIVE_TRANSFER_STATES }.forEach { pause(it.taskId) } }
    fun resumeVisible() { uiState.value.visible.filter { it.state in setOf(TransferState.PAUSED, TransferState.WAITING_NETWORK, TransferState.FAILED) }.forEach { resume(it.taskId) } }

    fun pause(taskId: String) = withTaskAccount(taskId) { account -> source.pause(account, taskId) }

    fun resume(taskId: String) = withTaskAccount(taskId) { account -> source.resume(account, taskId) }

    fun requestCancel(taskId: String) {
        cancelId.value = taskId
    }

    fun dismissCancel() {
        cancelId.value = null
    }

    /** 取消的二次确认（删除类操作必须二次确认；取消会丢弃断点数据）。 */
    fun confirmCancel() {
        val taskId = cancelId.value ?: return
        cancelId.value = null
        withTaskAccount(taskId) { account -> source.cancel(account, taskId) }
    }

    fun dismissConflict() {
        uiState.value.conflictTask?.let { dismissedConflicts.value = dismissedConflicts.value + it.taskId }
    }

    fun resolveConflictKeepBoth(taskId: String) = resolveConflict(taskId, ConflictPolicy.KEEP_BOTH)

    fun resolveConflictOverwrite(taskId: String) = resolveConflict(taskId, ConflictPolicy.OVERWRITE)

    fun clearFinished() {
        // VM 不持有 AccountManager；任务行自带 accountId，任取一行即为当前账户。
        val account = latestTasks.value.orEmpty().firstOrNull()?.accountId ?: return
        runAction { source.clearFinished(account) }
    }

    // ---- 内部 ----

    private fun resolveConflict(taskId: String, policy: ConflictPolicy) {
        dismissedConflicts.value = dismissedConflicts.value - taskId
        withTaskAccount(taskId) { account -> source.resolveConflict(account, taskId, policy) }
    }

    /** accountId 从任务行解析（VM 不依赖 AccountManager）；行已被清理时动作安全跳过。 */
    private fun withTaskAccount(taskId: String, action: suspend (String) -> Unit) {
        val account = latestTasks.value.orEmpty().firstOrNull { it.taskId == taskId }?.accountId ?: return
        runAction { action(account) }
    }

    private fun runAction(action: suspend () -> Unit) {
        query.update { it.copy(error = null) }
        viewModelScope.launch {
            try { action() }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) { query.update { it.copy(error = "操作未完成，请稍后重试") } }
        }
    }

    /**
     * 任务离开活跃态（暂停/失败/冲突等待/完成/取消）时通知后台执行器收尾。
     *
     * 去重语义是**跳变沿**：只在"活跃 → 非活跃"的一帧触发一次 stop，停留在非活跃的后续帧不再
     * 重复调用；若任务被用户重新激活（resume 会再次 start），之后的下一次离开会再次触发，
     * 否则后台 job 会随暂停/完成循环泄漏。
     */
    private fun releaseDeparted(tasks: List<TransferTaskEntity>) {
        val active: Set<String> = tasks
            .filter { it.state in ACTIVE_TRANSFER_STATES }
            .mapTo(HashSet()) { it.taskId }
        val departed = previouslyActive - active
        if (departed.isNotEmpty()) departed.forEach(stopBackground)
        previouslyActive.apply {
            clear()
            addAll(active)
        }
    }

    /** 采样拍：对最新任务快照做差分并指数平滑（估算口径，详见类 KDoc）。 */
    private fun sample() {
        val tasks = latestTasks.value.orEmpty()
        val now = nowMillis()
        val previous = lastSample
        lastSample = Sample(now, tasks.associateTo(HashMap()) { it.taskId to it.downloadedBytes })
        if (previous == null) return
        val elapsedMillis = (now - previous.timeMillis).coerceAtLeast(1L)
        // 未在下方更新的任务（暂停/完成/新出现）速度一律归零，避免遗留陈旧估算。
        val updated = speeds.value.mapValues { 0L }.toMutableMap()
        for (task in tasks) {
            if (task.state != TransferState.RUNNING) continue
            val before = previous.transferred[task.taskId] ?: continue // 新任务下一拍才有差分
            val instant = (task.downloadedBytes - before).coerceAtLeast(0L) * 1_000L / elapsedMillis
            val previousEstimate = speeds.value[task.taskId] ?: instant
            updated[task.taskId] = (previousEstimate + instant) / 2
        }
        speeds.value = updated
    }

    private class Sample(val timeMillis: Long, val transferred: Map<String, Long>)
}
