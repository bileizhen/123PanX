package io.github.bileizhen.pan123x.core.transfer.download

import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.AuthorizationProvider
import io.github.bileizhen.pan123x.core.network.DeviceProfile
import io.github.bileizhen.pan123x.core.network.PanApi
import io.github.bileizhen.pan123x.core.network.PanHttpClientFactory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PanDownloadResolver 端到端测试（不触真实网络）。
 *
 * 用 MockWebServer 同时承载 123pan 取链端点与 CDN 跳转：传输客户端经拦截器把所有 host
 * （含 web-pro2.123952.com）重写到 MockWebServer，从而能逐跳断言重定向、href、b64 兜底、
 * 安全拒绝、JSON 重定向预检与 code==2 重登一次。
 */
class PanDownloadResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var logger: AppLogger
    private val device = DeviceProfile(loginUuid = "0123456789abcdef0123456789abcdef")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        logger = AppLogger()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api(): PanApi = PanApi(
        client = PanHttpClientFactory.defaultClient(device = { device }, auth = AuthorizationProvider { "Bearer t" }),
        primaryBaseUrl = server.url("/").toString(),
        fallbackBaseUrl = null,
    )

    /** 传输客户端：把任意 host 重写到 MockWebServer（scheme 降为 http），保留 followRedirects=false。 */
    private fun transfer(): OkHttpClient = PanHttpClientFactory.transferClient().newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val target = server.url("/")
            val url = request.url.newBuilder()
                .scheme("http")
                .host(target.host)
                .port(target.port)
                .build()
            chain.proceed(request.newBuilder().url(url).build())
        }
        .build()

    private fun resolver(relogin: suspend () -> Boolean = { true }): PanDownloadResolver =
        PanDownloadResolver(api(), transfer(), logger, relogin)

    private fun source() = DownloadSource(
        fileId = 42, fileName = "a.bin", size = 1024, etag = "e1", s3KeyFlag = "s3k", isFolder = false,
    )

    private fun okDownload(data: String) = MockResponse().setBody("""{"code":0,"message":"ok","data":$data}""")

    private fun nonJson() = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/octet-stream").setBody("")

    @Test
    fun directRedirectUrlShortCircuitsWithoutTransferRequest() = runTest {
        server.enqueue(okDownload("""{"RedirectUrl":"https://cdn.example.com/direct.bin"}"""))

        val outcome = resolver().resolve(source())

        assertTrue(outcome is ResolveOutcome.Success)
        assertEquals("https://cdn.example.com/direct.bin", (outcome as ResolveOutcome.Success).url)
        assertFalse(outcome.trafficLimited)
        // 直链短路：只有取链那一次 API 请求，不再发传输请求
        assertEquals(1, server.requestCount)
    }

    @Test
    fun downloadUrlIsRewrittenThenFollowsLocationRedirect() = runTest {
        server.enqueue(okDownload("""{"DownloadUrl":"https://origin.example.com/f.zip"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example.com/real.zip"))
        server.enqueue(nonJson())

        val outcome = resolver().resolve(source())

        assertEquals("https://cdn.example.com/real.zip", (outcome as ResolveOutcome.Success).url)
        server.takeRequest() // 取链
        val follow = server.takeRequest()
        assertEquals("/download-v2/", follow.requestUrl!!.encodedPath)
        assertTrue(follow.requestUrl!!.query!!.contains("params="))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun followsHtmlHrefWhenNoRedirectHeader() = runTest {
        server.enqueue(okDownload("""{"DownloadUrl":"https://origin.example.com/f.zip"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<a href='https://cdn.example.com/real.zip'>go</a>"))
        server.enqueue(nonJson())

        val outcome = resolver().resolve(source())

        assertEquals("https://cdn.example.com/real.zip", (outcome as ResolveOutcome.Success).url)
    }

    @Test
    fun fallsBackToDownloadV2ParamsWhenNoRedirectOrHref() = runTest {
        server.enqueue(okDownload("""{"DownloadUrl":"https://origin.example.com/f.zip"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>nothing</html>"))
        server.enqueue(nonJson())

        val outcome = resolver().resolve(source())

        assertEquals("https://origin.example.com/f.zip?auto_redirect=0", (outcome as ResolveOutcome.Success).url)
    }

    @Test
    fun rejectsHttpDowngradeRedirectAndKeepsRewrittenUrl() = runTest {
        val raw = "https://origin.example.com/f.zip"
        server.enqueue(okDownload("""{"DownloadUrl":"$raw"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example.com/f.zip"))
        server.enqueue(nonJson())

        val outcome = resolver().resolve(source())

        val url = (outcome as ResolveOutcome.Success).url
        assertEquals(DownloadUrlCodec.rewriteDownloadUrl(raw), url)
        assertTrue(url.startsWith("https://web-pro2.123952.com/"))
    }

    @Test
    fun rejectsPrivateIpRedirectAndKeepsRewrittenUrl() = runTest {
        val raw = "https://origin.example.com/f.zip"
        server.enqueue(okDownload("""{"DownloadUrl":"$raw"}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.1/f.zip"))
        server.enqueue(nonJson())

        val outcome = resolver().resolve(source())

        val url = (outcome as ResolveOutcome.Success).url
        assertEquals(DownloadUrlCodec.rewriteDownloadUrl(raw), url)
    }

    @Test
    fun jsonRedirectPreflightSwitchesToRealLink() = runTest {
        server.enqueue(okDownload("""{"DownloadUrl":"https://origin.example.com/f.zip"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>nothing</html>"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"code":0,"data":{"redirect_url":"https://cdn.example.com/final.zip"}}"""),
        )

        val outcome = resolver().resolve(source())

        assertEquals("https://cdn.example.com/final.zip", (outcome as ResolveOutcome.Success).url)
    }

    @Test
    fun unsafeDirectUrlIsRejectedAndFallsBackToRaw() = runTest {
        server.enqueue(
            okDownload("""{"RedirectUrl":"http://cdn.example.com/d.bin","DownloadUrl":"https://origin.example.com/f.zip"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example.com/real.zip"))
        server.enqueue(nonJson())

        val outcome = resolver().resolve(source())

        assertEquals("https://cdn.example.com/real.zip", (outcome as ResolveOutcome.Success).url)
    }

    @Test
    fun sessionExpiredReloginsOnceAndRetries() = runTest {
        var reloginCount = 0
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"expired"}"""))
        server.enqueue(okDownload("""{"RedirectUrl":"https://cdn.example.com/direct.bin"}"""))

        val outcome = resolver { reloginCount++; true }.resolve(source())

        assertEquals("https://cdn.example.com/direct.bin", (outcome as ResolveOutcome.Success).url)
        assertEquals(1, reloginCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun sessionExpiredNeverReloginsTwice() = runTest {
        var reloginCount = 0
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"expired"}"""))

        val outcome = resolver { reloginCount++; true }.resolve(source())

        assertTrue(outcome is ResolveOutcome.Failure)
        assertEquals(DownloadMessages.SESSION_EXPIRED, (outcome as ResolveOutcome.Failure).userMessage)
        assertEquals(1, reloginCount)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun missingUrlsYieldsNoDownloadUrlFailure() = runTest {
        server.enqueue(okDownload("""{}"""))

        val outcome = resolver().resolve(source())

        assertEquals(DownloadMessages.NO_DOWNLOAD_URL, (outcome as ResolveOutcome.Failure).userMessage)
        assertEquals(1, server.requestCount)
    }

    /**
     * 真机实测（RMX5060）：请求一个超出剩余下载额度的文件时，`/a/api/file/download_info`
     * 返回 `{"code":5113}` 且**完全没有 data**——既没有 RedirectUrl 也没有 DownloadUrl，
     * web-pro2 重写绕过无从下手。此时必须报"流量超限"，不能误报成"未找到下载链接"。
     */
    @Test
    fun trafficLimitedWithoutAnyUrlYieldsTrafficLimitFailure() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":5113,"message":"下载流量已超出限制"}"""))

        val outcome = resolver().resolve(source())

        assertEquals(
            DownloadMessages.TRAFFIC_LIMITED_BLOCKED,
            (outcome as ResolveOutcome.Failure).userMessage,
        )
        assertEquals(1, server.requestCount)
    }

    /** 5114 与 5113 同义（参考源 `_DOWNLOAD_LIMIT_CODES`），走同一分支。 */
    @Test
    fun trafficLimited5114WithoutAnyUrlYieldsTrafficLimitFailure() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":5114,"message":"下载流量已超出限制"}"""))

        val outcome = resolver().resolve(source())

        assertEquals(
            DownloadMessages.TRAFFIC_LIMITED_BLOCKED,
            (outcome as ResolveOutcome.Failure).userMessage,
        )
    }

    /** 服务端 message 属诊断信息，不得进入用户文案（成功响应常见 message="ok"）。 */
    @Test
    fun serverMessageNeverLeaksIntoUserFacingText() = runTest {
        server.enqueue(okDownload("""{}"""))

        val outcome = resolver().resolve(source())

        assertEquals(
            DownloadMessages.NO_DOWNLOAD_URL,
            (outcome as ResolveOutcome.Failure).userMessage,
        )
    }

    @Test
    fun trafficLimitedPropagatesIntoSuccess() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":5113,"message":"超限","data":{"RedirectUrl":"https://cdn.example.com/direct.bin"}}"""),
        )

        val outcome = resolver().resolve(source())

        assertTrue((outcome as ResolveOutcome.Success).trafficLimited)
        assertEquals("https://cdn.example.com/direct.bin", outcome.url)
    }
}
