package io.github.bileizhen.pan123x.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * PanDownloadApi 协议回归测试（不触真实网络）。
 * 重点断言：download_info 7 字段逐字、batch_download_info 内层小写 fileId、
 * RedirectUrl / DownloadUrl 双键解析、5113/5114 记为成功且 trafficLimited=true、
 * code==2 → SessionExpired、非幂等 POST 绝不自动重试。
 */
class PanDownloadApiTest {

    private lateinit var server: MockWebServer
    private val device = DeviceProfile(loginUuid = "0123456789abcdef0123456789abcdef")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api(
        auth: AuthorizationProvider = AuthorizationProvider { "Bearer t" },
        client: OkHttpClient = PanHttpClientFactory.defaultClient(device = { device }, auth = auth),
    ): PanApi = PanApi(
        client = client,
        primaryBaseUrl = server.url("/").toString(),
        fallbackBaseUrl = null,
    )

    private fun ok(data: String) = MockResponse().setBody("""{"code":0,"message":"ok","data":$data}""")

    @Test
    fun downloadInfoSendsSevenFieldBodyVerbatim() = runTest {
        server.enqueue(ok("""{"RedirectUrl":"https://cdn.example.com/f.bin"}"""))

        val result = api().getDownloadLink(
            fileId = 42, fileName = "a.bin", size = 1024, etag = "e1", s3KeyFlag = "s3k", isFolder = false,
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/a/api/file/download_info", request.path)
        // 7 字段、顺序、键名大小写与参考源 session_file.py#get_file_link 逐字一致
        assertEquals(
            """{"driveId":0,"etag":"e1","fileId":42,"s3keyFlag":"s3k","type":0,"fileName":"a.bin","size":1024}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun batchDownloadInfoSendsLowercaseInnerFileId() = runTest {
        server.enqueue(ok("""{"DownloadUrl":"https://cdn.example.com/dir.zip"}"""))

        val result = api().getDownloadLink(
            fileId = 123, fileName = "dir", size = 0, etag = "", s3KeyFlag = "", isFolder = true,
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/a/api/file/batch_download_info", request.path)
        // 内层键小写 fileId（与 trash 的内层大写 FileId 相反）
        assertEquals("""{"fileIdList":[{"fileId":123}]}""", request.body.readUtf8())
    }

    @Test
    fun parsesRedirectUrlAsDirectUrlAndDownloadUrlAsRawUrl() = runTest {
        server.enqueue(ok("""{"RedirectUrl":"https://cdn.example.com/direct.bin","DownloadUrl":"https://origin.example.com/raw.bin"}"""))

        val result = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        val dto = (result as ApiResult.Success<DownloadLinkDto>).data
        assertEquals("https://cdn.example.com/direct.bin", dto.directUrl)
        assertEquals("https://origin.example.com/raw.bin", dto.rawUrl)
        assertFalse(dto.trafficLimited)
    }

    @Test
    fun parsesSnakeCaseFallbackKeys() = runTest {
        server.enqueue(ok("""{"redirect_url":"https://cdn.example.com/d.bin","downloadUrl":"https://origin.example.com/r.bin"}"""))

        val dto = (api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        ) as ApiResult.Success<DownloadLinkDto>).data

        assertEquals("https://cdn.example.com/d.bin", dto.directUrl)
        assertEquals("https://origin.example.com/r.bin", dto.rawUrl)
    }

    @Test
    fun parsesDefensiveInfoListFallback() = runTest {
        // 防御性兜底：链接嵌在 data.InfoList[0]（参考源未显式处理该形状）
        server.enqueue(ok("""{"InfoList":[{"DownloadUrl":"https://origin.example.com/inner.bin"}]}"""))

        val dto = (api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        ) as ApiResult.Success<DownloadLinkDto>).data

        assertEquals("https://origin.example.com/inner.bin", dto.rawUrl)
        assertEquals("", dto.directUrl)
    }

    @Test
    fun trafficLimitCodesReturnSuccessWithTrafficLimited() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":5113,"message":"下载流量已超出限制","data":{"DownloadUrl":"https://origin.example.com/r.bin"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":5114,"message":"下载流量已超出限制","data":{"DownloadUrl":"https://origin.example.com/r.bin"}}"""))

        val first = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )
        val second = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        // 5113/5114 不是失败：Success + trafficLimited=true，rawUrl 仍可继续重写绕过
        assertTrue(first is ApiResult.Success)
        assertTrue((first as ApiResult.Success<DownloadLinkDto>).data.trafficLimited)
        assertEquals("https://origin.example.com/r.bin", first.data.rawUrl)
        assertTrue(second is ApiResult.Success)
        assertTrue((second as ApiResult.Success<DownloadLinkDto>).data.trafficLimited)
    }

    /**
     * 真机实测（RMX5060）：超限响应可能只带 code、没有 data。此时 DTO 必须把包络 message
     * 带出来，否则上层只能看到"未找到链接"，无法区分流量超限。
     */
    @Test
    fun trafficLimitedWithoutDataKeepsServerMessage() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":5113,"message":"下载流量已超出限制"}"""))

        val result = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        val dto = (result as ApiResult.Success<DownloadLinkDto>).data
        assertTrue(dto.trafficLimited)
        assertEquals("", dto.rawUrl)
        assertEquals("", dto.directUrl)
        assertEquals("下载流量已超出限制", dto.serverMessage)
    }

    /** 非超限的成功码若无链接，serverMessage 仍原样带出（供诊断，不作用户文案）。 */
    @Test
    fun plainSuccessKeepsServerMessageForDiagnostics() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"message":"ok","data":{}}"""))

        val result = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        val dto = (result as ApiResult.Success<DownloadLinkDto>).data
        assertEquals("", dto.rawUrl)
        assertEquals("ok", dto.serverMessage)
    }

    @Test
    fun otherNonZeroCodePassesThroughAsApiError() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":5000,"message":"文件不存在"}"""))

        val result = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        assertTrue(result is ApiResult.ApiError)
        assertEquals(5000, (result as ApiResult.ApiError).code)
        assertEquals("文件不存在", result.message)
    }

    @Test
    fun bodyCodeTwoMapsToSessionExpired() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))

        val result = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        assertTrue(result is ApiResult.SessionExpired)
    }

    @Test
    fun downloadPostIsNeverRetriedAutomatically() = runTest {
        // 非幂等 POST 绝不自动重试：429 也只发一次
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}"""))

        val result = api().getDownloadLink(
            fileId = 1, fileName = "f", size = 10, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        assertTrue(result is ApiResult.ApiError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun downloadInfoBodyUsesTypeZeroForFiles() = runTest {
        server.enqueue(ok("""{}"""))

        api().getDownloadLink(
            fileId = 7, fileName = "x", size = 1, etag = "e", s3KeyFlag = "s", isFolder = false,
        )

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0", body["type"]!!.jsonPrimitive.content)
        assertEquals(7, body.size)
    }
}
