package io.github.bileizhen.pan123x.core.transfer.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台基座的纯函数测试（分派逻辑抽成可注入 sdkInt 的纯函数）：
 * - [backendOf] 的 SDK 分派（34+ 走用户发起 Job，26..33 走 dataSync 前台服务）；
 * - 通知 id 稳定性与前台摘要 id 常量；
 * - 前台摘要文案。
 * 真实通知、服务与 Job 生命周期属 Android 依赖，不在 JVM 单测范围（留给真机验证）。
 */
class TransferBackgroundLogicTest {

    // ---- SDK 分派 ----

    @Test
    fun backendUsesJobSchedulerOnApi34Plus() {
        assertEquals(Backend.JOB, backendOf(34))
        assertEquals(Backend.JOB, backendOf(35))
        assertEquals(Backend.JOB, backendOf(36))
        assertEquals(Backend.JOB, backendOf(37))
    }

    @Test
    fun backendUsesForegroundServiceBetween26And33() {
        assertEquals(Backend.FOREGROUND_SERVICE, backendOf(26))
        assertEquals(Backend.FOREGROUND_SERVICE, backendOf(27))
        assertEquals(Backend.FOREGROUND_SERVICE, backendOf(29))
        assertEquals(Backend.FOREGROUND_SERVICE, backendOf(33))
    }

    @Test
    fun dispatchBoundaryFlipsExactlyAt34() {
        // 边界两侧各取一值，锁死"34 是唯一分界"（的分派规则）。
        assertEquals(Backend.FOREGROUND_SERVICE, backendOf(33))
        assertEquals(Backend.JOB, backendOf(34))
    }

    // ---- 通知 id ----

    @Test
    fun notificationIdIsStablePerTask() {
        val id = TransferNotifications.notificationId("0b6bd3e0-4a1b-4a1e-9d7a-1a2b3c4d5e6f")
        // 同一 taskId 永远映射同一 id：进度 → 完成 / 失败必须替换同一条通知。
        assertEquals(id, TransferNotifications.notificationId("0b6bd3e0-4a1b-4a1e-9d7a-1a2b3c4d5e6f"))
        assertNotEquals(id, TransferNotifications.notificationId("another-task"))
    }

    @Test
    fun foregroundSummaryIdIsFixedAndDistinctFromTaskIds() {
        assertEquals(41002, TransferNotifications.FOREGROUND_NOTIFICATION_ID)
        // 摘要与任务通知 id 不同源，避免相互覆盖（哈希碰撞对 UUID 可忽略）。
        assertNotEquals(
            TransferNotifications.FOREGROUND_NOTIFICATION_ID,
            TransferNotifications.notificationId("0b6bd3e0-4a1b-4a1e-9d7a-1a2b3c4d5e6f"),
        )
    }

    // ---- 文案 ----

    @Test
    fun summaryTextCountsActiveTransfers() {
        assertEquals("正在进行 0 个传输任务", TransferNotifications.summaryText(0))
        assertEquals("正在进行 1 个传输任务", TransferNotifications.summaryText(1))
        assertEquals("正在进行 3 个传输任务", TransferNotifications.summaryText(3))
    }

    @Test
    fun summaryTextClampsNegativeCountToZero() {
        assertEquals("正在进行 0 个传输任务", TransferNotifications.summaryText(-2))
    }

    // ---- 常量冻结 ----

    @Test
    fun channelContractMatchesFrozenSpec() {
        assertEquals("transfers", TransferNotifications.CHANNEL_ID)
        assertEquals("传输", TransferNotifications.CHANNEL_NAME)
        assertEquals(41001, TransferBackgroundLauncher.JOB_ID)
    }

    @Test
    fun jobIdNeverCollidesWithForegroundSummaryId() {
        assertTrue(TransferBackgroundLauncher.JOB_ID != TransferNotifications.FOREGROUND_NOTIFICATION_ID)
    }
}
