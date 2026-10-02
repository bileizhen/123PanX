package io.github.bileizhen.pan123x.core.transfer.background

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 单个活跃传输任务的进程级快照。
 *
 * 为什么是独立值类型（而非直接用 Room 行）：后台基座（前台服务 / 用户发起 Job）只关心
 * "现在有几个任务在跑、各自叫什么、预计多少字节"这三件事，用于满足系统声明与用户可见通知；
 * 任何敏感字段（targetUri、S3 会话、CDN 地址）都不得进入本类。
 */
data class ActiveTransfer(
    val taskId: String,
    val title: String,
    val estimatedBytes: Long,
)

/**
 * 进程级活跃传输任务计数。
 *
 * 为什么用 object 单例：真正的传输工作跑在应用级 CoroutineScope 上的协调器协程里，
 * 前台服务与用户发起 Job **不执行传输**，只负责进程优先级与通知；系统组件（Service / JobService）
 * 由系统实例化、无法注入依赖，进程内唯一的共享状态就是这个活跃集合，双方各自观察它：
 * - 计数从 0 → N：基座启动（或保持）；
 * - 计数清零：最后一个任务结束时 stopSelf / jobFinished。
 *
 * 这不是 UI 状态（不违反  的全局 mutable singleton 禁令）——它是后台基座的进程级
 * 计数器，生命周期与进程一致；进程被杀后由 Room 持久化 + recoverOnStart 恢复任务，本集合随之清零。
 */
object ActiveTransfers {

    private val mutableActive = MutableStateFlow<List<ActiveTransfer>>(emptyList())

    /** 当前活跃任务快照；StateFlow 首值即当前状态，便于服务 / Job 直接观察。 */
    val active: StateFlow<List<ActiveTransfer>> = mutableActive.asStateFlow()

    /** 是否已无活跃任务（计数清零事件的便捷判断）。 */
    val isEmpty: Boolean get() = mutableActive.value.isEmpty()

    /** 活跃任务的估算网络字节总和（用户发起 Job 的 setEstimatedNetworkBytes 输入）。 */
    fun totalEstimatedBytes(): Long = mutableActive.value.sumOf { it.estimatedBytes }

    /**
     * 登记任务。同一 [taskId] 重复登记幂等：集合中始终只有一条，但刷新标题与估算字节——
     * 这与用户发起 Job "同 jobId 重复排期即更新估算"的语义一致。
     */
    fun add(taskId: String, title: String, estimatedBytes: Long) {
        mutableActive.update { current ->
            current.filterNot { it.taskId == taskId } + ActiveTransfer(taskId, title, estimatedBytes)
        }
    }

    /** 注销任务；返回是否确实存在过（供调用方区分"正常结束"与"重复停止"）。 */
    fun remove(taskId: String): Boolean {
        val existed = mutableActive.value.any { it.taskId == taskId }
        if (existed) {
            mutableActive.update { current -> current.filterNot { it.taskId == taskId } }
        }
        return existed
    }
}

/** 后台执行基座：用户发起数据传输 Job（API 34+）或 dataSync 前台服务（API 26..33）。 */
enum class Backend { JOB, FOREGROUND_SERVICE }

/**
 * 按 SDK 选择后台基座。
 *
 * 纯函数、无 Android 运行时调用，[sdkInt] 注入后可在 JVM 单测中穷举各 API 级别的分派结果：
 * - API >= 34：User-Initiated Data Transfer Job（JobScheduler）；
 * - API 26..33：dataSync 前台服务。
 *
 * **不要**把 WorkManager long-running worker 作为方案（明确排除）。
 */
fun backendOf(sdkInt: Int = Build.VERSION.SDK_INT): Backend =
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Backend.JOB else Backend.FOREGROUND_SERVICE
