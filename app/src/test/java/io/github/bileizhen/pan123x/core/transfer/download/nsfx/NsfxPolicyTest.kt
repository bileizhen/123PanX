package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数策略回归测试（不触真实网络）。
 * 覆盖分段数查表、动态分段下限、各 mode、动态尾段拆分决策、退避与点阵算法。
 */
class NsfxPolicyTest {

    private val mb = 1024L * 1024

    /** threads=32 且关闭动态分段，使查表结果不被 maxThreads / 5MB 下限干扰。 */
    private val plain = NsfxConfig(threads = 32, enableDynamicSegments = false)

    @Test
    fun segmentCountFollowsSizeTable() {
        assertEquals(1, SegmentPlanner.calculate(1 * mb, plain).second)
        assertEquals(1, SegmentPlanner.calculate(4 * mb, plain).second)
        assertEquals(2, SegmentPlanner.calculate(10 * mb, plain).second)
        assertEquals(4, SegmentPlanner.calculate(30 * mb, plain).second)
        assertEquals(8, SegmentPlanner.calculate(100 * mb, plain).second)
        assertEquals(12, SegmentPlanner.calculate(300 * mb, plain).second)
        assertEquals(16, SegmentPlanner.calculate(800 * mb, plain).second)
        assertEquals(20, SegmentPlanner.calculate(1500 * mb, plain).second)
        assertEquals(24, SegmentPlanner.calculate(3000 * mb, plain).second)
    }

    @Test
    fun nonPositiveSizeReturnsSingleSegment() {
        assertEquals(1 to 1, SegmentPlanner.calculate(0, plain))
        assertEquals(1 to 1, SegmentPlanner.calculate(-5, plain))
    }

    @Test
    fun dynamicSegmentsFloorKeepsFiveMbPerSegment() {
        val dynamic = NsfxConfig(threads = 32, enableDynamicSegments = true)
        // 9MB：查表得 2，但 9/2=4.5MB<5MB → 收缩为 1
        assertEquals(1, SegmentPlanner.calculate(9 * mb, dynamic).second)
        // 12MB：查表得 2，12/2=6MB≥5MB → 保持 2
        assertEquals(2, SegmentPlanner.calculate(12 * mb, dynamic).second)
    }

    @Test
    fun threadCountIsCappedByConfig() {
        val cfg = NsfxConfig(threads = 2, enableDynamicSegments = false)
        val (threads, count) = SegmentPlanner.calculate(300 * mb, cfg)
        assertEquals(2, threads)
        assertEquals(12, count)
    }

    @Test
    fun modesHonourExplicitSegmentCounts() {
        assertEquals(8, SegmentPlanner.calculate(500 * mb, NsfxConfig(threads = 8, mode = "threads_only")).second)
        assertEquals(3, SegmentPlanner.calculate(500 * mb, NsfxConfig(threads = 16, segments = 3, mode = "manual")).second)
        assertEquals(16, SegmentPlanner.calculate(500 * mb, NsfxConfig(threads = 16, mode = "segments_only")).second)
        assertEquals(6, SegmentPlanner.calculate(500 * mb, NsfxConfig(threads = 16, segments = 6, mode = "segments_only")).second)
    }

    @Test
    fun dynamicSplitStealsFromLoneTailSegment() {
        val remaining = 32 * mb
        val decisions = DynamicSegmentPolicy.plan(
            listOf(SplitSnapshot(0, remaining, 0.0, 0, 100_000)),
            maxConcurrent = 2,
            totalSegments = 1,
        )
        assertEquals(1, decisions.size)
        assertEquals(0, decisions[0].index)
        assertEquals("tail-steal", decisions[0].reason)
        assertEquals((remaining * 0.60).roundToLong(), decisions[0].stealBytes)
    }

    @Test
    fun dynamicSplitTriggersOnStall() {
        val remaining = 32 * mb
        val decisions = DynamicSegmentPolicy.plan(
            listOf(
                SplitSnapshot(0, remaining, 5.0, 5_000, 100_000),
                SplitSnapshot(1, remaining, 5.0, 0, 100_000),
            ),
            maxConcurrent = 3,
            totalSegments = 2,
        )
        assertEquals(1, decisions.size)
        assertEquals(0, decisions[0].index)
        assertEquals("stall-tail-steal", decisions[0].reason)
    }

