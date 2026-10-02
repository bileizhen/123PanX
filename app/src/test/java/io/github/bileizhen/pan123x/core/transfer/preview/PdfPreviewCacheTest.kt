// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.preview

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PdfPreviewCache 回归测试（不触真实网络）。
 * 重点断言：缓存命中零请求；大小不符重下；**边下边超限中止**且 .part 清理；
 * 非 2xx 的 IOException 消息不含 URL / query；Content-Length 与
 * 实际写入不符视为下载不完整并清理；完成后 cachedFile 命中且二次 ensure 零请求。
 */
class PdfPreviewCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: PdfPreviewCache

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cache = PdfPreviewCache(tmp.root, OkHttpClient())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun previewsDir(): File = File(tmp.root, "previews")

    private fun finalFile(fileId: Long): File = File(previewsDir(), "$fileId.pdf")

    private fun partFile(fileId: Long): File = File(previewsDir(), "$fileId.pdf.part")

    /** 预置一份伪造的已缓存副本。 */
    private fun seedCache(fileId: Long, bytes: Int) {
        finalFile(fileId).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes) { 'A'.code.toByte() })
        }
    }

    @Test
    fun cachedFileOnlyHitsWhenSizeMatches() {
        assertNull(cache.cachedFile(6, 100))
        seedCache(6, 100)
        assertEquals(finalFile(6), cache.cachedFile(6, 100))
        // 大小不一致（内容已刷新）永不命中，宁可重下。
        assertNull(cache.cachedFile(6, 99))
        // 未知大小（expectedSize<=0）永不命中。
        assertNull(cache.cachedFile(6, 0))
    }

    @Test
    fun cacheHitSkipsDownload() = runTest {
        seedCache(1, 100)
        // 若实现错误真的发起请求，会拿到此响应并把缓存覆盖成 1 字节，后续断言立刻暴露。
        server.enqueue(MockResponse().setBody("x"))

        val file = cache.ensure(1, server.url("/pdf").toString(), expectedSize = 100, maxBytes = 1_000_000)

        assertEquals(finalFile(1), file)
        assertEquals(100L, file.length())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sizeMismatchTriggersRedownload() = runTest {
        seedCache(2, 40)
        server.enqueue(MockResponse().setBody("ABCDEFGHIJ")) // 10 字节，Content-Length 自动为 10

        val file = cache.ensure(2, server.url("/pdf").toString(), expectedSize = 10, maxBytes = 1_000_000)

        assertEquals(10L, file.length())
        assertEquals(1, server.requestCount)
        assertEquals(file, cache.cachedFile(2, 10))
    }

    @Test
    fun oversizeAbortsMidStreamAndCleansPart() = runTest {
        val body = ByteArray(1024) { 'x'.code.toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))

        // expectedSize 必须不大于 maxBytes，否则命中"超限预判"（oversizePrecheck 用例已覆盖），
        // 走不到本用例要测的"边下边计数中止"分支。
        val error = runCatching {
            cache.ensure(3, server.url("/pdf").toString(), expectedSize = 16, maxBytes = 16)
        }.exceptionOrNull()

        assertTrue("应抛 PdfTooLargeException，实际 ${error?.javaClass}", error is PdfTooLargeException)
        assertEquals(16L, (error as PdfTooLargeException).maxBytes)
        // 边下边计数中止：半成品与最终文件都不允许残留。
        assertFalse(partFile(3).exists())
        assertFalse(finalFile(3).exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversizePrecheckAvoidsRequest() = runTest {
        // 云端已报告大小超限：一个字节都不应下载。
        server.enqueue(MockResponse().setBody("should-not-be-fetched"))

        val error = runCatching {
            cache.ensure(8, server.url("/pdf").toString(), expectedSize = 200, maxBytes = 100)
        }.exceptionOrNull()

        assertTrue(error is PdfTooLargeException)
        assertEquals(100L, (error as PdfTooLargeException).maxBytes)
        assertEquals(0, server.requestCount)
        assertFalse(partFile(8).exists())
    }

    @Test
    fun non2xxThrowsIOExceptionWithoutUrl() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        val secretUrl = server.url("/secret/path?sig=abc").toString()

        val error = runCatching {
            cache.ensure(4, secretUrl, expectedSize = 4, maxBytes = 1_000_000)
        }.exceptionOrNull()

        assertTrue("应抛 IOException，实际 ${error?.javaClass}", error is IOException)
        assertFalse(error is PdfTooLargeException)
        // 消息绝不携带 URL / host / query。
        val message = error?.message.orEmpty()
        assertFalse(message.contains("sig=abc"))
        assertFalse(message.contains("http"))
        assertFalse(message.contains("127.0.0.1"))
        assertFalse(partFile(4).exists())
        assertFalse(finalFile(4).exists())
    }

    @Test
    fun truncatedBodyFailsLengthVerificationAndCleans() = runTest {
        // 服务器声明 999 字节但只发 5 字节（经响应拦截器改写 Content-Length 模拟截断 CDN）。
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request()).newBuilder().header("Content-Length", "999").build()
            }
            .build()
        val mismatchCache = PdfPreviewCache(tmp.root, client)
        server.enqueue(MockResponse().setBody("12345"))

        val error = runCatching {
            mismatchCache.ensure(5, server.url("/f.pdf").toString(), expectedSize = 999, maxBytes = 1_000_000)
        }.exceptionOrNull()

        assertTrue("应抛 IOException，实际 ${error?.javaClass}", error is IOException)
        assertFalse(error is PdfTooLargeException)
        assertFalse(partFile(5).exists())
        assertFalse(finalFile(5).exists())
    }

    @Test
    fun completedDownloadThenCachedFileHits() = runTest {
        val payload = "PDFDATA-0123456789"
        server.enqueue(MockResponse().setBody(payload))
        val url = server.url("/file.pdf").toString()

        val file = cache.ensure(7, url, expectedSize = payload.length.toLong(), maxBytes = 1_000_000)

        assertEquals(payload.length.toLong(), file.length())
        assertEquals(file, cache.cachedFile(7, payload.length.toLong()))

        // 第二次 ensure 命中缓存，服务器零请求。
        server.enqueue(MockResponse().setBody("X"))
        val again = cache.ensure(7, url, expectedSize = payload.length.toLong(), maxBytes = 1_000_000)
        assertEquals(file, again)
        assertEquals(1, server.requestCount)
    }
}
