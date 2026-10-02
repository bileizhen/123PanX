// Kotlin adaptation of Hanabi-Download-Manager-X NSFX via bileizhen/LeiFetch. SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import kotlin.math.roundToLong

/**
 * NSFX 下载策略配置（默认 4 连接，设置可选 1/2/4/8/16）。
 *
 * 本文件整体为纯 Kotlin，刻意不引用任何 Android API（无 Context / AtomicFile / org.json），
 * 因此分段规划、动态拆分、退避与点阵算法可在 JVM 单元测试中全量覆盖。
 *
 * @param threads 目标并发线程数（会被分段数与上限裁剪）。
 * @param segments 手动/固定分段数；为 null 时由 mode 决定取值。
 * @param mode auto | manual | threads_only | segments_only。
 * @param maxConcurrentTasks 同时下载的任务数上限（由协调器使用，引擎本身不消费）。
 * @param globalMaxConnections 全局并发连接数上限，交给 [NsfxHttpClient] 的信号量。
 * @param maxRetries 单次分段/单连接的最大重试次数（1..32）。
 * @param enableDynamicSegments 是否启用动态尾段拆分（大文件尾部偷取空闲连接）。
 * @param globalSpeedLimit 全局限速（字节/秒，0 表示不限速）。
 * @param segmentSpeedLimit 单分段限速（字节/秒，0 表示不限速）。
 */
data class NsfxConfig(
    val threads: Int = 4,
    val segments: Int? = null,
    val mode: String = "auto",
    val maxConcurrentTasks: Int = 3,
    val globalMaxConnections: Int = 16,
    val maxRetries: Int = 32,
    val enableDynamicSegments: Boolean = true,
    val globalSpeedLimit: Long = 0,
    val segmentSpeedLimit: Long = 0,
    val connectionTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 30_000,
)

/**
 * 分段规划器：按文件大小与配置决定「并发线程数 → 分段数」。
 *
 * 算法逐字保留 LeiFetch/NSFX：大小→分段数查表、`enableDynamicSegments` 的 5MB 下限、
 * 最终 `count.coerceIn(1, 32)`，避免与真源行为漂移。
 */
object SegmentPlanner {

    /**
     * 计算分段方案。
     *
     * @return `Pair(并发线程数, 分段总数)`；size<=0 时返回 `1 to 1`。
     */
    fun calculate(size: Long, config: NsfxConfig): Pair<Int, Int> {
        val maxThreads = config.threads.coerceIn(1, 64)
        if (size <= 0) return 1 to 1
        val maxSegments = minOf(256L, size).toInt()
        when (config.mode) {
            "manual" -> (config.segments ?: config.threads).coerceIn(1, maxSegments).let { return minOf(maxThreads, it) to it }
            "threads_only" -> config.threads.coerceIn(1, minOf(32, maxSegments)).let { return minOf(maxThreads, it) to it }
            "segments_only" -> (config.segments ?: 16).coerceIn(1, maxSegments).let { return minOf(maxThreads, it) to it }
        }
        val mb = 1024L * 1024
        var count = when {
            size < 5 * mb -> 1
            size < 20 * mb -> 2
            size < 50 * mb -> 4
            size < 200 * mb -> 8
            size < 500 * mb -> 12
            size < 1024 * mb -> 16
            size < 2048 * mb -> 20
            else -> 24
        }
        // 动态分段开启时保证每段不小于 5MB，避免小文件被过度切碎产生无效连接开销。
        if (config.enableDynamicSegments && count > 1 && size / count < 5 * mb) {
            count = (size / (5 * mb)).toInt().coerceIn(1, count)
        }
        count = count.coerceIn(1, 32)
        return minOf(maxThreads, count) to count
    }
}

/** 单个活动分段的实时快照，供 [DynamicSegmentPolicy] 判定是否拆分。 */
data class SplitSnapshot(val index: Int, val remaining: Long, val speed: Double, val idleMs: Long, val sinceSplitMs: Long)

/** 一次动态拆分决策：从 [index] 段尾部偷取 [stealBytes] 字节新建分段。 */
data class SplitDecision(val index: Int, val stealBytes: Long, val reason: String, val score: Double)

/**
 * 动态尾段拆分策略：当某个分段停滞或明显偏慢且偏重时，从其尾部切出一段交给空闲连接。
 * 算法逐字保留 LeiFetch（stall / single / slow+heavy 三种触发与打分）。
 */
object DynamicSegmentPolicy {

    /** 可拆分的最小剩余字节（8MB），低于该值的分段不再切分。 */
    const val MIN_SPLIT_BYTES = 8L * 1024 * 1024