    @Test
    fun dynamicSplitTriggersOnSlowHeavySegment() {
        val remaining = 64 * mb
        val decisions = DynamicSegmentPolicy.plan(
            listOf(
                SplitSnapshot(0, remaining, 10.0, 0, 100_000),
                SplitSnapshot(1, remaining, 10.0, 0, 100_000),
                SplitSnapshot(2, remaining * 2, 1.0, 0, 100_000),
            ),
            maxConcurrent = 4,
            totalSegments = 3,
        )
        assertEquals(1, decisions.size)
        assertEquals(2, decisions[0].index)
        assertEquals("throughput-tail-steal", decisions[0].reason)
    }

    @Test
    fun dynamicSplitRejectsSmallOrRecentSegments() {
        val justBelowFloor = DynamicSegmentPolicy.MIN_SPLIT_BYTES * 2 - 1
        assertTrue(DynamicSegmentPolicy.plan(listOf(SplitSnapshot(0, justBelowFloor, 0.0, 0, 100_000)), 2, 1).isEmpty())
        assertTrue(DynamicSegmentPolicy.plan(listOf(SplitSnapshot(0, 32 * mb, 0.0, 0, 7_999)), 2, 1).isEmpty())
    }

    @Test
    fun dynamicSplitReturnsEmptyWithoutActiveSegments() {
        assertTrue(DynamicSegmentPolicy.plan(emptyList(), 4, 0).isEmpty())
        assertTrue(DynamicSegmentPolicy.plan(listOf(SplitSnapshot(0, 0, 0.0, 0, 100_000)), 2, 1).isEmpty())
    }

    @Test
    fun retryDelayMatchesFormulaAndBounds() {
        assertEquals(637L, NsfxRetryPolicy.delayMs(1))
        assertEquals(1024L, NsfxRetryPolicy.delayMs(2))
        for (retry in 1..40) {
            val current = NsfxRetryPolicy.delayMs(retry)
            assertTrue("retry=$retry delay=$current", current in 500..15000)
            if (retry > 1) {
                assertTrue("retry=$retry should not decrease", current >= NsfxRetryPolicy.delayMs(retry - 1))
            }
        }
    }

    @Test
    fun maxRetriesNormalisesConfiguredValue() {
        assertEquals(32, NsfxRetryPolicy.maxRetries(0))
        assertEquals(32, NsfxRetryPolicy.maxRetries(-3))
        assertEquals(32, NsfxRetryPolicy.maxRetries(100))
        assertEquals(10, NsfxRetryPolicy.maxRetries(10))
    }

    @Test
    fun permanentHttpCodesAreComplete() {
        assertEquals(setOf(400, 401, 403, 404, 405, 410, 416, 451), NsfxRetryPolicy.permanentHttp)
    }

    @Test
    fun pieceSizeKeepsTotalPiecesBounded() {
        assertEquals(1 * mb, PiecePolicy.pieceSize(0))
        assertEquals(1 * mb, PiecePolicy.pieceSize(100 * mb))
        assertEquals(2 * mb, PiecePolicy.pieceSize(4096 * mb))
    }

    @Test
    fun coverageReflectsPartialMultiSegmentProgress() {
        val size = 4 * mb
        val piece = 1 * mb
        val first = Segment(0, 0, 2 * mb).also { it.downloaded = 1 * mb }
        val second = Segment(1, 2 * mb, 4 * mb).also { it.downloaded = 2 * mb }

        val fills = PiecePolicy.coverage(listOf(first, second), size, piece)

        assertEquals(4, fills.size)
        assertEquals(255, fills[0].toInt() and 0xFF)
        assertEquals(0, fills[1].toInt() and 0xFF)
        assertEquals(255, fills[2].toInt() and 0xFF)
        assertEquals(255, fills[3].toInt() and 0xFF)
    }

    @Test
    fun coverageNormalisesLastPieceSpan() {
        val size = 2 * mb + mb / 2 // 2.5MB：末片实际跨度 0.5MB
        val piece = 1 * mb
        val whole = Segment(0, 0, size).also { it.downloaded = size }

        val fills = PiecePolicy.coverage(listOf(whole), size, piece)

        assertEquals(3, fills.size)
        assertEquals(255, fills[0].toInt() and 0xFF)
        assertEquals(255, fills[1].toInt() and 0xFF)
        assertEquals(255, fills[2].toInt() and 0xFF)
    }
}
