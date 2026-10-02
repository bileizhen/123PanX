@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.core.transfer.upload.engine

import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanUploadApi
import io.github.bileizhen.pan123x.core.network.UploadRequestDto
import io.github.bileizhen.pan123x.core.transfer.upload.RandomAccessReader
import io.github.bileizhen.pan123x.core.transfer.upload.UploadPartPlan
import io.github.bileizhen.pan123x.core.transfer.upload.UploadSource
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * UploadEngine 编排测试（引擎行； 不触真实网络）。
 *
 * 替身策略：脚本化 [FakeApi] / [FakeTransport] / [FakeSource]（纯 JVM），断言引擎的编排语义：
 * 端点调用次序与 body 键名由 PanUploadApiTest 负责，这里只验证"引擎在什么时机、
 * 以什么参数、按什么顺序"调用它们，以及 progress/session/parts 三个回调的次数与顺序。
 * 引擎协程全部继承调用方（TestDispatcher）上下文，重试退避 delay 走虚拟时间，用例零真实等待。
 */
class UploadEngineTest {
    @Test fun disabledBackoffFailsAfterFirstTransientPut() = runTest {
        val api = FakeApi()
        val transport = FakeTransport().apply { failures = { _, _ -> SocketTimeoutException("fake-timeout") } }
        try {
            UploadEngine(api, transport, retriesEnabled = { false }).upload(UploadRequest("no-retry", FakeSource(ByteArray(100)), 0, ConflictPolicy.ASK), { _, _ -> }, {}, {})
            fail("retry disabled")
        } catch (error: SocketTimeoutException) { assertEquals("fake-timeout", error.message) }
        assertEquals(1, transport.attempts); assertEquals(0, api.completeCount)
    }

