package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 引擎端到端回归测试（MockWebServer + 临时目录 + [FileSegmentSink]， 不触真实网络）。
 * 覆盖 206 多段、不支持 Range 回退单连接、断点续传只补缺口、500 重试、416 永久失败、
 * 取消立即停止并保留断点、以及「期望 206 却得到 200」的 [RangeFailure]。
 */
class NsfxDownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var sinkDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("nsfx-engine").toFile()
        // 断点目录与目标文件目录必须分开：storage.reset 会清空断点目录。
        sinkDir = Files.createTempDirectory("nsfx-sink").toFile()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        dir.deleteRecursively()
        sinkDir.deleteRecursively()
    }

    /** 2 并发、关闭动态分段：8MB 以下查表得 2 段，便于稳定断言。 */
    private fun config(threads: Int = 2) = NsfxConfig(threads = threads, enableDynamicSegments = false)

    private fun engine(cfg: NsfxConfig = config()) = NsfxDownloadEngine(cfg, NsfxHttpClient(cfg, OkHttpClient()))

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        java.util.Random(42).nextBytes(bytes)
        return bytes
    }

    private fun sinkFile() = File(sinkDir, "out.bin")

    @Test
    fun multiSegmentDownloadWritesExactBytes() = runTest {
        val body = randomBytes(5 * MB + 1)
        val dispatcher = RangeDispatcher(body)
        server.dispatcher = dispatcher
        val storage = NsfxStorage(dir)
        val sink = FileSegmentSink(sinkFile(), body.size.toLong())

        val result = engine().download(
            NsfxRequest("t1", server.url("/file").toString(), expectedSize = 0, etag = ""),
            storage, sink,
        )

        assertEquals(body.size.toLong(), result)
        sink.close()
        assertArrayEquals(body, sinkFile().readBytes())
        val dataRanges = dispatcher.ranges.filterNotNull().filter { it != "bytes=0-0" }
        assertEquals(2, dataRanges.size)
    }

    @Test
    fun serverWithoutRangeFallsBackToSingleConnection() = runTest {
        val body = randomBytes(64 * 1024)
        val dispatcher = RangeDispatcher(body, supportRange = false)
        server.dispatcher = dispatcher
        val storage = NsfxStorage(dir)
        val sink = FileSegmentSink(sinkFile(), body.size.toLong())

        val telemetry = mutableListOf<EngineTelemetry>()
        val result = engine().download(
            NsfxRequest("t2", server.url("/file").toString(), expectedSize = 0, etag = ""),
            storage, sink, telemetry = { telemetry += it },
        )

        assertEquals(listOf(SegmentSnapshot(0, 0, body.size.toLong(), body.size.toLong())), telemetry.last().segments)
        assertEquals(body.size.toLong(), result)
        sink.close()
        assertArrayEquals(body, sinkFile().readBytes())
        // probe(bytes=0-0) + 单连接整文件请求（无 Range）
        assertEquals(listOf("bytes=0-0", null), dispatcher.ranges)
    }

    @Test fun perRequestConnectionChoiceOverridesEngineDefault() = runTest {
        val body = randomBytes(5 * MB + 1)
        val dispatcher = RangeDispatcher(body)
        server.dispatcher = dispatcher
        val sink = FileSegmentSink(sinkFile(), body.size.toLong())
        val connections = Collections.synchronizedList(mutableListOf<Int>())
        try {
            engine(config(threads = 4)).download(
                NsfxRequest("one-connection", server.url("/file").toString(), 0, "", connections = 1),
                NsfxStorage(dir), sink, telemetry = { connections.add(it.connections) },
            )
            assertArrayEquals(body, sinkFile().readBytes())
            assertTrue(connections.isNotEmpty())
            assertTrue(connections.all { it <= 1 })
        } finally { sink.close() }
    }

    @Test
    fun resumeRequestsOnlyMissingRanges() = runTest {
        val body = randomBytes(5 * MB + 1)
        val size = body.size.toLong()
        val mid = size / 2
        val dispatcher = RangeDispatcher(body)
        server.dispatcher = dispatcher
        val url = server.url("/file").toString()
        val info = FileInfo(url, size, "\"v1\"", "", supportsRange = true)

        val storage = NsfxStorage(dir)
        val plan = mutableListOf(Segment(0, 0, mid), Segment(1, mid, size))
        plan[0].downloaded = mid
        plan[1].downloaded = 100
        storage.save(info, plan)
        storage.checkpoint(plan[0])
        storage.checkpoint(plan[1])

        val sink = FileSegmentSink(sinkFile(), size)
        sink.writeAt(0, body, (mid + 100).toInt())

        val result = engine().download(NsfxRequest("t3", url, expectedSize = size, etag = ""), storage, sink)

        assertEquals(size, result)
        sink.close()
        assertArrayEquals(body, sinkFile().readBytes())
        val dataRanges = dispatcher.ranges.filterNotNull().filter { it != "bytes=0-0" }
        assertEquals(listOf("bytes=${mid + 100}-${size - 1}"), dataRanges)
    }

    /**
     * 真机场景：暂停后重新取链，CDN signed URL 已变（不得把 signed URL 当任务身份）。
     * 只要 size + 强 ETag 一致，续传必须只补缺口，而不是把整个文件重下一遍。
     */
    @Test
    fun resumeAcrossReresolvedUrlStillRequestsOnlyMissingRanges() = runTest {
        val body = randomBytes(5 * MB + 1)
        val size = body.size.toLong()
        val mid = size / 2
        val dispatcher = RangeDispatcher(body)
        server.dispatcher = dispatcher
        val oldUrl = server.url("/file?sign=first").toString()
        val freshUrl = server.url("/file?sign=second").toString()

        val storage = NsfxStorage(dir)
        val plan = mutableListOf(Segment(0, 0, mid), Segment(1, mid, size))
        plan[0].downloaded = mid
        plan[1].downloaded = 100
        // 断点日志记录的是"上次"的签名 URL。
        storage.save(FileInfo(oldUrl, size, "\"v1\"", "", supportsRange = true), plan)
        storage.checkpoint(plan[0])
        storage.checkpoint(plan[1])

        val sink = FileSegmentSink(sinkFile(), size)
        sink.writeAt(0, body, (mid + 100).toInt())

        // 重新取链后换成 freshUrl；etag 与 size 不变。
        val result = engine().download(
            NsfxRequest("t3b", freshUrl, expectedSize = size, etag = ""),
            storage, sink,
        )

        assertEquals(size, result)
        sink.close()
        assertArrayEquals(body, sinkFile().readBytes())
        val dataRanges = dispatcher.ranges.filterNotNull().filter { it != "bytes=0-0" }
        assertEquals(listOf("bytes=${mid + 100}-${size - 1}"), dataRanges)
    }

    @Test
    fun serverErrorIsRetriedThenCompletes() = runTest {
        val body = randomBytes(64 * 1024)
        val dispatcher = RangeDispatcher(body, supportRange = false, failFirst = 1)
        server.dispatcher = dispatcher
        val storage = NsfxStorage(dir)
        val sink = FileSegmentSink(sinkFile(), body.size.toLong())

        val result = engine().download(
            NsfxRequest("t4", server.url("/file").toString(), expectedSize = body.size.toLong(), etag = ""),
            storage, sink,
        )

        assertEquals(body.size.toLong(), result)
        sink.close()
        assertArrayEquals(body, sinkFile().readBytes())
        assertEquals(2, dispatcher.ranges.size)
    }

    @Test fun disabledBackoffDoesNotRetryServerErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val cfg = config()
        val engine = NsfxDownloadEngine(cfg, NsfxHttpClient(cfg, OkHttpClient()), retriesEnabled = { false })
        val sink = FileSegmentSink(sinkFile(), 1024)
        try {
            engine.download(NsfxRequest("no-retry", server.url("/file").toString(), expectedSize = 1024, etag = ""), NsfxStorage(dir), sink)
            fail("500 must fail when retries are disabled")
        } catch (error: HttpFailure) { assertEquals(500, error.status) }
        finally { sink.close() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun permanent416FailsFast() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes 100-99/0")
        }
        val storage = NsfxStorage(dir)
        val sink = FileSegmentSink(sinkFile(), -1)

        try {
            engine().download(NsfxRequest("t5", server.url("/file").toString(), expectedSize = 0, etag = ""), storage, sink)
            fail("expected HttpFailure")
        } catch (error: HttpFailure) {
            assertEquals(416, error.status)
        }
        // probe 带 Range 被拒 → 去掉 Range 再试一次即永久失败，不再重试。
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancellationStopsPromptlyAndLeavesCheckpointReadable() = runTest {
        val body = randomBytes(5 * MB + 1)
        val size = body.size.toLong()
        val dispatcher = RangeDispatcher(body, throttleBytes = 4096, throttlePeriodMs = 40)
        server.dispatcher = dispatcher
        val url = server.url("/file").toString()
        val storage = NsfxStorage(dir)
        val sink = FileSegmentSink(sinkFile(), size)

        val startedAt = System.nanoTime()
        val job = launch(Dispatchers.IO) {
            engine().download(NsfxRequest("t6", url, expectedSize = 0, etag = ""), storage, sink)
        }
        awaitRequestCount(2)
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue("job should be cancelled", job.isCancelled)
        assertTrue("cancellation should be prompt, took ${elapsedMs}ms", elapsedMs < 10_000)
        val reloaded = NsfxStorage(dir).load(FileInfo(url, size, "\"v1\"", "", supportsRange = true))
        assertNotNull("checkpoint should remain readable", reloaded)
    }

    @Test
    fun midDownload200Where206ExpectedRaisesRangeFailure() = runTest {
        val body = randomBytes(5 * MB + 1)
        val size = body.size.toLong()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range == "bytes=0-0") {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-0/$size")
                        .setHeader("Content-Length", "1")
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(body, 0, 1))
                } else {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Length", body.size.toString())
                        .setHeader("ETag", "\"v1\"")
                        .setBody(Buffer().write(body))
                }
            }
        }
        val storage = NsfxStorage(dir)
        val sink = FileSegmentSink(sinkFile(), size)

        try {
            engine().download(NsfxRequest("t7", server.url("/file").toString(), expectedSize = 0, etag = ""), storage, sink)
            fail("expected RangeFailure")
        } catch (_: RangeFailure) {
            // expected：资源已变化
        }
    }

    private suspend fun awaitRequestCount(count: Int) {
        withContext(Dispatchers.IO) {
            var spins = 0
            while (server.requestCount < count && spins++ < 500) delay(20)
        }
    }

    /** 支持 Range 的 MockWebServer 调度器；可注入 500 前缀失败与节流以模拟慢速/断网。 */
    private class RangeDispatcher(
        private val body: ByteArray,
        private val supportRange: Boolean = true,
        private val etag: String = "\"v1\"",
        private val failFirst: Int = 0,
        private val throttleBytes: Long = 0,
        private val throttlePeriodMs: Long = 0,
    ) : Dispatcher() {

        private val calls = AtomicInteger(0)
        val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf<String?>())

        override fun dispatch(request: RecordedRequest): MockResponse {
            val index = calls.incrementAndGet()
            val range = request.getHeader("Range")
            ranges.add(range)
            if (failFirst > 0 && index <= failFirst) return MockResponse().setResponseCode(500)
            if (supportRange && range != null) {
                val match = RANGE_RE.find(range)
                if (match != null) {
                    val start = match.groupValues[1].toLong()
                    val end = match.groupValues[2].toLong()
                    val realEnd = minOf(end, body.size - 1L).toInt()
                    val slice = body.copyOfRange(start.toInt(), realEnd + 1)
                    val response = MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$realEnd/${body.size}")
                        .setHeader("Content-Length", slice.size.toString())
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(slice))
                    if (throttleBytes > 0 && range != "bytes=0-0") {
                        response.throttleBody(throttleBytes, throttlePeriodMs, TimeUnit.MILLISECONDS)
                    }
                    return response
                }
            }
            val response = MockResponse().setResponseCode(200)
                .setHeader("Content-Length", body.size.toString())
                .setHeader("ETag", etag)
                .setBody(Buffer().write(body))
            if (throttleBytes > 0) response.throttleBody(throttleBytes, throttlePeriodMs, TimeUnit.MILLISECONDS)
            return response
        }

        private companion object {
            val RANGE_RE = Regex("bytes=(\\d+)-(\\d+)")
        }
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
