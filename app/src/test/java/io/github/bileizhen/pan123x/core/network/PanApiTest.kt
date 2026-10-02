package io.github.bileizhen.pan123x.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PanApi 协议回归测试：请求头 / 包络解析 / code 语义 / fallback 粘滞 / 幂等退避 / 超时。
 * 全部走 MockWebServer，不访问真实 123 云盘。
 */
class PanApiTest {

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
        auth: AuthorizationProvider = AuthorizationProvider { null },
        fallback: MockWebServer? = null,
        client: OkHttpClient = PanHttpClientFactory.defaultClient(device = { device }, auth = auth),
    ): PanApi = PanApi(
        client = client,
        primaryBaseUrl = server.url("/").toString(),
        fallbackBaseUrl = fallback?.url("/")?.toString(),
    )

    private fun loginOk(token: String = "abc123") =
        MockResponse().setBody("""{"code":200,"message":"ok","data":{"token":"$token"}}""")

    private fun rateLimited() =
        MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}""")

    @Test
    fun loginSuccessSendsProtocolHeadersAndReturnsBearerToken() = runTest {
        server.enqueue(loginOk())

        val result = api().login("user@example.com", "p@ss")

        assertTrue(result is ApiResult.Success)
        assertEquals("Bearer abc123", (result as ApiResult.Success<String>).data)

        val request = server.takeRequest()
        assertEquals("/b/api/user/sign_in", request.path)
        assertEquals("POST", request.method)
        assertEquals("android", request.getHeader("platform"))
        assertEquals("Xiaomi", request.getHeader("devicename"))
        assertEquals("61", request.getHeader("app-version"))
        assertEquals("2.4.0", request.getHeader("x-app-version"))
        assertEquals("123pan/v2.4.0(Android_13;Xiaomi)", request.getHeader("user-agent"))
        assertEquals("Android_13", request.getHeader("osversion"))
        assertEquals("M2011K2C", request.getHeader("devicetype"))
        assertEquals("0123456789abcdef0123456789abcdef", request.getHeader("loginuuid"))
        assertEquals("application/json", request.getHeader("content-type"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(1, body["type"]!!.jsonPrimitive.int)
        assertEquals("user@example.com", body["passport"]!!.jsonPrimitive.content)
        assertEquals("p@ss", body["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun loginApiErrorPropagatesCodeAndMessage() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":401,"message":"用户名或密码错误"}"""))

        val result = api().login("user@example.com", "wrong")

        assertTrue(result is ApiResult.ApiError)
        result as ApiResult.ApiError
        assertEquals(401, result.code)
        assertEquals("用户名或密码错误", result.message)
    }

    @Test
    fun loginInvalidHtmlBodyReturnsParseError() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>Bad Gateway</body></html>"))

        val result = api().login("user@example.com", "p@ss")

        assertTrue(result is ApiResult.ParseError)
        assertEquals("服务器返回无效 JSON (HTTP 200)", (result as ApiResult.ParseError).message)
    }

    @Test
    fun bodyCodeTwoMapsToSessionExpiredOnBothEndpoints() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))

        val panApi = api()

        assertTrue(panApi.login("user@example.com", "p@ss") is ApiResult.SessionExpired)
        assertTrue(panApi.getUserInfo() is ApiResult.SessionExpired)
    }

    @Test
    fun userInfoSuccessParsesPascalCaseFieldsWithStringNumberTolerance() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"message":"ok","data":{
                  "UID":"123456789",
                  "Nickname":"测试用户",
                  "SpaceUsed":"5251000000",
                  "SpacePermanent":28500000000000,
                  "SpaceTemp":0,
                  "FileCount":42,
                  "Vip":1,
                  "VipExpire":"2027-01-01",
                  "VipLevel":"1",
                  "HeadImage":"https://example.com/avatar.png",
                  "DirectTraffic":1073741824,
                  "ShareTraffic":0,
                  "Passport":"13800138000",
                  "ProfessionalSpacePermanent":1000,
                  "ProfessionalSpaceUsed":100,
                  "StandardSpacePermanent":2000,
                  "StandardSpaceUsed":200
                }}
                """.trimIndent(),
            ),
        )

        val result = api().getUserInfo()

        assertTrue(result is ApiResult.Success)
        val info = (result as ApiResult.Success<UserInfoDto>).data
        assertEquals(123456789L, info.uid)
        assertEquals("测试用户", info.nickname)
        assertEquals(5251000000L, info.spaceUsed)
        assertEquals(28500000000000L, info.spaceTotal)
        assertEquals(0L, info.spaceTemp)
        assertEquals(42L, info.fileCount)
        assertTrue(info.vip)
        assertEquals("2027-01-01", info.vipExpire)
        assertEquals(1, info.vipLevel)
        assertEquals("https://example.com/avatar.png", info.headImage)
        assertEquals(1073741824L, info.directTraffic)
        assertEquals(0L, info.shareTraffic)
        assertEquals(13800138000L, info.passport)
        assertEquals(1000L, info.professionalSpacePermanent)
        assertEquals(100L, info.professionalSpaceUsed)
        assertEquals(2000L, info.standardSpacePermanent)
        assertEquals(200L, info.standardSpaceUsed)
    }

    @Test
    fun userInfoFallsBackToLowercaseKeys() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"data":{
                  "uid":7,"nickname":"n","spaceUsed":1,"spacePermanent":2,"spaceTemp":3,
                  "fileCount":4,"vip":false,"vipLevel":5,"headImage":"h",
                  "directTraffic":6,"shareTraffic":7,"passport":8
                }}
                """.trimIndent(),
            ),
        )

        val result = api().getUserInfo()

        assertTrue(result is ApiResult.Success)
        val info = (result as ApiResult.Success<UserInfoDto>).data
        assertEquals(7L, info.uid)
        assertEquals("n", info.nickname)
        assertEquals(1L, info.spaceUsed)
        assertEquals(2L, info.spaceTotal)
        assertEquals(3L, info.spaceTemp)
        assertEquals(4L, info.fileCount)
        assertTrue(!info.vip)
        assertEquals(5, info.vipLevel)
        assertEquals(8L, info.passport)
    }

    @Test
    fun connectionFailureFallsBackToAlternateHostAndSticks() = runTest {
        val fallbackServer = MockWebServer().also { it.start() }
        fallbackServer.enqueue(MockResponse().setBody("""{"code":0,"data":{"UID":1,"Nickname":"fb"}}"""))
        fallbackServer.enqueue(MockResponse().setBody("""{"code":0,"data":{"UID":1,"Nickname":"fb"}}"""))
        // 先构造 PanApi 再关停主服务器：shutdown 后 MockWebServer 无法再提供 url/port
        val panApi = api(fallback = fallbackServer)
        server.shutdown()

        assertTrue(panApi.getUserInfo() is ApiResult.Success)
        assertTrue(panApi.getUserInfo() is ApiResult.Success)

        assertEquals(0, server.requestCount)
        assertEquals(2, fallbackServer.requestCount)
        runCatching { fallbackServer.shutdown() }
    }

    @Test
    fun getUserInfoRetriesRateLimitOnceThenSucceeds() = runTest {
        server.enqueue(rateLimited())
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"UID":1,"Nickname":"ok"}}"""))

        val result = api().getUserInfo()

        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun persistentRateLimitStopsAfterBoundedRetries() = runTest {
        repeat(3) { server.enqueue(rateLimited()) }

        val result = api().getUserInfo()

        assertTrue(result is ApiResult.ApiError)
        assertEquals(429, (result as ApiResult.ApiError).code)
        assertEquals("请求过于频繁", result.message)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun httpServerErrorWithHtmlBodyRetriesThenReturnsParseError() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>")) }

        val result = api().getUserInfo()

        assertTrue(result is ApiResult.ParseError)
        assertEquals("服务器返回无效 JSON (HTTP 502)", (result as ApiResult.ParseError).message)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun loginNeverRetriesRateLimit() = runTest {
        server.enqueue(rateLimited())
        server.enqueue(rateLimited())

        val result = api().login("user@example.com", "p@ss")

        assertTrue(result is ApiResult.ApiError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun readTimeoutSurfacesNetworkError() = runBlocking {
        // 读超时 5s（PanHttpClientFactory 默认），body 延迟 6s 触发 SocketTimeoutException
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(6, TimeUnit.SECONDS))

        val result = api().login("user@example.com", "p@ss")

        assertTrue(result is ApiResult.NetworkError)
    }

    @Test
    fun authorizationHeaderFollowsProvider() = runTest {
        server.enqueue(loginOk())
        api().login("user@example.com", "p@ss")
        assertNull(server.takeRequest().getHeader("authorization"))

        server.enqueue(loginOk())
        api(auth = AuthorizationProvider { "Bearer abc" }).login("user@example.com", "p@ss")
        assertEquals("Bearer abc", server.takeRequest().getHeader("authorization"))
    }
}
