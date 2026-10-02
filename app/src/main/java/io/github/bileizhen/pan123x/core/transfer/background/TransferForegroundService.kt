package io.github.bileizhen.pan123x.core.transfer.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 传输前台服务（API 26..33 的后台基座）。
 *
 * 为什么它**不执行传输**：真正的上传 / 下载跑在应用级 CoroutineScope 上的
 * [io.github.bileizhen.pan123x.core.transfer.upload.UploadCoordinator] 与
 * [io.github.bileizhen.pan123x.core.transfer.download.DownloadCoordinator] 协程里，状态持久化在 Room。
 * 本服务只是满足系统要求——以 `dataSync` 类型进入前台、提升进程优先级，并展示"N 个传输任务"摘要。
 * 因此 START_NOT_STICKY 即可：进程被杀后任务由 Room + recoverOnStart 恢复（转 WAITING_USER 等用户
 * 显式继续，绝不自动重启），服务无需粘性重启。
 *
 * 计数来源是进程级 [ActiveTransfers]：活跃集合非空则保持前台，最后一个任务注销时
 * stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf。dataSync 前台服务有系统时长上限
 * （Android 15+ 约 6 小时），到点系统回调 onTimeout，此时必须自行退出前台，否则系统抛出
 * ForegroundServiceDidNotStopInTimeException；退出只降低进程优先级，进程内传输继续，
 * 任务状态已由 Room 持久化，不丢进度。
 *
 * 应用需要在 AndroidManifest.xml 中声明（本类不持有 Manifest）：
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 * <service
 *     android:name="io.github.bileizhen.pan123x.core.transfer.background.TransferForegroundService"
 *     android:exported="false"
 *     android:foregroundServiceType="dataSync" />
 * ```
 */
class TransferForegroundService : Service() {

    /** 服务自己的观察 scope：onDestroy 取消；Main.immediate 与服务生命周期同源，无需额外线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notifications: TransferNotifications

    /** 是否已成功进入前台；stopForeground 只对进过前台的实例有意义，避免无谓调用。 */
    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        notifications = TransferNotifications(this)
        notifications.ensureChannel()
        scope.launch {
            // StateFlow 首值即当前快照；计数清零事件驱动退出（PLAN ：最后一个任务结束时 stopSelf）。
            ActiveTransfers.active.collect { list -> onActiveChanged(list) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            // 停止单个任务：注销后计数是否清零由下方统一判断（最后一个任务 → stopNow）。
            intent.getStringExtra(EXTRA_TASK_ID)?.let { ActiveTransfers.remove(it) }
        } else {
            registerFromIntent(intent)
            // startForegroundService 的契约：启动后必须尽快 startForeground，否则系统抛异常。
            // launcher 已先把任务登记进 ActiveTransfers，这里读到的计数已包含本任务。
            startAsForeground()
        }
        if (ActiveTransfers.isEmpty) {
            // 覆盖"刚启动就全部结束"的竞态：先满足 startForeground 契约，再立即退出。
            stopNow()
        } else {
            notifications.refreshForegroundSummary(ActiveTransfers.active.value.size)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** dataSync 时长上限到点（onTimeout(int) 自 API 34 起，短服务类型触发）：必须自行退出前台。 */
    override fun onTimeout(startId: Int) {
        stopNow()
    }

    /** 带 fgsType 的超时回调（API 35 起，dataSync 6 小时上限触发）：同样自行退出前台。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopNow()
    }

    // ---- 内部实现 ----

    /** 活跃计数变化：非空则刷新摘要；清零时仅在已进前台的实例上退出（onCreate 首值可能为空）。 */
    private fun onActiveChanged(list: List<ActiveTransfer>) {
        when {
            list.isEmpty() -> if (foregroundStarted) stopNow()
            else -> notifications.refreshForegroundSummary(list.size)
        }
    }

    /** 以 dataSync 类型进入前台（3 参 startForeground 自 API 29 起；26..28 用 2 参重载）。 */
    private fun startAsForeground() {
        val summary = notifications.foregroundSummary(ActiveTransfers.active.value.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                TransferNotifications.FOREGROUND_NOTIFICATION_ID,
                summary,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(TransferNotifications.FOREGROUND_NOTIFICATION_ID, summary)
        }
        foregroundStarted = true
    }

    /** 退出前台并停止服务；重复调用安全（stopSelf 幂等）。 */
    private fun stopNow() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    /** 意图 extras → ActiveTransfers：launcher 已登记过，这里幂等重登，兼容直启服务的方式。 */
    private fun registerFromIntent(intent: Intent?) {
        val delivered = intent ?: return
        val taskId = delivered.getStringExtra(EXTRA_TASK_ID) ?: return
        ActiveTransfers.add(
            taskId = taskId,
            title = delivered.getStringExtra(EXTRA_TITLE).orEmpty(),
            estimatedBytes = delivered.getLongExtra(EXTRA_ESTIMATED_BYTES, 0L),
        )
    }

    companion object {
        internal const val ACTION_STOP = "io.github.bileizhen.pan123x.core.transfer.background.STOP"
        internal const val EXTRA_TASK_ID = "taskId"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_ESTIMATED_BYTES = "estimatedBytes"

        /** 启动 intent：extras 满足接口约定（taskId、estimatedBytes、title）。 */
        internal fun intent(context: Context, taskId: String, estimatedBytes: Long, title: String): Intent =
            Intent(context, TransferForegroundService::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_ESTIMATED_BYTES, estimatedBytes)
                .putExtra(EXTRA_TITLE, title)

        /** 停止单个任务的 intent（服务内转成 ActiveTransfers.remove，最后一个任务时自停）。 */
        internal fun stopIntent(context: Context, taskId: String): Intent =
            Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_TASK_ID, taskId)
    }
}
