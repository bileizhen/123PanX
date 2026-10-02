package io.github.bileizhen.pan123x.core.network

import io.github.bileizhen.pan123x.core.account.DeviceIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PanQrApiClient 协议回归测试（不触真实网络）。
 *
 * 协议真源 `.reference/123pan/src/app/api/session.py:453-577`：重点断言 generate 的
 * `_qr_headers` 合并请求头（loginuuid / platform=web / app-version=3 / content-type charset）、
 * uniID/url 逐字键名解析、poll 的 `code==200 → loginStatus=3 + token + login_type` 归一化、
 * `code==0` 的 loginStatus 透传、非 0 code → ApiError、`code==2` → SessionExpired。
 * 客户端直连 LOGIN_BASE_URL、无 fallback 无重试，测试以注入 baseUrl 指向 MockWebServer。
 */
class PanQrApiTest {

    private lateinit var server: MockWebServer

    /** 固定设备身份：loginuuid / osversion / devicetype 断言的期望来源。 */
    private val identity = DeviceIdentity(
        deviceType = "M2011K2C",
        osVersion = "Android_13",
        loginUuid = "0123456789abcdef0123456789abcdef",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun api(): PanQrApiClient =
        PanQrApiClient(identity = { identity }, baseUrl = server.url("/").toString())

    private fun enqueueBody(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    // ------------------------------------------------------------------
    // qr_generate —— 请求头与解析（session.py:456-509）
    // ------------------------------------------------------------------

    @Test
    fun generateSendsMergedQrHeadersAndParsesUniIdUrl() = runTest {
        enqueueBody("""{"code":0,"message":"","data":{"uniID":"uni-1","url":"https://login.123pan.com/m/qr?k=1"}}""")

        val result = api().qrGenerate()

        assertTrue(result is ApiResult.Success)
        val dto = (result as ApiResult.Success<QrGenerateDto>).data
        assertEquals("uni-1", dto.uniId)
        assertEquals("https://login.123pan.com/m/qr?k=1", dto.url)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/user/qr-code/generate", request.path)
        // _qr_headers 合并结果逐字段（session.py:456-463 + 会话级 constants.py:30-35 / session.py:250-262）
        assertEquals(identity.loginUuid, request.getHeader("loginuuid"))
        assertEquals("web", request.getHeader("platform"))
        assertEquals("3", request.getHeader("app-version"))
        assertEquals("application/json;charset=UTF-8", request.getHeader("content-type"))
        assertEquals("Xiaomi", request.getHeader("devicename"))
        assertEquals("2.4.0", request.getHeader("x-app-version"))
        assertEquals("Android_13", request.getHeader("osversion"))
        assertEquals("M2011K2C", request.getHeader("devicetype"))
        assertEquals("123pan/v2.4.0(Android_13;Xiaomi)", request.getHeader("user-agent"))
    }

    @Test
    fun generateWithBlankUniIdOrUrlIsParseError() = runTest {
        // 参考源对缺失键返回空串（data.get("uniID", "")）——空 uniID 会造成坏码，这里按解析失败处理
        enqueueBody("""{"code":0,"message":"","data":{"uniID":"","url":"https://x"}}""")

        val result = api().qrGenerate()

        assertTrue(result is ApiResult.ParseError)
        // 解析失败不重试：单次请求（参考源 qr_generate 无重试）
        assertEquals(1, server.requestCount)
    }

    @Test
    fun generateNonZeroCodeIsApiErrorWithServerMessage() = runTest {
        enqueueBody("""{"code":4000,"message":"请求过快"}""")

        val result = api().qrGenerate()

        assertTrue(result is ApiResult.ApiError)
        val error = result as ApiResult.ApiError
        assertEquals(4000, error.code)
        assertEquals("请求过快", error.message)
    }

    @Test
    fun generateCodeTwoIsSessionExpired() = runTest {
        enqueueBody("""{"code":2,"message":"登录已过期"}""")

        assertTrue(api().qrGenerate() is ApiResult.SessionExpired)
    }

    @Test
    fun generateInvalidJsonIsParseError() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))

