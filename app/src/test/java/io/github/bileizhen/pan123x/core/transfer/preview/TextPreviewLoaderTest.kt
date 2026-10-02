package io.github.bileizhen.pan123x.core.transfer.preview

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TextPreviewLoader 行为测试（测试矩阵； 纯 JVM 不触真实网络）。
 *
 * MockWebServer 承载直链：覆盖全量读取、按上限截断、Content-Length 缺失、非法 UTF-8 替换、
 * HTTP 错误（5xx 重试一次 / 4xx 快速失败）、断连后重试恢复。退避 delay 走真实时钟，
 * 单次 200~400ms，套件总时长可控。
 */
class TextPreviewLoaderTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** 每次取最新 url，保证 shutdown/重启后端口变化不影响断言。 */
    private fun url(): String = server.url("/notes/readme.txt").toString()

    @Test
    fun readsFullBodyWhenUnderLimit() = runTest {
        val body = "第一行中文\nsecond line"
        val expectedBytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        server.enqueue(MockResponse().setBody(body))

        val result = TextPreviewLoader.load(url(), maxBytes = 1024, client = client)

        assertEquals(body, result.text)
        assertEquals(expectedBytes, result.bytes)
        assertFalse(result.truncated)
        // Content-Length 由 MockWebServer 按字节长度补齐
        assertEquals(expectedBytes, result.totalSize)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/notes/readme.txt", request.path)
    }

    @Test
    fun truncatesAtLimitWithoutDownloadingRest() = runTest {
        val body = "a".repeat(1000)
        server.enqueue(MockResponse().setBody(body))

        val result = TextPreviewLoader.load(url(), maxBytes = 300, client = client)

        assertEquals(300L, result.bytes)
        assertEquals("a".repeat(300), result.text)
        assertTrue(result.truncated)
        assertEquals(1000L, result.totalSize)
    }

    /** 文件恰好等于上限：补读探测到 EOF，不误报截断（truncated 语义）。 */
    @Test
    fun bodyExactlyAtLimitIsNotTruncated() = runTest {
        val body = "b".repeat(256)
        server.enqueue(MockResponse().setBody(body))

        val result = TextPreviewLoader.load(url(), maxBytes = 256, client = client)

        assertEquals(256L, result.bytes)
        assertFalse(result.truncated)
    }

    @Test
    fun missingContentLengthYieldsNullTotalSize() = runTest {
        val body = "chunked body"
        server.enqueue(MockResponse().setChunkedBody(body, 3))

        val result = TextPreviewLoader.load(url(), maxBytes = 1024, client = client)

        assertEquals(body, result.text)
        assertFalse(result.truncated)
        assertNull(result.totalSize)
    }

    /** 非法 UTF-8 序列按 REPLACE 语义替换为 U+FFFD，不抛异常。 */
    @Test
    fun invalidUtf8BytesAreReplacedNotThrown() = runTest {
        val bytes = byteArrayOf('h'.code.toByte(), 0xFF.toByte(), 0xFE.toByte(), 'i'.code.toByte())
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))

        val result = TextPreviewLoader.load(url(), maxBytes = 64, client = client)

        assertTrue(result.text.contains('\uFFFD'))
        assertEquals(4L, result.bytes)
        assertFalse(result.truncated)
    }

    /** 5xx 各重试一次后仍失败：抛 IOException，消息只含状态码、不含 URL。 */
    @Test
    fun http500AfterRetryThrowsIOExceptionWithoutUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { TextPreviewLoader.load(url(), maxBytes = 1024, client = client) }
            .exceptionOrNull()

        assertTrue(error is IOException)
        val message = error?.message.orEmpty()
        assertTrue(message.contains("HTTP 500"))
        assertFalse(message.contains(url()))
        assertFalse(message.contains("/notes/readme.txt"))
        assertEquals(2, server.requestCount)
    }

    /** 4xx 属永久失败（signed URL 过期 / 无权限），不浪费重试。 */
    @Test
    fun http403FailsFastWithoutRetry() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = runCatching { TextPreviewLoader.load(url(), maxBytes = 1024, client = client) }
            .exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesOnceAfterServer500ThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("retry ok"))

        val result = TextPreviewLoader.load(url(), maxBytes = 1024, client = client)

        assertEquals("retry ok", result.text)
        assertEquals(2, server.requestCount)
    }

    /** 首次断连（传输中被掐）→ 重试一次成功；OkHttp 自身可能透明重连，不断言精确请求数。 */
    @Test
    fun retriesOnceAfterBrokenConnectionThenSucceeds() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("recovered"))

        val result = TextPreviewLoader.load(url(), maxBytes = 1024, client = client)

        assertEquals("recovered", result.text)
        assertFalse(result.truncated)
    }
}
