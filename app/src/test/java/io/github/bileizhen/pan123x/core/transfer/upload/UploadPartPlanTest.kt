package io.github.bileizhen.pan123x.core.transfer.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UploadPartPlan 纯函数测试。
 * 覆盖边界：空文件、恰好整数倍、余 1 字节、末片不足块、预签名窗口排他上界、并行度钳位。
 */
class UploadPartPlanTest {

    private val block = UploadPartPlan.BLOCK_SIZE

    @Test
    fun constantsMatchReferenceSource() {
        assertEquals(5L * 1024 * 1024, UploadPartPlan.BLOCK_SIZE)
        assertEquals(4, UploadPartPlan.MAX_THREADS)
        assertEquals(4, UploadPartPlan.PRESIGN_MIN_BATCH)
        assertEquals(8, UploadPartPlan.PRESIGN_MAX_BATCH)
        assertEquals(64L * 1024 * 1024, UploadPartPlan.LARGE_FILE_THRESHOLD)
        assertEquals(3_000L, UploadPartPlan.LARGE_FILE_SETTLE_MS)
    }

    @Test
    fun totalPartsReturnsZeroForEmptyFile() {
        // 空文件不上传任何分片（不能算出 1 片）
        assertEquals(0, UploadPartPlan.totalParts(0))
        assertEquals(0, UploadPartPlan.totalParts(-1))
    }

    @Test
    fun totalPartsRoundsUp() {
        assertEquals(1, UploadPartPlan.totalParts(1))
        assertEquals(1, UploadPartPlan.totalParts(block))          // 恰好整数倍
        assertEquals(2, UploadPartPlan.totalParts(block + 1))      // 余 1 字节
        assertEquals(3, UploadPartPlan.totalParts(block * 3))
        assertEquals(4, UploadPartPlan.totalParts(block * 3 + 1))
    }

    @Test
    fun offsetIsOneBased() {
        assertEquals(0L, UploadPartPlan.offsetOf(1))
        assertEquals(block, UploadPartPlan.offsetOf(2))
        assertEquals(block * 4, UploadPartPlan.offsetOf(5))
    }

    @Test
    fun lengthOfLastPartIsRemainder() {
        val size = block * 2 + 1
        assertEquals(block.toInt(), UploadPartPlan.lengthOf(1, size))
        assertEquals(block.toInt(), UploadPartPlan.lengthOf(2, size))
        assertEquals(1, UploadPartPlan.lengthOf(3, size))   // 末片不足一块
        assertEquals(0, UploadPartPlan.lengthOf(4, size))   // 越界
        assertEquals(0, UploadPartPlan.lengthOf(1, 0))      // 空文件
    }

    @Test
    fun pendingPartsExcludesUploadedAndIsAscending() {
        assertEquals(listOf(2, 4, 5), UploadPartPlan.pendingParts(5, setOf(1, 3)))
        assertEquals(listOf(1, 2, 3), UploadPartPlan.pendingParts(3, emptySet()))
        assertEquals(emptyList<Int>(), UploadPartPlan.pendingParts(0, emptySet()))
        assertEquals(emptyList<Int>(), UploadPartPlan.pendingParts(2, setOf(1, 2)))
    }

    @Test
    fun uploadedBytesAccountsForShortLastPart() {
        val size = block * 2 + 1
        // 分片 3 只有 1 字节：不能简单用 count * blockSize
        assertEquals(block + 1, UploadPartPlan.uploadedBytes(setOf(1, 3), size))
        assertNotEquals(2 * block, UploadPartPlan.uploadedBytes(setOf(1, 3), size))
        assertEquals(0L, UploadPartPlan.uploadedBytes(emptySet(), size))
        assertEquals(size, UploadPartPlan.uploadedBytes(setOf(1, 2, 3), size))
    }

    @Test
    fun presignWindowUsesMinOfBatchAndTotalPlusOne() {
        // threads=4 -> batch=min(max(4,8),8)=8
        assertEquals(PresignWindow(1, 9), UploadPartPlan.presignWindow(partNumber = 1, totalParts = 100, threads = 4))
        // threads=1 -> batch=4
        assertEquals(PresignWindow(1, 5), UploadPartPlan.presignWindow(partNumber = 1, totalParts = 100, threads = 1))
        // threads=3 -> batch=min(max(4,6),8)=6
        assertEquals(PresignWindow(1, 7), UploadPartPlan.presignWindow(partNumber = 1, totalParts = 100, threads = 3))
    }

    @Test
    fun presignWindowClampsAtTotalPartsPlusOne() {
        // endExclusive = min(pn + batch, totalParts + 1)
        assertEquals(PresignWindow(95, 101), UploadPartPlan.presignWindow(partNumber = 95, totalParts = 100, threads = 4))
        assertEquals(PresignWindow(100, 101), UploadPartPlan.presignWindow(partNumber = 100, totalParts = 100, threads = 4))
    }

    @Test
    fun presignWindowClampsThreadsFirst() {
        // threads 先钳位到 1..4 再算 batch
        assertEquals(PresignWindow(1, 9), UploadPartPlan.presignWindow(partNumber = 1, totalParts = 100, threads = 99))
        assertEquals(PresignWindow(1, 5), UploadPartPlan.presignWindow(partNumber = 1, totalParts = 100, threads = -3))
    }

    @Test
    fun workerCountClampsThreadsAndNeverNegative() {
        assertEquals(3, UploadPartPlan.workerCount(threads = 4, pendingCount = 3))
        assertEquals(1, UploadPartPlan.workerCount(threads = 1, pendingCount = 10))
        assertEquals(4, UploadPartPlan.workerCount(threads = 8, pendingCount = 10)) // 钳位到 MAX_THREADS
        assertEquals(1, UploadPartPlan.workerCount(threads = 0, pendingCount = 5))  // 下钳位到 1
        assertEquals(0, UploadPartPlan.workerCount(threads = 4, pendingCount = 0))
        assertEquals(0, UploadPartPlan.workerCount(threads = 4, pendingCount = -1))
    }

    @Test
    fun customBlockSizeIsHonoured() {
        assertEquals(4, UploadPartPlan.totalParts(size = 40, blockSize = 10))
        assertEquals(10L, UploadPartPlan.offsetOf(partNumber = 2, blockSize = 10))
        assertEquals(5, UploadPartPlan.lengthOf(partNumber = 4, size = 35, blockSize = 10))
        assertTrue(UploadPartPlan.uploadedBytes(setOf(1, 4), size = 35, blockSize = 10) == 15L)
    }
}