        assertTrue(api().qrGenerate() is ApiResult.ParseError)
    }

    // ------------------------------------------------------------------
    // qr_poll —— code==200 归一化 / code==0 透传（session.py:511-577）
    // ------------------------------------------------------------------

    @Test
    fun pollCode200NormalizesToConfirmedWithTokenAndLoginType() = runTest {
        enqueueBody("""{"code":200,"message":"","data":{"token":"jwt-abc","login_type":7}}""")

        val result = api().qrPoll("uni-9")

        assertTrue(result is ApiResult.Success)
        // code==200 = 已确认：loginStatus 恒 3，scanPlatform 取 data.login_type，token 直传（session.py:548-559）
        assertEquals(QrPollDto(loginStatus = 3, scanPlatform = 7, token = "jwt-abc"), (result as ApiResult.Success<QrPollDto>).data)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/user/qr-code/result", request.requestUrl!!.encodedPath)
        // query 键名 uniID 逐字（session.py:522）
        assertEquals("uni-9", request.requestUrl!!.queryParameter("uniID"))
        assertEquals(identity.loginUuid, request.getHeader("loginuuid"))
    }

    @Test
    fun pollCode200WechatScanCarriesPlatformFour() = runTest {
        enqueueBody("""{"code":200,"message":"","data":{"token":"","login_type":4}}""")

        val result = api().qrPoll("uni-9")

        assertTrue(result is ApiResult.Success)
        val dto = (result as ApiResult.Success<QrPollDto>).data
        assertEquals(3, dto.loginStatus)
        assertEquals(4, dto.scanPlatform)
        assertEquals("", dto.token)
    }

    @Test
    fun pollCodeZeroPassesLoginStatusThroughForWaitingScannedRejectedExpired() = runTest {
        val expectations = listOf(0, 1, 2, 4)
        expectations.forEach { status ->
            enqueueBody("""{"code":0,"message":"","data":{"loginStatus":$status,"scanPlatform":0}}""")
        }

        expectations.forEach { status ->
            val result = api().qrPoll("uni-1")
            assertTrue(result is ApiResult.Success)
            // code==0：loginStatus / scanPlatform 透传，token 恒空（session.py:568-577）
            assertEquals(
                QrPollDto(loginStatus = status, scanPlatform = 0, token = ""),
                (result as ApiResult.Success<QrPollDto>).data,
            )
        }
        assertEquals(expectations.size, server.requestCount)
    }

    @Test
    fun pollCodeZeroWithMissingDataFallsBackToReferenceDefaults() = runTest {
        // data 缺失时 loginStatus 默认 -1、scanPlatform 默认 0（session.py:574-575 的 get 缺省值）
        enqueueBody("""{"code":0,"message":""}""")

        val result = api().qrPoll("uni-1")

        assertTrue(result is ApiResult.Success)
        assertEquals(QrPollDto(loginStatus = -1, scanPlatform = 0, token = ""), (result as ApiResult.Success<QrPollDto>).data)
    }

    @Test
    fun pollNonZeroCodeIsApiError() = runTest {
        enqueueBody("""{"code":40001,"message":"会话不存在"}""")

        val result = api().qrPoll("uni-gone")

        assertTrue(result is ApiResult.ApiError)
        assertEquals(40001, (result as ApiResult.ApiError).code)
        assertEquals("会话不存在", result.message)
    }

    @Test
    fun pollCodeTwoIsSessionExpired() = runTest {
        enqueueBody("""{"code":2,"message":"登录已过期"}""")

        assertTrue(api().qrPoll("uni-1") is ApiResult.SessionExpired)
    }

    @Test
    fun pollNetworkFailureMapsToNetworkErrorWithoutRetry() = runTest {
        // 连接层失败直接 NetworkError，不重试（参考源 qr_poll 对 RequestException 即返回失败）
        server.shutdown()

        val result = api().qrPoll("uni-1")

        assertTrue(result is ApiResult.NetworkError)
    }
}
