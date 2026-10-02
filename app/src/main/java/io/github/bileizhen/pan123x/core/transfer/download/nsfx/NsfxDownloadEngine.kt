// Kotlin adaptation of Hanabi-Download-Manager-X NSFX via bileizhen/LeiFetch. SPDX-License-Identifier: GPL-3.0-only
//
// 有意偏离 LeiFetch：
//   偏离 1：`org.json` → `kotlinx.serialization`（主机限流记忆的 JSON 持久化；org.json 在 JVM 单测下为桩）。
//   偏离 2：`android.util.AtomicFile` → `NsfxStorage.atomic`（纯 JVM 原子写），去掉 Context 依赖。
//   偏离 3：传输 `HttpURLConnection` → OkHttp（`NsfxHttpClient`）；写盘 `RandomAccessFile.seek+write`
//           → `SegmentSink.writeAt(offset, ...)` 定位写（并发安全，可写 SAF）。
// 同时移除 LeiFetch 的 Task / Context / Logs / workDir 业务耦合，改为值对象 + 注入 [AppLogger]。
package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.PanHttpClientFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一次下载请求的纯值描述（不含 Room / Context 耦合）。
 *
 * @param key 任务标识，仅用于日志（不得是完整 signed URL）。
 * @param url 已解析的 CDN 直链。
 * @param expectedSize 已知的文件大小；0 或负数表示未知，将走 probe 探测。
 * @param etag 期望的强 ETag（可为空）。
 * @param headers 透传给传输层的少量请求头。
 */
data class NsfxRequest(
    val key: String,
    val url: String,
    val expectedSize: Long,
    val etag: String,
    val headers: Map<String, String> = emptyMap(),
    val connections: Int? = null,
)

/** 单个分段的持久化快照，供任务协调器写入 Room。 */
data class SegmentSnapshot(val index: Int, val start: Long, val end: Long, val downloaded: Long)

/**
 * 引擎遥测：每次 tick 携带完整分段快照（任务协调器据此整体替换分段表）。
 *
 * @param connections 当前活动连接数。
 * @param pieceSize 点阵片宽。
 * @param fills 点阵覆盖度（0..255）。
 * @param speed 平滑后的整体速度（字节/秒）。
 * @param segments 各分段快照。
 */
class EngineTelemetry(
    val connections: Int,
    val pieceSize: Long,
    val fills: ByteArray,
    val speed: Long,
    val segments: List<SegmentSnapshot>,
)

/**
 * NSFX 多线程下载引擎。
 *
 * 控制流（LeiFetch `download` 的忠实移植，写盘改为 [SegmentSink]）：
 * 小文件(1B..8MB，无断点)直连单连接 → 否则 probe(bytes=0-0) 判定 Range/长度/校验器 →
 * 不支持 Range 或长度<=0 或校验器为空 → 单连接；否则分段并发（可选动态尾段拆分）。
 * 断点：`storage.load` 命中且 sink 长度一致则续传；否则 `storage.reset` 重来。
 * 429/503 主机限流 → 并发减半并记忆主机上限（45 分钟）。协程取消经 `ensureActive` 立即传播。
 *
 * @param config NSFX 配置。
 * @param http 传输层；默认使用传输专用 OkHttpClient（不携带 123pan API 头）。
 * @param logger 可选应用日志器；日志只记录主机名，绝不记录完整 signed URL。
 * @param hostStrategyFile 主机限流记忆的持久化文件；为 null 时仅进程内记忆。
 */
