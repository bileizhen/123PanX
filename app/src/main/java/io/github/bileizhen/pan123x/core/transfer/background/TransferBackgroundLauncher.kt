package io.github.bileizhen.pan123x.core.transfer.background

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * 后台基座启动器（接口约定）：按 SDK 把"有活跃传输任务"这件事映射到
 * 系统要求的进程优先级机制：
 *
 * - **API >= 34**：User-Initiated Data Transfer Job。固定 [JOB_ID] 排期；同一 jobId 重复排期
 *   只会更新估算字节，这是预期行为。排期入口是 [JobScheduler.schedule]——平台**没有**单参
 *   `enqueue(JobInfo)`（`enqueue(JobInfo, JobWorkItem)` 是 API 26 的 JobWorkItem 通道，与
 *   用户发起 Job 无关），因此契约文字里的 "JobScheduler.enqueue" 落地为 `schedule(JobInfo)`。
 * - **API 26..33**：`startForegroundService` 启动 [TransferForegroundService]（dataSync）。
 *
 * 两条路都**不执行传输**（传输在应用级 scope 的协调器协程里）；排期失败只意味着
 * 进程优先级回落，传输照常进行、状态照常落 Room，因此这里只记日志、不向上抛。
 *
 * 时序约定：`start` **先登记 [ActiveTransfers] 再启动基座**，保证服务 / Job 的入口回调读到的
 * 计数已包含本任务；`stop` 注销后仅当计数清零才撤掉基座——中间态由基座自己观察 StateFlow 更新。
 */
object TransferBackgroundLauncher {

    /**
     * 用户发起 Job 的固定 jobId（全应用只有一个传输 Job）：重复排期 = 更新估算，取消 = cancel(JOB_ID)。
     * 与 [TransferNotifications.FOREGROUND_NOTIFICATION_ID] 同属 41xxx 段，避免与其他模块冲突。
     */
    const val JOB_ID = 41001

    /**
     * 为一个任务启动（或保持）后台基座。
     * [estimatedBytes] 是本任务预计的网络字节量，用于用户发起 Job 的网络量声明；
     * [title] 是通知 / 摘要里展示的文件名，禁止携带 signed URL、token 或完整 uri。
     *
     * 前置条件与降级（可靠性优先）：用户发起 Job 必须"应用在前台时排期"，
     * startForegroundService 也有后台启动限制；调用点应来自用户触发的入队流程。即便系统
     * 拒绝（OEM 限制 / 前台判定差异 / 参数被拒），也只记日志不抛——传输本体在进程内照常
     * 执行，丢的只是优先级外壳，任务状态已在 Room（先持久化再执行）。
     */
    fun start(context: Context, taskId: String, estimatedBytes: Long, title: String = "") {
        ActiveTransfers.add(taskId, title, estimatedBytes)
        try {
            if (isJobBackend()) {
                scheduleJob(context)
            } else {
                context.startForegroundService(
                    TransferForegroundService.intent(context, taskId, estimatedBytes, title),
                )
            }
        } catch (restricted: IllegalStateException) {
            Log.w(TAG, "后台基座启动被拒绝（后台启动限制），传输继续：${restricted.message.orEmpty()}")
        } catch (invalid: IllegalArgumentException) {
            Log.w(TAG, "后台基座参数被系统拒绝，传输继续：${invalid.message.orEmpty()}")
        }
    }

    /** 任务结束（完成 / 失败 / 暂停 / 取消）后注销；最后一个任务注销时撤掉后台基座。 */
    fun stop(context: Context, taskId: String) {
        ActiveTransfers.remove(taskId)
        if (!ActiveTransfers.isEmpty) return
        if (isJobBackend()) {
            jobScheduler(context)?.cancel(JOB_ID)
        } else {
            context.stopService(TransferForegroundService.stopIntent(context, taskId))
        }
    }

    // ---- SDK 分派 ----

    /**
     * 运行时守卫：`backendOf == Backend.JOB` 的布尔形式。
     * 标注 [ChecksSdkIntAtLeast] 让 lint 把 `if (isJobBackend)` 识别为 API 34+ 守卫
     * （满足 setUserInitiated 的 API 34 与 setEstimatedNetworkBytes 的 API 28 要求）；
     * 决策本体仍是纯函数 [backendOf]，JVM 单测直接穷举 [backendOf] 的分派结果。
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun isJobBackend(): Boolean = backendOf() == Backend.JOB

    /** 排期 / 更新用户发起 Job；调用点已由 [isJobBackend] 保证 API 34+。 */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleJob(context: Context) {
        val scheduler = jobScheduler(context) ?: return
        val component = ComponentName(context, TransferJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            // 用户发起 Job 必须声明网络量估算，否则 build 抛 IllegalArgumentException；
            // 本应用传输以下载为主、上传也可能触发，保守按"上传量 = 活跃任务估算总和"声明。
            .setUserInitiated(true)
            .setEstimatedNetworkBytes(0L, ActiveTransfers.totalEstimatedBytes().coerceAtLeast(1L))
            .build()
        val result = scheduler.schedule(jobInfo)
        if (result != JobScheduler.RESULT_SUCCESS) {
            // 排期失败不阻塞传输：任务继续在进程内执行，只是失去系统侧的优先级保障。
            Log.w(TAG, "传输 Job 排期失败：result=$result")
        }
    }

    private fun jobScheduler(context: Context): JobScheduler? =
        context.getSystemService(JobScheduler::class.java)

    private const val TAG = "TransferBackground"
}
