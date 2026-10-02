package io.github.bileizhen.pan123x.core.transfer.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 传输通知：上传 / 下载共用一个渠道，进度与终态按 taskId 复用同一 id。
 *
 * 为什么这样设计：
 * - **权限未授予静默降级**：API 33+ 的 POST_NOTIFICATIONS 是运行时权限，用户拒绝后所有通知
 *   直接跳过，不崩、不引导弹窗、不影响传输本体——传输的权威状态在传输工作台与 Room，通知只是旁路；
 * - **任务身份只认 taskId**：进度（ongoing）与完成 / 失败（autoCancel）用同一通知 id 替换，
 *   避免同一任务刷出多条通知；id 由稳定哈希派生（[Companion.notificationId]，纯函数可测）；
 * - **通知文本不含敏感信息**：调用方必须只传文件名 / 进度 / 用户可读结果，禁止把 signed URL、
 *   token、Cookie 或完整 uri 传入任何参数（本类不做二次脱敏，靠约定收口在接线层）；
 * - **渠道低打扰**：进度通知 setOngoing + setOnlyAlertOnce，渠道关闭提示音与振动
 *   （对齐 LeiFetch Notices.channels 的做法），避免长传期间连环响铃。
 *
 * 小图标使用平台级资源（android.R.drawable.stat_sys_download 等）：后台基座不依赖 app 侧
 * 任何 drawable，应用后续可换成品牌图标，只改本文件的三个常量。
 */
class TransferNotifications(private val context: Context) {

    /**
     * 确保传输通知渠道存在；重复调用安全（createNotificationChannel 对同 id 只做更新）。
     * minSdk 26：NotificationChannel 无条件可用（构建基线），无需版本分支。
     */
    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "上传与下载的进度、完成与失败通知"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 进度通知： ongoing、仅首次提醒、进度条 0..100（[percent] 内部钳位）；
     * [indeterminate] 为 true 时（如取链 / 计算 MD5 阶段）显示不确定进度条，忽略 [percent]。
     */
    fun progress(taskId: String, title: String, text: String, percent: Int, indeterminate: Boolean) {
        val notification = base(title, text)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
        post(notificationId(taskId), notification)
    }

    /** 完成：与进度同 id 替换，可左滑清除，点按打开应用。 */
    fun finished(taskId: String, title: String, text: String) {
        post(notificationId(taskId), finalNotification(title, text, failed = false))
    }

    /** 失败：与进度同 id 替换，图标切换为错误态，可左滑清除。 */
    fun failed(taskId: String, title: String, text: String) {
        post(notificationId(taskId), finalNotification(title, text, failed = true))
    }

    /** 撤销某任务的通知（用户取消任务时调用；未授予通知权限时同样静默跳过）。 */
    fun cancel(taskId: String) {
        if (!canPost()) return
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    /**
     * 前台服务摘要通知（不直接发送）：由 [TransferForegroundService] 在 startForeground 时使用。
     * 系统要求 startForeground 必须携带通知且不受 POST_NOTIFICATIONS 影响，因此这里**只构建**，
     * 权限门控在 [refreshForegroundSummary] 的"更新"路径上。
     */
    internal fun foregroundSummary(activeCount: Int): Notification = base(DEFAULT_TITLE, summaryText(activeCount))
        .setOngoing(true)
        .build()

    /** 活跃计数变化时更新前台摘要（同 id 覆盖）；权限未授予时静默跳过。 */
    internal fun refreshForegroundSummary(activeCount: Int) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context)
                .notify(FOREGROUND_NOTIFICATION_ID, foregroundSummary(activeCount))
        } catch (revoked: SecurityException) {
            // 权限检查与发送之间被用户撤销：静默降级（PLAN），前台服务本体不受影响。
            Log.w(TAG, "通知权限已被撤销，跳过传输摘要更新")
        }
    }

    // ---- 构建与发送 ----

    /** 公共骨架：渠道、小图标、标题 / 文本、私密度与点按行为（打开应用）。 */
    private fun base(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON_ONGOING)
            .setContentTitle(title.ifBlank { DEFAULT_TITLE })
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent())

    /** 终态通知：结束 ongoing，允许清除；失败用错误图标。 */
    private fun finalNotification(title: String, text: String, failed: Boolean): Notification =
        base(title, text)
            .setSmallIcon(if (failed) ICON_FAILED else ICON_DONE)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

    /** 点按通知回到应用首页；取不到启动意图时省略（通知本身照常展示）。 */
    private fun contentIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 发送前置门控（PLAN  静默降级）：
     * API 33+ 检查 POST_NOTIFICATIONS 授权，再检查渠道总开关；任一不满足直接放弃发送。
     */
    private fun canPost(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 门控之后仍可能碰上权限被即时撤销（SecurityException）：记录并放弃，不影响传输。 */
    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (revoked: SecurityException) {
            Log.w(TAG, "通知权限已被撤销，跳过传输通知")
        }
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val CHANNEL_NAME = "传输"

        /**
         * 前台服务摘要通知的固定 id。与任务通知 id（taskId 哈希）分属两个命名空间：
         * 摘要随活跃计数更新，任务通知随任务生灭；固定常量便于系统侧去重与测试断言。
         */
        const val FOREGROUND_NOTIFICATION_ID = 41002

        /** 任务通知 id：taskId → 稳定 id，进度与终态复用同一通知（UUID 碰撞概率可忽略）。 */
        fun notificationId(taskId: String): Int = taskId.hashCode()

        /** 前台服务摘要文案（纯函数，JVM 可测）；负数计数按 0 展示。 */
        fun summaryText(activeCount: Int): String = "正在进行 ${activeCount.coerceAtLeast(0)} 个传输任务"

        private const val DEFAULT_TITLE = "传输"
        private const val TAG = "TransferNotifications"

        // 平台级资源（API 1 起稳定存在），不引入 app 侧 drawable 依赖。
        private const val ICON_ONGOING = android.R.drawable.stat_sys_download
        private const val ICON_DONE = android.R.drawable.stat_sys_download_done
        private const val ICON_FAILED = android.R.drawable.stat_notify_error
    }
}