class NsfxDownloadEngine(
    private val config: NsfxConfig,
    private val http: NsfxHttpClient = NsfxHttpClient(config, PanHttpClientFactory.transferClient()),
    private val logger: AppLogger? = null,
    hostStrategyFile: File? = null,
    private val retriesEnabled: () -> Boolean = { true },
    private val consumeBytes: (suspend (Int) -> Unit)? = null,
) {

    private val limiter = RateLimiter(config.globalSpeedLimit)

    private val hostLock = Any()
    private val hostFile: File? = hostStrategyFile
    private val hostJson = Json { ignoreUnknownKeys = true }
    private val hostHints: MutableMap<String, HostHint> = (
        runCatching {
            hostFile?.takeIf { it.isFile }?.let {
                hostJson.decodeFromString<Map<String, HostHint>>(it.readText()).toMutableMap()
            }
        }.getOrNull()
        ) ?: mutableMapOf()

    /**
     * 下载到 [sink]，返回最终字节数（已校验 == 文件总大小）。
     *
     * @param progress `(done, total, speed)` 进度回调；至少每秒一次并在结束时再回调一次。
     * @param telemetry 遥测回调；每次 tick 携带完整分段快照。
     * @throws IOException 传输/校验失败；协程取消直接传播（不吞 `CancellationException`）。
     */
    suspend fun download(
        request: NsfxRequest,
        storage: NsfxStorage,
        sink: SegmentSink,
        progress: (done: Long, total: Long, speed: Long) -> Unit = { _, _, _ -> },
        telemetry: (EngineTelemetry) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        // 短长度文件直接单连接下载，避免多余的 Range 探测（参照 neonsf）。
        if (request.expectedSize in 1 until SMALL_FILE_DIRECT_LIMIT && !storage.hasState()) {
            try {
                logger?.i(LogSource.DOWNLOAD, "${request.key} 小文件直连（${request.expectedSize} 字节）")
                return@withContext single(
                    request,
                    FileInfo(request.url, request.expectedSize, "", "", false),
                    storage, sink, progress, telemetry,
                )
            } catch (_: SizeMismatch) {
                // 来源提示已过期：重置不完整数据，回到权威探测路径作为 fallback。
                logger?.w(LogSource.DOWNLOAD, "${request.key} 大小提示已失效，回到完整探测")
                storage.reset()
            }
        }
        val info = probe(request)
        if (!info.supportsRange || info.size <= 0 || info.validator.isEmpty()) {
            storage.reset()
            logger?.w(LogSource.DOWNLOAD, "${request.key} 服务器不支持分段，改用单连接")
            return@withContext single(request, info, storage, sink, progress, telemetry)
        }
        val (threads, count) = SegmentPlanner.calculate(info.size, config.copy(threads = request.connections?.coerceIn(1, 16) ?: config.threads))
        val saved = storage.load(info)
        // 命中断点但介质长度与预期不符（如 SAF 文件被截断）→ 重置重来。
        val resumable = saved != null && sink.currentLength().let { it < 0 || it == info.size }
        val segments: MutableList<Segment> = if (resumable) {
            saved!!
        } else {
            storage.reset()
            (0 until count).map { i -> Segment(i, info.size * i / count, info.size * (i + 1) / count) }
                .toMutableList()
                .also { storage.save(info, it) }
        }
        if (segments.any { it.downloaded > 0 }) {
            logger?.i(
                LogSource.DOWNLOAD,
                "${request.key} 从断点恢复：已完成 ${segments.sumOf { it.downloaded }} / ${info.size} 字节",
            )
        } else {
            logger?.i(
                LogSource.DOWNLOAD,
                "${request.key} 分段 $count 段 · 并发 $threads · ${info.size} 字节 · 主机 ${hostOf(info.url)}",
            )
        }
        var concurrency = hostCap(info.url, threads)
        val pieceSize = PiecePolicy.pieceSize(info.size)
        telemetry(telemetryOf(concurrency, pieceSize, segments, info.size, 0))
        var round = 0
        val bytes = AtomicLong(segments.sumOf { it.downloaded })
        while (true) {
            try {
                runSegments(request, info, storage, sink, segments, concurrency, bytes, progress, telemetry, pieceSize)
                break
            } catch (e: HttpFailure) {
                if (e.status !in THROTTLE_CODES || concurrency <= 1 || round++ >= 6) throw e
                concurrency = maxOf(1, concurrency / 2)
                logger?.w(LogSource.DOWNLOAD, "${request.key} 主机限流（HTTP ${e.status}），并发降至 $concurrency")
                rememberHost(info.url, concurrency)
                delay(NsfxRetryPolicy.delayMs(round))
            }
        }
        NsfxStorage.verifyCoverage(segments, info.size)
        require(segments.all { it.downloaded == it.size }) { "分段尚未完成" }
        sink.sync()
        progress(info.size, info.size, 0)
        telemetry(telemetryOf(0, pieceSize, segments, info.size, 0))
        info.size
    }

    /** probe：以 `Range: bytes=0-0` 判定 Range 支持、总大小与校验器。 */
    private suspend fun probe(request: NsfxRequest): FileInfo {
        var retries = 0
        var useRange = true
        while (true) {
            try {
                val info = http.get(request.url, request.headers, if (useRange) "bytes=0-0" else null).use { r ->
                    // 400/405/416 表示 Range 不被接受（416 + `bytes */0` 例外：空文件）。
                    if (useRange && r.code in setOf(400, 405, 416) &&
                        !(r.code == 416 && r.header("Content-Range") == "bytes */0")
                    ) {
                        useRange = false
                        return@use null
                    }
                    requireIdentity(r)
                    val size = when (r.code) {
                        206 -> parseRange(r.header("Content-Range"), 0, 0)
                        200 -> r.length
                        416 -> if (r.header("Content-Range") == "bytes */0") 0 else throw HttpFailure(416)
                        else -> throw HttpFailure(r.code)
                    }
                    val tag = strongEtag(r.header("ETag"))
                    val modified = validLastModified(r.header("Last-Modified"))
                    if (r.code == 206) {
                        // 合法 0-0 响应应只有 1 字节；读到 EOF 后连接可进入连接池。
                        val input = r.stream
                        if (input.read() >= 0 && input.read() == -1) r.markConsumed()
                    }
                    FileInfo(request.url, size, tag, modified, r.code == 206)
                }
                if (info != null) return info
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (!retriesEnabled() || !retryable(e) || retries >= NsfxRetryPolicy.maxRetries(config.maxRetries)) throw e
                delay(NsfxRetryPolicy.delayMs(++retries))
            }
        }
    }

    /** 分段并发调度：维持 [concurrency] 个 worker，并按需执行动态尾段拆分。 */
    private suspend fun runSegments(
        request: NsfxRequest,
        info: FileInfo,
        storage: NsfxStorage,
        sink: SegmentSink,
        segments: MutableList<Segment>,
        concurrency: Int,
        bytes: AtomicLong,
        progress: (Long, Long, Long) -> Unit,
        telemetry: (EngineTelemetry) -> Unit,
        pieceSize: Long,
    ) = supervisorScope {
        val workers = mutableMapOf<Int, Deferred<Unit>>()
        var lastBytes = bytes.get()
        var lastTime = System.nanoTime()
        var lastReport = 0L
        var smoothed = 0.0
        val samples = mutableMapOf<Int, Long>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                workers.filterValues { it.isCompleted }.keys.toList().forEach { id -> workers.remove(id)!!.await() }
                segments.filter { it.remaining > 0 && it.index !in workers }
                    .take((concurrency - workers.size).coerceAtLeast(0))
                    .forEach { s -> workers[s.index] = async(Dispatchers.IO) { transfer(request, info, storage, sink, s, bytes) } }
                if (workers.isEmpty()) break
                delay(200)
                val now = System.nanoTime()
                val elapsed = (now - lastTime) / 1e9
                val current = bytes.get()
                val instant = ((current - lastBytes) / elapsed).coerceAtLeast(0.0)
                smoothed = if (smoothed <= 0) instant else smoothed * 0.7 + instant * 0.3
                for (s in segments) {
                    val old = samples.put(s.index, s.downloaded) ?: s.downloaded
                    s.speed = (s.downloaded - old).coerceAtLeast(0) / elapsed
                }
                lastTime = now
                lastBytes = current
                if (now - lastReport >= 1_000_000_000) {
                    progress(current, info.size, smoothed.toLong())
                    telemetry(telemetryOf(workers.size, pieceSize, segments, info.size, smoothed.toLong()))
                    lastReport = now
                }
                if (config.enableDynamicSegments && workers.size < concurrency &&
                    segments.none { it.remaining > 0 && it.index !in workers }
                ) {
                    val snapshots = segments.filter { it.index in workers && it.remaining > 0 }.map {
                        SplitSnapshot(
                            it.index, it.remaining, it.speed,
                            (now - it.lastProgressNs) / 1_000_000,
                            if (it.lastSplitNs == 0L) Long.MAX_VALUE else (now - it.lastSplitNs) / 1_000_000,
                        )
                    }
                    val plans = DynamicSegmentPolicy.plan(snapshots, concurrency, segments.size)
                    for (plan in plans) {
                        val worker = workers.remove(plan.index) ?: continue
                        worker.cancelAndJoin()
                        val s = segments.first { it.index == plan.index }
                        if (s.remaining < DynamicSegmentPolicy.MIN_SPLIT_BYTES * 2) continue
                        val steal = plan.stealBytes
                            .coerceIn(DynamicSegmentPolicy.MIN_SPLIT_BYTES, s.remaining - DynamicSegmentPolicy.MIN_SPLIT_BYTES)
                        val split = s.end - steal
                        val next = Segment(segments.maxOf { it.index } + 1, split, s.end)
                        s.end = split
                        s.lastSplitNs = now
                        next.lastSplitNs = now
                        segments.add(next)
                        storage.save(info, segments)
                    }
                }
            }
        } finally {
            workers.values.forEach { it.cancel() }
            withContext(NonCancellable) {
                workers.values.forEach { it.join() }
                progress(bytes.get(), info.size, 0)
                telemetry(telemetryOf(0, pieceSize, segments, info.size, 0))
            }
        }
    }

    /** 单个分段的传输循环：定位写 + 限速 + 每秒刷盘/checkpoint + 退避重试。 */
    private suspend fun transfer(
        request: NsfxRequest,
        info: FileInfo,
        storage: NsfxStorage,
        sink: SegmentSink,
        segment: Segment,
        bytes: AtomicLong,
    ) {
        val segmentLimiter = RateLimiter(config.segmentSpeedLimit)
        var retries = 0
        while (segment.remaining > 0) {
            try {
                val start = segment.start + segment.downloaded
                val end = segment.end - 1
                http.get(request.url, request.headers, "bytes=$start-$end", info.validator).use { response ->
                    // 签名 CDN(如腾讯 cdntips)每次请求都 302 到不同的边缘 URL,
                    // 身份只认校验器与总长,请求已带 If-Range 兜底;200 即校验失败。
                    if (response.code == 200) throw RangeFailure("RANGE_RESPONSE_INVALID：资源已变化")
                    if (response.code != 206) throw HttpFailure(response.code)
                    if (parseRange(response.header("Content-Range"), start, end) != info.size ||
                        !validatorMatches(info, response.header("ETag"), response.header("Last-Modified"))
                    ) {
                        throw RangeFailure("RANGE_RESPONSE_INVALID：资源已变化")
                    }
                    requireIdentity(response)
                    response.stream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var writeOffset = start
                        var lastSync = System.nanoTime()
                        try {
                            while (segment.remaining > 0) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), segment.remaining).toInt())
                                if (n < 0) throw IOException("incomplete transfer")
                                if (consumeBytes != null) consumeBytes.invoke(n) else limiter.consume(n)
                                segmentLimiter.consume(n)
                                sink.writeAt(writeOffset, buffer, n)
                                writeOffset += n
                                segment.downloaded += n
                                bytes.addAndGet(n.toLong())
                                segment.lastProgressNs = System.nanoTime()
                                if (System.nanoTime() - lastSync > 1_000_000_000) {
                                    sink.sync()
                                    storage.checkpoint(segment)
                                    lastSync = System.nanoTime()
                                }
                            }
                            if (input.read() != -1) throw RangeFailure("分片响应超出范围")
                            response.markConsumed()
                        } finally {
                            sink.sync()
                            storage.checkpoint(segment)
                        }
                    }
                }
                return
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is RangeFailure) {
                    // 资源已变化：把该分段回滚到 0（非重试，向上抛出交给上层失败）。
                    bytes.addAndGet(-segment.downloaded)
                    segment.downloaded = 0
                    storage.checkpoint(segment)
                    throw e
                }
                if (e is HttpFailure && e.status in THROTTLE_CODES) throw e
                if (!retriesEnabled() || !retryable(e) || retries >= NsfxRetryPolicy.maxRetries(config.maxRetries)) throw e
                delay(NsfxRetryPolicy.delayMs(++retries))
            }
        }
    }

    /** 单连接整文件下载（不支持 Range / 长度未知 / 校验器为空时的回退路径）。 */
    private suspend fun single(
        request: NsfxRequest,
        info: FileInfo,
        storage: NsfxStorage,
        sink: SegmentSink,
        progress: (Long, Long, Long) -> Unit,
        telemetry: (EngineTelemetry) -> Unit,
    ): Long {
        if (info.size == 0L) {
            progress(0, 0, 0)
            return 0
        }
        var retries = 0
        while (true) {
            try {
                http.get(request.url, request.headers).use { response ->
                    if (response.code != 200) throw HttpFailure(response.code)
                    requireIdentity(response)
                    if (info.size >= 0 && response.length >= 0 && response.length != info.size) {
                        throw SizeMismatch("文件大小已变化：期望 ${info.size}，实际 ${response.length}")
                    }
                    var done = 0L
                    var previous = 0L
                    var last = System.nanoTime()
                    var lastSync = System.nanoTime()
                    val total = info.size.takeIf { it >= 0 } ?: response.length
                    val pieceSize = maxOf(1L, total)
                    progress(0, total, 0)
                    telemetry(EngineTelemetry(1, pieceSize, ByteArray(1), 0, listOf(SegmentSnapshot(0, 0, total, 0))))
                    try {
                        response.stream.use { input ->
                            val buffer = ByteArray(128 * 1024)
                            val segmentLimiter = RateLimiter(config.segmentSpeedLimit)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (consumeBytes != null) consumeBytes.invoke(n) else limiter.consume(n)
                                segmentLimiter.consume(n)
                                sink.writeAt(done, buffer, n)
                                done += n
                                val now = System.nanoTime()
                                if (now - last > 1_000_000_000) {
                                    val instant = ((done - previous) * 1e9 / (now - last)).toLong()
                                    sink.sync()
                                    progress(done, total, instant)
                                    telemetry(
                                        EngineTelemetry(
                                            1, pieceSize,
                                            byteArrayOf(((done * 255) / pieceSize).coerceIn(0L, 255L).toByte()),
                                            instant,
                                            listOf(SegmentSnapshot(0, 0, total, done)),
                                        ),
                                    )
                                    last = now
                                    previous = done
                                }
                                if (now - lastSync > 1_000_000_000) {
                                    sink.sync()
                                    lastSync = now
                                }
                            }
                            response.markConsumed()
                            sink.sync()
                        }
                    } finally {
                        progress(done, total, 0)
                        telemetry(EngineTelemetry(0, pieceSize, ByteArray(1), 0,
                            listOf(SegmentSnapshot(0, 0, total.coerceAtLeast(done), done))))
                    }
                    if (info.size >= 0 && done != info.size) {
                        throw SizeMismatch("文件大小已变化：期望 ${info.size}，实际 $done")
                    }
                    if (response.length >= 0 && done != response.length) throw IOException("incomplete transfer")
                    progress(done, done, 0)
                    telemetry(EngineTelemetry(0, maxOf(1L, done), byteArrayOf(255.toByte()), 0,
                        listOf(SegmentSnapshot(0, 0, done, done))))
                    return done
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (!retriesEnabled() || !retryable(e) || retries >= NsfxRetryPolicy.maxRetries(config.maxRetries)) throw e
                delay(NsfxRetryPolicy.delayMs(++retries))
            }
        }
    }

    private fun telemetryOf(connections: Int, pieceSize: Long, segments: List<Segment>, size: Long, speed: Long) =
        EngineTelemetry(
            connections = connections,
            pieceSize = pieceSize,
            fills = PiecePolicy.coverage(segments, size, pieceSize),
            speed = speed,
            segments = segments.map { SegmentSnapshot(it.index, it.start, it.end, it.downloaded) },
        )

    private fun hostCap(url: String, requested: Int): Int {
        val host = runCatching { URI(url).host }.getOrNull() ?: return requested
        synchronized(hostLock) {
            val hint = hostHints[host]
            return if (hint == null || hint.expires < System.currentTimeMillis()) requested
            else minOf(requested, hint.cap.coerceAtLeast(1))
        }
    }

    private fun rememberHost(url: String, cap: Int) {
        val host = runCatching { URI(url).host }.getOrNull() ?: return
        synchronized(hostLock) {
            hostHints[host] = HostHint(cap, System.currentTimeMillis() + 45 * 60_000)
            hostFile?.let { file -> runCatching { NsfxStorage.atomic(file, hostJson.encodeToString(hostHints.toMap())) } }
        }
    }

    /** 只记录主机名，绝不记录完整 signed URL。 */
    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "?"

    private fun requireIdentity(response: NsfxHttpClient.Response) {
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) throw RangeFailure("不支持压缩的分段表示")
    }

    private fun retryable(e: IOException): Boolean = e !is RangeFailure && e !is SizeMismatch && e !is SSLHandshakeException &&
        !(e is HttpFailure && e.status in NsfxRetryPolicy.permanentHttp)

    companion object {
        /** 小文件直连上限：8MB（含 1..8MB-1）。 */
        const val SMALL_FILE_DIRECT_LIMIT = 8L * 1024 * 1024

        private val THROTTLE_CODES = setOf(429, 503)

        /** 仅接受强 ETag（形如 `"abc"`）；弱标签或缺失返回空串。 */
        fun strongEtag(value: String?): String = value.orEmpty().trim()
            .takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }.orEmpty()

        /** 仅接受合法 RFC 1123 Last-Modified；含 CR/LF 或不可解析返回空串。 */
        fun validLastModified(value: String?): String = value.orEmpty().trim().takeIf {
            '\r' !in it && '\n' !in it && runCatching {
                ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
            }.isSuccess
        }.orEmpty()

        /** 校验响应校验器与已知身份是否一致。 */
        fun validatorMatches(info: FileInfo, etag: String?, lastModified: String?): Boolean =
            if (info.etag.isNotEmpty()) strongEtag(etag) == info.etag
            else validLastModified(lastModified) == info.lastModified

        /** 解析 `Content-Range: bytes start-end/total`，并校验区间与总长。 */
        fun parseRange(value: String?, start: Long, end: Long): Long {
            val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty())
                ?: throw RangeFailure("无效 Content-Range")
            if (m.groupValues[1].toLong() != start || m.groupValues[2].toLong() != end) throw RangeFailure("Content-Range 不匹配")
            return m.groupValues[3].toLong().also { if (it <= end || end < start) throw RangeFailure("Content-Range 总长不正确") }
        }
    }
}

/** 主机限流记忆条目（45 分钟有效）。 */
@Serializable
internal data class HostHint(val cap: Int, val expires: Long)

/** Mutex 令牌桶限速器（保留 LeiFetch 实现）：0 表示不限速。 */
private class RateLimiter(private val rate: Long) {
    private val lock = Mutex()
    private var nextNs = 0L

    suspend fun consume(bytes: Int) {
        if (rate <= 0) return
        val wait = lock.withLock {
            val now = System.nanoTime()
            nextNs = maxOf(now, nextNs) + (bytes * 1e9 / rate).toLong()
            ((nextNs - now) / 1_000_000).coerceAtLeast(0)
        }
        if (wait > 0) delay(wait)
    }
}
