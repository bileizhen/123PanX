package io.github.bileizhen.pan123x.core.transfer.background

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActiveTransfers] 进程级计数测试（单测要求）：
 * 登记 / 注销计数、重复登记幂等、清零事件（StateFlow 值断言）。
 * 全部为纯 JVM 断言：StateFlow 的更新即时可见，无需协程测试调度器。
 */
class ActiveTransfersTest {

    @After
    fun drain() {
        // object 单例在 JVM 内跨用例共享：每个用例结束后清空，避免用例间串扰。
        ActiveTransfers.active.value.forEach { ActiveTransfers.remove(it.taskId) }
        assertTrue(ActiveTransfers.isEmpty)
    }

    @Test
    fun addRegistersTaskWithMetadata() {
        ActiveTransfers.add("task-1", "备份.zip", 1024L)

        assertEquals(listOf(ActiveTransfer("task-1", "备份.zip", 1024L)), ActiveTransfers.active.value)
        assertFalse(ActiveTransfers.isEmpty)
    }

    @Test
    fun duplicateAddIsIdempotentAndRefreshesMetadata() {
        ActiveTransfers.add("task-1", "旧名字.bin", 100L)
        ActiveTransfers.add("task-1", "新名字.bin", 200L)

        // 计数幂等（始终一条），但标题与估算字节取最新值——与"同 jobId 重复排期即更新估算"对齐。
        assertEquals(1, ActiveTransfers.active.value.size)
        assertEquals(ActiveTransfer("task-1", "新名字.bin", 200L), ActiveTransfers.active.value.single())
    }

    @Test
    fun distinctTasksAppendInOrder() {
        ActiveTransfers.add("a", "a.iso", 1L)
        ActiveTransfers.add("b", "b.iso", 2L)
        ActiveTransfers.add("c", "c.iso", 3L)

        assertEquals(listOf("a", "b", "c"), ActiveTransfers.active.value.map { it.taskId })
        assertEquals(3, ActiveTransfers.active.value.size)
    }

    @Test
    fun removeReturnsTrueOnlyForExistingTask() {
        assertFalse(ActiveTransfers.remove("missing"))

        ActiveTransfers.add("task-1", "f.bin", 10L)
        assertTrue(ActiveTransfers.remove("task-1"))
        assertFalse(ActiveTransfers.remove("task-1"))
    }

    @Test
    fun removingLastTaskEmitsEmptyState() {
        // 清零事件：最后一个任务注销后，StateFlow 值回到空列表（基座据此 stopSelf / jobFinished）。
        ActiveTransfers.add("a", "a.bin", 1L)
        ActiveTransfers.add("b", "b.bin", 2L)

        ActiveTransfers.remove("a")
        assertFalse(ActiveTransfers.isEmpty)

        ActiveTransfers.remove("b")
        assertTrue(ActiveTransfers.active.value.isEmpty())
        assertTrue(ActiveTransfers.isEmpty)
    }

    @Test
    fun totalEstimatedBytesSumsActiveTasks() {
        ActiveTransfers.add("a", "a.bin", 100L)
        ActiveTransfers.add("b", "b.bin", 200L)
        assertEquals(300L, ActiveTransfers.totalEstimatedBytes())

        ActiveTransfers.remove("a")
        assertEquals(200L, ActiveTransfers.totalEstimatedBytes())

        ActiveTransfers.remove("b")
        assertEquals(0L, ActiveTransfers.totalEstimatedBytes())
    }

    @Test
    fun emptyCollectionHasZeroEstimate() {
        assertEquals(0L, ActiveTransfers.totalEstimatedBytes())
    }
}
