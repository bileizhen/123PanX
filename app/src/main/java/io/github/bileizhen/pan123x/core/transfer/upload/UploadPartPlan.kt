package io.github.bileizhen.pan123x.core.transfer.upload

/**
 * 一次批量预签名的分片窗口：[start] 含、[endExclusive] **不含**。
 *
 * 排他上界与参考源一致（`upload_service.py:435`：`end = min(pn + batch, total_parts + 1)`），
 * 因此 `[start, endExclusive)` 落在 1-based 分片号上。
 */
data class PresignWindow(val start: Int, val endExclusive: Int)

/**
 * 上传分片计划的纯函数集合。
 *
 * **无任何 Android / 网络依赖**，JVM 全量可测：分片数、偏移、长度、待传集合、续传进度、
 * 预签名窗口、并行度全部在这里算，引擎只负责调度。协议常量逐字对齐
 * `.reference/123pan` `src/app/service/upload_service.py`，不要"顺手改成更合理的值"。
 *
 * 分片编号为 **1-based**，字节区间：
 * ```
 * offset(pn) = (pn - 1) * BLOCK_SIZE
 * length(pn) = min(BLOCK_SIZE, size - offset(pn))   // 末片可能不足一块
 * ```
 */
object UploadPartPlan {

    /** 固定 5 MiB 分片（参考源 `upload_service.py:267` 的 `block_size = 5242880`），不可配置。 */
    const val BLOCK_SIZE: Long = 5L * 1024 * 1024

    /** 最大并行分片线程数（参考源 `_MAX_UPLOAD_THREADS`）。 */
    const val MAX_THREADS: Int = 4

    /** 预签名批量下界（参考源 `batch = min(max(4, threads * 2), 8)` 中的 4）。 */
    const val PRESIGN_MIN_BATCH: Int = 4

    /** 预签名批量上界（同上公式中的 8）。 */
    const val PRESIGN_MAX_BATCH: Int = 8

    /** 大文件阈值 64 MiB（参考源 `upload_service.py:569`）。 */
    const val LARGE_FILE_THRESHOLD: Long = 64L * 1024 * 1024

    /** 大文件在 complete 与 upload_complete 之间的收尾延迟（参考源 `sleep(3)`）。 */
    const val LARGE_FILE_SETTLE_MS: Long = 3_000

    /**
     * 分片总数 `ceil(size / blockSize)`。**`size == 0`（空文件）返回 0 片**——
     * 空文件不上传任何分片，直接走 complete 流程，不能算出 1 片。
     */
    fun totalParts(size: Long, blockSize: Long = BLOCK_SIZE): Int {
        if (size <= 0L || blockSize <= 0L) return 0
        return ((size + blockSize - 1) / blockSize).toInt()
    }

    /** 分片 [partNumber] 的起始偏移（1-based）。 */
    fun offsetOf(partNumber: Int, blockSize: Long = BLOCK_SIZE): Long =
        (partNumber - 1).toLong() * blockSize

    /**
     * 分片 [partNumber] 的字节长度，末片不足一块时返回实际剩余字节；
     * 越界（超出文件尾）与空文件一律返回 0。
     */
    fun lengthOf(partNumber: Int, size: Long, blockSize: Long = BLOCK_SIZE): Int {
        if (blockSize <= 0L || size <= 0L) return 0
        val remaining = size - offsetOf(partNumber, blockSize)
        return remaining.coerceIn(0L, blockSize).toInt()
    }

    /** 1-based 升序的待传分片：`1..totalParts` 中排除 [uploaded]；[totalParts]==0 时为空表。 */
    fun pendingParts(totalParts: Int, uploaded: Set<Int>): List<Int> =
        (1..totalParts).filter { it !in uploaded }

    /**
     * 已上传分片占用的字节数（续传起点进度）。
     *
     * 必须逐片用 [lengthOf] 累加而**不能**用 `uploaded.size * blockSize`：末片通常不足一块，
     * 直接乘会高估进度，导致进度条提前到 100% 或速度统计失真。
     */
    fun uploadedBytes(uploaded: Set<Int>, size: Long, blockSize: Long = BLOCK_SIZE): Long =
        uploaded.sumOf { lengthOf(it, size, blockSize).toLong() }

    /**
     * 预签名窗口。批量 `batch = min(max(PRESIGN_MIN_BATCH, threads * 2), PRESIGN_MAX_BATCH)`，
     * [PresignWindow.endExclusive] = `min(partNumber + batch, totalParts + 1)`。
     *
     * [threads] 先钳位到 `1..MAX_THREADS`，与参考源在 `up_load` 开头先钳位再算 batch 的顺序一致。
     */
    fun presignWindow(partNumber: Int, totalParts: Int, threads: Int): PresignWindow {
        val effectiveThreads = threads.coerceIn(1, MAX_THREADS)
        val batch = (effectiveThreads * 2).coerceAtLeast(PRESIGN_MIN_BATCH).coerceAtMost(PRESIGN_MAX_BATCH)
        return PresignWindow(
            start = partNumber,
            endExclusive = (partNumber + batch).coerceAtMost(totalParts + 1),
        )
    }

    /**
     * 分片并行度 = `min(threads, pendingCount)`，[threads] 先钳位到 `1..MAX_THREADS`；
     * 结果不会为负（[pendingCount] ≤ 0 时返回 0，调用方据此跳过上传循环）。
     */
    fun workerCount(threads: Int, pendingCount: Int): Int =
        threads.coerceIn(1, MAX_THREADS).coerceAtMost(pendingCount).coerceAtLeast(0)
}
