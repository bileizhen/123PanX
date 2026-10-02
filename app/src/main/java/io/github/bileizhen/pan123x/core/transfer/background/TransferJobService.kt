package io.github.bileizhen.pan123x.core.transfer.background

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输用户发起 Job（API 34+ 的后台基座， 的 User-Initiated Data Transfer Job）。
 *
 * 为什么它**不执行传输**：与 [TransferForegroundService] 同理，真正的
 * 上传 / 下载在应用级 scope 的协调器协程里、状态持久化在 Room；本 Job 只以 setUserInitiated
 * 提升进程的网络与调度优先级，并在活跃任务清零后调用 jobFinished 交还系统。用户发起 Job 有
 * 约 6 小时的系统时长上限，超时由系统回调 onStopJob 结束——同样只影响优先级，不影响传输本体。
 *
 * 生命周期约定：
 * - onStartJob 返回 true（还有"观察活跃任务"这件事未完成），随后在 [ActiveTransfers] 清零时
 *   jobFinished(params, reschedule=false)；StateFlow 首值即当前快照，任务已清零则立即结束；
 * - 同一 jobId 被重复排期（launcher 更新估算字节）会再次回调 onStartJob：丢弃旧观察、以最新
 *   params 重建，jobFinished 用 AtomicBoolean 保证幂等，不会重复上报；
 * - onStopJob 返回 false（不要求重排）：传输本体不受影响，进程死亡后由 Room + recoverOnStart
 *   恢复（绝不自动重启）。
 *
 * 应用需要在 AndroidManifest.xml 中声明（BIND_JOB_SERVICE 是 JobService 的硬性要求）：
 * ```xml
 * <service
 *     android:name="io.github.bileizhen.pan123x.core.transfer.background.TransferJobService"
 *     android:exported="false"
 *     android:permission="android.permission.BIND_JOB_SERVICE" />
 * ```
 */
class TransferJobService : JobService() {

    /** 观察活跃任务的 scope；onDestroy 取消，避免服务销毁后还持有 StateFlow 观察者。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前一次 onStartJob 的观察协程；重复 onStartJob 时先取消旧的，防止用过期 params 上报。 */
    private var observation: Job? = null

    /** jobFinished 幂等保护：onStopJob 与观察协程可能先后到达（见 [finishOnce]）。 */
    private val finished = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        observation?.cancel()
        finished.set(false)
        observation = scope.launch {
            // 挂起直到活跃任务清零；若 onStartJob 时已清零，StateFlow 首值即满足条件、立即返回。
            ActiveTransfers.active.first { it.isEmpty() }
            finishOnce(params)
        }
        // 返回 true：还有未完成的工作（观察），完成后由 finishOnce 收尾。
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // 系统主动停止（用户在任务管理器停止 / 时长上限 / 约束变化）：不重排、不重启传输。
        // 传输本体在进程内继续；进程死亡后由 Room 持久化 + recoverOnStart 兜底。
        observation?.cancel()
        observation = null
        finishOnce(params)
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** jobFinished(params, reschedule=false)：幂等上报，首次调用生效，后续调用被吞掉。 */
    private fun finishOnce(params: JobParameters) {
        if (finished.compareAndSet(false, true)) {
            jobFinished(params, false)
        }
    }
}