    @Test fun uploadedBytesUseSharedChunkThrottleWithoutChangingPartPayload() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        var reservations = 0
        val limiter = io.github.bileizhen.pan123x.core.transfer.LiveRateLimiter({ reservations++; 0L })
        val bytes = ByteArray(70 * 1024) { (it % 127).toByte() }
        try {
            OkHttpUploadPartTransport(OkHttpClient(), limiter).put(server.url("/part").toString(), bytes, 5)
            val request = server.takeRequest()
            assertTrue(reservations >= 5)
            assertTrue(bytes.contentEquals(request.body.readByteArray()))
            assertNull(request.getHeader("authorization")); assertNull(request.getHeader("loginuuid"))
        } finally { server.shutdown() }
    }

    // ---- 替身 ----

    /** 脚本化 PanUploadApi：队列按序出队，空队列给"成功建会话 / 空 list / 全窗口 URL"默认值。 */
    private class FakeApi : PanUploadApi {
        val uploadDuplicates = mutableListOf<Int>()
        var listCount = 0
        val presignCalls = mutableListOf<Pair<Int, Int>>() // (partNumberStart, partNumberEnd)
        var completeCount = 0
        val finishCalls = mutableListOf<Long>()

        val requestResults = ArrayDeque<ApiResult<UploadRequestDto>>()
        val listResults = ArrayDeque<ApiResult<List<Int>>>()
        val presignResults = ArrayDeque<ApiResult<Map<Int, String>>>()

        override suspend fun requestUpload(
            fileName: String,
            size: Long,
            etag: String,
            parentFileId: Long,
            duplicate: Int,
        ): ApiResult<UploadRequestDto> {
            uploadDuplicates += duplicate
            return if (requestResults.isEmpty()) ApiResult.Success(sessionDto()) else requestResults.removeFirst()
        }

        override suspend fun listUploadedParts(
            bucket: String,
            key: String,
            uploadId: String,
            storageNode: String,
        ): ApiResult<List<Int>> {
            listCount++
            return if (listResults.isEmpty()) ApiResult.Success(emptyList()) else listResults.removeFirst()
        }

        override suspend fun presignParts(
            bucket: String,
            key: String,
            uploadId: String,
            storageNode: String,
            partNumberStart: Int,
            partNumberEnd: Int,
        ): ApiResult<Map<Int, String>> {
            presignCalls += partNumberStart to partNumberEnd
            if (presignResults.isNotEmpty()) return presignResults.removeFirst()
            val urls = (partNumberStart until partNumberEnd).associateWith { part -> "https://cdn.test/part$part" }
            return ApiResult.Success(urls)
        }

        override suspend fun completeMultipartUpload(
            bucket: String,
            key: String,
            uploadId: String,
            storageNode: String,
        ): ApiResult<Unit> {
            completeCount++
            return ApiResult.Success(Unit)
        }

        override suspend fun finishUpload(fileId: Long): ApiResult<Unit> {
            finishCalls += fileId
            return ApiResult.Success(Unit)
        }
    }

    /** 脚本化 PUT：可注入按次序失败、可挂起在途请求（gate）以观测并发度。 */
    private class FakeTransport : UploadPartTransport {
        var attempts = 0
            private set
        val puts = mutableListOf<String>()
        val putSizes = mutableListOf<Int>()
        var inFlight = 0
            private set
        var maxInFlight = 0
            private set
        // lambda 返回 null 表示该次尝试成功（"第 N 次失败后成功"的脚本化入口）
        var failures: ((attempt: Int, url: String) -> Throwable?)? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun put(url: String, data: ByteArray, timeoutSeconds: Int) {
            attempts++
            inFlight++
            if (inFlight > maxInFlight) maxInFlight = inFlight
            try {
                failures?.invoke(attempts, url)?.let { throw it }
                gate?.await() // 挂起以模拟长时间在途 PUT；取消经此传播
                puts += url
                putSizes += data.size
            } finally {
                inFlight--
            }
        }
    }

    /** 内存来源：可关闭随机读能力以模拟不支持 seek 的 SAF provider。 */
    private class FakeSource(
        private val data: ByteArray,
        private val seekable: Boolean = true,
    ) : UploadSource {
        var streamOpens = 0
            private set
        var randomOpens = 0
            private set

        override val displayName: String = "test.bin"
        override val size: Long = data.size.toLong()
        override val lastModified: Long = 1_000L

        override fun openStream(): InputStream {
            streamOpens++
            return ByteArrayInputStream(data)
        }

        override fun openRandomAccess(): RandomAccessReader? {
            if (!seekable) return null
            randomOpens++
            return object : RandomAccessReader {
                override fun readAt(offset: Long, length: Int): ByteArray {
                    val from = offset.toInt()
                    if (from >= data.size) return ByteArray(0)
                    val to = minOf(from + length, data.size)
                    return data.copyOfRange(from, to)
                }

                override fun close() = Unit
            }
        }
    }

    // ---- 完整流程 ----

    @Test
    fun fullFlowUploadsAllPartsThenCompletesInOrder() = runTest {
        val size = UploadPartPlan.BLOCK_SIZE * 2 + 100 // 3 片：5MB、5MB、100B
        val source = FakeSource(ByteArray(size.toInt()) { index -> (index % 199).toByte() })
        val api = FakeApi()
        val transport = FakeTransport()
        val lengths = (1..3).map { part -> UploadPartPlan.lengthOf(part, size) }
        val events = mutableListOf<String>()

        val outcome = UploadEngine(api, transport, logger = AppLogger(), threads = 1).upload(
            request = UploadRequest(key = "task-1", source = source, parentFileId = 7L, policy = ConflictPolicy.ASK),
            progress = { bytes, total -> events += "progress:$bytes/$total" },
            session = { created -> events += "session:${created.uploadId}" },
            parts = { snapshots ->
                events += if (snapshots.size > 1) {
                    "plan:${snapshots.size}"
                } else {
                    "part:${snapshots[0].partNumber}:${snapshots[0].size}"
                }
            },
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        // 协议次序：upload_request(ASK → duplicate=0) → list → PUT ×3 → 收尾确认 list → complete → finish
        assertEquals(listOf(0), api.uploadDuplicates)
        assertEquals(1, source.streamOpens) // MD5 只开一次顺序流
        assertEquals(1, source.randomOpens)
        assertEquals(2, api.listCount)
        assertEquals(listOf(1 to 4), api.presignCalls) // 窗口 (1, min(1+4, 4))=(1,4)，分片 2/3 命中缓存
        assertEquals((1..3).map { "https://cdn.test/part$it" }, transport.puts)
        assertEquals(lengths, transport.putSizes)
        assertEquals(1, api.completeCount)
        assertEquals(listOf(42L), api.finishCalls)
        // 回调次序：session 恰一次 → 完整计划一次 → 起点进度 → 每片（part + 累计进度）
        val expected = mutableListOf("session:upload-id", "plan:3", "progress:0/$size")
        var running = 0L
        lengths.forEachIndexed { index, length ->
            running += length
            expected += "part:${index + 1}:$length"
            expected += "progress:$running/$size"
        }
        assertEquals(expected, events)
    }

    // ---- 秒传与冲突 ----

    @Test
    fun instantUploadReusesWithoutPuttingAnyByte() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        api.requestResults += ApiResult.Success(UploadRequestDto(reuse = true, fileId = 7L))
        val transport = FakeTransport()
        var sessionCalls = 0
        var partsCalls = 0
        var progressCalls = 0

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.KEEP_BOTH),
            progress = { _, _ -> progressCalls++ },
            session = { sessionCalls++ },
            parts = { partsCalls++ },
        )

        assertEquals(UploadOutcome.Done(fileId = 7L, reused = true), outcome)
        assertEquals(1, source.streamOpens) // MD5 仍需计算（秒传靠 etag）
        assertEquals(0, transport.attempts) // 一个字节都不发
        assertEquals(0, api.listCount) // 不建会话、不进分片阶段
        assertEquals(0, api.completeCount)
        assertTrue(api.finishCalls.isEmpty())
        assertEquals(0, sessionCalls + partsCalls + progressCalls) // 三个回调都不触发
    }

    @Test
    fun askConflictReturnsConflictWithoutAnyPut() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        api.requestResults += ApiResult.Success(UploadRequestDto(conflict = true, serverMessage = "同名文件已存在"))
        val transport = FakeTransport()
        var sessionCalls = 0

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
            session = { sessionCalls++ },
        )

        assertEquals(UploadOutcome.Conflict("同名文件已存在"), outcome)
        assertEquals(listOf(0), api.uploadDuplicates) // ASK 只发一次（duplicate=0），不重发
        assertEquals(0, transport.attempts) // 冲突未决不发任何 PUT
        assertEquals(0, api.listCount)
        assertEquals(0, sessionCalls) // 冲突不回调 session
    }

    @Test
    fun keepBothConflictRetriesOnceWithDuplicateAndProceeds() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        api.requestResults += ApiResult.Success(UploadRequestDto(conflict = true, serverMessage = "dup"))
        api.requestResults += ApiResult.Success(sessionDto(fileId = 9L))
        val transport = FakeTransport()

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.KEEP_BOTH),
        )

        assertEquals(UploadOutcome.Done(fileId = 9L, reused = false), outcome)
        assertEquals(listOf(1, 1), api.uploadDuplicates) // 两次都带 duplicate=1（参考源 :313-323）
        assertEquals(1, transport.attempts) // 冲突解决后正常分片上传
        assertEquals(1, api.completeCount)
        assertEquals(listOf(9L), api.finishCalls)
    }

    // ---- 断点续传 ----

    @Test
    fun resumeSkipsUploadedPartsAndReportsStartProgress() = runTest {
        val source = FakeSource(ByteArray(350)) // blockSize=100 → 4 片：100/100/100/50
        val api = FakeApi()
        api.listResults += ApiResult.Success(listOf(1, 3)) // 服务端已有分片 1、3
        val transport = FakeTransport()
        val progressCalls = mutableListOf<Pair<Long, Long>>()
        var sessionCalls = 0

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(
                key = "t",
                source = source,
                parentFileId = 0L,
                policy = ConflictPolicy.ASK,
                resumeSession = resumeSession(blockSize = 100),
            ),
            progress = { bytes, total -> progressCalls += bytes to total },
            session = { sessionCalls++ },
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        assertEquals(0, source.streamOpens) // 跳过 MD5（分片走随机读，不经过顺序流）
        assertTrue("续传不得重发 upload_request", api.uploadDuplicates.isEmpty())
        assertEquals(0, sessionCalls) // 续传不再回调 session
        // 只 PUT 缺失分片 2、4，长度 100 / 50
        assertEquals(listOf("https://cdn.test/part2", "https://cdn.test/part4"), transport.puts)
        assertEquals(listOf(100, 50), transport.putSizes)
        // 起点 = 已传分片字节（100+100=200），随后每完成一片累计一次
        assertEquals(listOf(200L to 350L, 300L to 350L, 350L to 350L), progressCalls)
        // part2 批量窗口 (2, min(2+4, 5))=(2,5)，part4 命中缓存
        assertEquals(listOf(2 to 5), api.presignCalls)
    }

    @Test
    fun resumeWithFailedListFallsBackToFullFlow() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        api.listResults += ApiResult.ApiError(code = 500, message = "boom") // 续传确认 list 失败
        val transport = FakeTransport()

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(
                key = "t",
                source = source,
                parentFileId = 0L,
                policy = ConflictPolicy.ASK,
                resumeSession = resumeSession(blockSize = UploadPartPlan.BLOCK_SIZE),
            ),
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        assertEquals(listOf(0), api.uploadDuplicates) // 放弃续传 → 重算 MD5 → 重发 upload_request
        assertEquals(1, source.streamOpens)
        // list 次序：续传确认（失败）→ 完整流程 list → 收尾确认 list
        assertEquals(3, api.listCount)
    }

    // ---- 分片重试 ----

    @Test
    fun timeoutIsRetriedThenSucceeds() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        val transport = FakeTransport()
        transport.failures = { attempt, _ -> if (attempt == 1) SocketTimeoutException("read timeout") else null }

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        assertEquals(2, transport.attempts) // 第 1 次超时、第 2 次成功
    }

    @Test
    fun givesUpAfterFiveAttemptsOnConnectionErrors() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        val transport = FakeTransport()
        transport.failures = { _, _ -> ConnectException("connection refused") }

        try {
            UploadEngine(api, transport).upload(
                UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
            )
            fail("重试耗尽后必须抛出最后一次连接异常")
        } catch (expected: ConnectException) {
            assertEquals("connection refused", expected.message)
        }
        assertEquals(5, transport.attempts) // 参考源 _UPLOAD_PART_RETRIES = 5
        assertEquals(0, api.completeCount)
    }

    @Test
    fun non2xxHttpErrorFailsImmediatelyWithoutRetry() = runTest {
        val source = FakeSource(ByteArray(1024))
        val api = FakeApi()
        val transport = FakeTransport()
        transport.failures = { _, _ -> UploadPartHttpException(403) }

        try {
            UploadEngine(api, transport).upload(
                UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
            )
            fail("非 2xx 不得重试")
        } catch (expected: UploadPartHttpException) {
            assertEquals(403, expected.code)
        }
        assertEquals(1, transport.attempts) // 立即失败，不重试
        assertEquals(0, api.completeCount)
    }

    // ---- 取消 ----

    @Test
    fun cancelStopsImmediatelyWithoutFurtherParts() = runTest {
        val source = FakeSource(ByteArray(350)) // 4 片
        val api = FakeApi()
        val transport = FakeTransport()
        transport.gate = CompletableDeferred() // 第一个 PUT 永久挂起
        var result: Result<UploadOutcome>? = null

        val job = launch {
            result = runCatching {
                UploadEngine(api, transport).upload(
                    UploadRequest(
                        key = "t",
                        source = source,
                        parentFileId = 0L,
                        policy = ConflictPolicy.ASK,
                        resumeSession = resumeSession(blockSize = 100),
                    ),
                )
            }
        }
        runCurrent()
        assertEquals(1, transport.attempts) // 第一片已发起并挂起在 gate 上

        job.cancelAndJoin()

        assertTrue(result!!.isFailure)
        assertTrue("取消必须以 CancellationException 传播（不吞不包装）", result!!.exceptionOrNull() is CancellationException)
        assertEquals(1, transport.attempts) // 取消后不再发起后续分片
        assertEquals(0, api.completeCount)
    }

    // ---- 空文件 ----

    @Test
    fun emptyFileCompletesWithoutAnyPart() = runTest {
        val source = FakeSource(ByteArray(0))
        val api = FakeApi()
        val transport = FakeTransport()
        val plans = mutableListOf<List<UploadPartSnapshot>>()
        val progressCalls = mutableListOf<Pair<Long, Long>>()
        var sessionCalls = 0

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
            progress = { bytes, total -> progressCalls += bytes to total },
            session = { sessionCalls++ },
            parts = { plans += it },
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        assertEquals(0, transport.attempts) // 不发任何 PUT
        assertEquals(1, plans.size)
        assertTrue("空文件计划必须为空（§1.4：0 片）", plans.single().isEmpty())
        assertEquals(listOf(0L to 0L), progressCalls)
        assertEquals(1, sessionCalls) // 空文件仍建会话并走 complete 流程
        assertEquals(1, api.completeCount)
        assertEquals(listOf(42L), api.finishCalls)
    }

    // ---- 并发与来源能力降级 ----

    @Test
    fun seekableSourceUploadsPartsConcurrently() = runTest {
        val size = UploadPartPlan.BLOCK_SIZE * 2 + 1 // 3 片
        val source = FakeSource(ByteArray(size.toInt()))
        val api = FakeApi()
        val transport = FakeTransport()
        transport.gate = CompletableDeferred() // 挂住所有在途 PUT 以观测并发度
        var result: Result<UploadOutcome>? = null

        val job = launch {
            result = runCatching {
                UploadEngine(api, transport, threads = 3).upload(
                    UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
                )
            }
        }
        runCurrent()
        assertEquals(3, transport.attempts) // 3 个 worker 同时在途
        assertEquals(3, transport.maxInFlight)
        assertEquals(listOf(1 to 4), api.presignCalls) // 首个 worker 批量覆盖 (1,4)，其余命中缓存

        transport.gate?.complete(Unit)
        advanceUntilIdle()

        assertTrue(result!!.isSuccess)
        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), result!!.getOrNull())
        assertEquals(3, transport.puts.size)
        assertEquals(
            setOf("https://cdn.test/part1", "https://cdn.test/part2", "https://cdn.test/part3"),
            transport.puts.toSet(),
        )
    }

    @Test
    fun nonSeekableSourceFallsBackToSequentialSingleWorker() = runTest {
        val source = FakeSource(ByteArray(600), seekable = false)
        val api = FakeApi()
        val transport = FakeTransport()

        val outcome = UploadEngine(api, transport, threads = 4).upload(
            UploadRequest(
                key = "t",
                source = source,
                parentFileId = 0L,
                policy = ConflictPolicy.ASK,
                resumeSession = resumeSession(blockSize = 100), // 6 片 × 100 字节
            ),
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        assertEquals(1, transport.maxInFlight) // 来源不支持随机读 → 强制单 worker
        assertEquals((1..6).map { "https://cdn.test/part$it" }, transport.puts) // 顺序消费：PUT 严格升序
        assertEquals(List(6) { 100 }, transport.putSizes)
        assertEquals(1, source.streamOpens) // 只开一次顺序流（续传跳过 MD5）
        // 顺序模式按 workers=1 计算窗口：batch=4 → (1, min(1+4, 7))=(1,5)，part5 → (5, 7)
        assertEquals(listOf(1 to 5, 5 to 7), api.presignCalls)
    }

    // ---- 预签名兜底 ----

    @Test
    fun presignResponseMissingRequestedPartFallsBackToSingleRequest() = runTest {
        val source = FakeSource(ByteArray(100)) // 单片文件：窗口 (1, min(1+4, 2))=(1,2)
        val api = FakeApi()
        api.presignResults += ApiResult.Success(emptyMap()) // 批量响应缺分片 1
        api.presignResults += ApiResult.Success(mapOf(1 to "https://cdn.test/single-1"))
        val transport = FakeTransport()

        val outcome = UploadEngine(api, transport).upload(
            UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
        )

        assertEquals(UploadOutcome.Done(fileId = 42L, reused = false), outcome)
        assertEquals(listOf(1 to 2, 1 to 2), api.presignCalls) // 批量一次 + 单分片兜底一次
        assertEquals(listOf("https://cdn.test/single-1"), transport.puts)
    }

    @Test
    fun presignFallbackMissingPartAbortsUpload() = runTest {
        val source = FakeSource(ByteArray(100))
        val api = FakeApi()
        api.presignResults += ApiResult.Success(emptyMap()) // 批量缺失
        api.presignResults += ApiResult.Success(emptyMap()) // 单分片兜底仍缺失
        val transport = FakeTransport()

        try {
            UploadEngine(api, transport).upload(
                UploadRequest(key = "t", source = source, parentFileId = 0L, policy = ConflictPolicy.ASK),
            )
            fail("兜底响应仍缺分片时必须中止上传")
        } catch (expected: IOException) {
            assertEquals("获取分片 1 预签名 URL 失败", expected.message)
        }
        assertEquals(0, transport.attempts)
        assertEquals(0, api.completeCount)
    }

    // ---- OkHttp 传输实现（MockWebServer，真实 PUT 语义） ----

    @Test
    fun okHttpTransportSendsPutBodyAndRejectsNon2xx() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(500))
            val transport = OkHttpUploadPartTransport(OkHttpClient())
            val payload = ByteArray(2048) { index -> (index % 97).toByte() }
            val url = server.url("/put-part").toString()

            runBlocking {
                transport.put(url, payload, timeoutSeconds = 10) // 2xx：正常返回
                try {
                    transport.put(url, payload, timeoutSeconds = 10)
                    fail("非 2xx 必须抛 UploadPartHttpException")
                } catch (expected: UploadPartHttpException) {
                    assertEquals(500, expected.code)
                }
            }

            val recorded = server.takeRequest()
            assertEquals("PUT", recorded.method)
            assertArrayEquals(payload, recorded.body.readByteArray())
            // 传输专用客户端不得携带 123pan API 头
            assertNull(recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader("loginuuid"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun okHttpTransportSurfacesTimeoutAsSocketTimeout() {
        val server = MockWebServer()
        server.start()
        try {
            // 不入队响应：请求挂起直到 1 秒调用超时；OkHttp 以 SocketTimeoutException 表现，
            // 恰好落在引擎"可重试"的异常清单内
            val transport = OkHttpUploadPartTransport(OkHttpClient())
            runBlocking {
                try {
                    transport.put(server.url("/slow").toString(), ByteArray(10), timeoutSeconds = 1)
                    fail("超时必须抛 SocketTimeoutException")
                } catch (expected: SocketTimeoutException) {
                    assertTrue(expected.message.orEmpty().isNotEmpty())
                }
            }
        } finally {
            server.shutdown()
        }
    }
}

/** 默认"新建 S3 会话"响应（code==0 且非冲突/秒传）。 */
private fun sessionDto(fileId: Long = 42L) = UploadRequestDto(
    conflict = false,
    reuse = false,
    fileId = fileId,
    bucket = "bucket",
    storageNode = "node",
    key = "upload-key",
    uploadId = "upload-id",
)

/** 测试用续传会话（五字段齐全，blockSize 可注入小值以便用小数据量构造多分片）。 */
private fun resumeSession(blockSize: Long) = UploadSession(
    bucket = "bucket",
    storageNode = "node",
    uploadKey = "upload-key",
    uploadId = "upload-id",
    fileId = 42L,
    etag = "resume-md5",
    blockSize = blockSize,
)