    /**
     * 生成拆分计划。
     *
     * @param snapshots 当前活动分段快照。
     * @param maxConcurrent 允许的并发连接上限。
     * @param totalSegments 现有分段总数（上限 256）。
     * @return 按优先级（score 降序）排序、最多 2 条的拆分决策。
     */
    fun plan(snapshots: List<SplitSnapshot>, maxConcurrent: Int, totalSegments: Int): List<SplitDecision> {
        val active = snapshots.filter { it.remaining > 0 }
        if (active.isEmpty()) return emptyList()
        val maxPlans = minOf(256 - totalSegments, maxConcurrent - active.size, 2)
        if (maxPlans <= 0) return emptyList()
        val average = active.map { it.remaining.toDouble() }.average()
        val speeds = active.map { it.speed }.filter { it > 0 }.sorted()
        val median = if (speeds.isEmpty()) 0.0 else speeds[speeds.size / 2]
        val single = active.size == 1
        return active.mapNotNull { s ->
            if (s.remaining < MIN_SPLIT_BYTES * 2 || s.sinceSplitMs < 8000) return@mapNotNull null
            val ratio = if (average <= 0) 1.0 else s.remaining / average
            val stalled = s.idleMs >= 4000
            val slow = median > 0 && s.speed > 0 && s.speed < median * 0.7
            val heavy = ratio >= if (single) 1.0 else 1.35
            if (!single && !stalled && !(slow && heavy)) return@mapNotNull null
            val stealRatio = if (stalled) 0.65 else if (single) 0.60 else 0.50
            val steal = (s.remaining * stealRatio).roundToLong().coerceIn(MIN_SPLIT_BYTES, s.remaining - MIN_SPLIT_BYTES)
            val score = ratio + (if (single) 1 else 0) + (if (stalled) 2 else 0) +
                if (median > 0 && s.speed > 0) (1 - s.speed / median).coerceIn(0.0, 1.5) else 0.0
            SplitDecision(s.index, steal, if (stalled) "stall-tail-steal" else if (single) "tail-steal" else "throughput-tail-steal", score)
        }.sortedByDescending { it.score }.take(maxPlans)
    }
}

/**
 * 重试退避策略（有上限、有 jitter、感知取消）。
 * `delayMs` 保留 LeiFetch 的确定性抖动公式（jitter 由 `(retry*137)%250` 提供，非随机数，
 * 保证单测可断言；取消由调用方的 `delay` 天然感知）。
 */
object NsfxRetryPolicy {

    /**
     * 第 [retry] 次重试前的等待毫秒数（retry 从 1 起）。
     * 公式：`(500 * 2^min(retry-1,5) + (retry*137)%250)`，裁剪到 500..15000。
     */
    fun delayMs(retry: Int): Long {
        val attempt = retry.coerceAtLeast(1)
        val exponent = if (attempt > 6) 5 else attempt - 1
        return (500L * (1 shl exponent) + (attempt * 137) % 250).coerceIn(500, 15000)
    }

    /** 归一化配置的重试上限到 1..32。 */
    fun maxRetries(configured: Int) = (if (configured < 1) 32 else configured).coerceIn(1, 32)

    /** 永久性 HTTP 状态码：命中即不重试，直接失败。 */
    val permanentHttp = setOf(400, 401, 403, 404, 405, 410, 416, 451)
}

/**
 * Motrix 式分段点阵：固定片宽、按覆盖度填充，供任务详情的分段视图使用。
 * 算法逐字保留 LeiFetch（含末片按实际跨度归一化）。
 */
object PiecePolicy {

    /** 根据文件大小选择点阵片宽，保证总片数不超过 2048。 */
    fun pieceSize(size: Long): Long {
        var piece = 1L shl 20
        if (size <= 0) return piece
        while ((size + piece - 1) / piece > 2048) piece = piece shl 1
        return piece
    }

    /** 每片覆盖度 0..255。每个分段的已下载部分总是从 start 起的连续前缀。 */
    fun coverage(segments: List<Segment>, size: Long, pieceSize: Long): ByteArray {
        if (size <= 0 || pieceSize <= 0) return ByteArray(0)
        val count = ((size + pieceSize - 1) / pieceSize).toInt()
        val covered = LongArray(count)
        for (segment in segments) {
            var piece = (segment.start / pieceSize).toInt()
            var offset = segment.start % pieceSize
            var remaining = segment.downloaded.coerceAtLeast(0)
            while (remaining > 0 && piece < count) {
                val take = minOf(pieceSize - offset, remaining)
                covered[piece] += take
                remaining -= take
                offset = 0
                piece++
            }
        }
        // 末片的实际跨度小于片宽,按实际跨度归一化,避免文件下完后尾片仍显示半满。
        return ByteArray(count) { i ->
            val span = minOf(pieceSize, size - i * pieceSize)
            ((covered[i] * 255) / span).toInt().coerceIn(0, 255).toByte()
        }
    }
}
