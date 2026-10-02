package io.github.bileizhen.pan123x.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PanOfflineApi 协议回归测试（， 不触真实网络）。
 *
 * 协议真源 `.reference/123pan` `offline_service.py:51-98`：逐字断言 resolve 的单键
 * `{"urls":...}` body 与 submit 的 `resource_list` 嵌套 body；data.list 双名解析
 * （snake_case 为 view 层实际用法，camelCase 为兼容形态）；result!=0 项 ok=false 带原文；
 * 非 0 code → ApiError、code==2 → SessionExpired；非幂等 POST 绝不自动重试。
 *
 * 离线两端点固定走 offlineBaseUrl（不走主 / 备线路），测试直接把该字段改指 MockWebServer。
 */
class PanOfflineApiTest {

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

    private fun api(): PanApi = PanApi(
        client = PanHttpClientFactory.defaultClient(device = { device }, auth = AuthorizationProvider { "Bearer t" }),
        primaryBaseUrl = server.url("/").toString(),
        fallbackBaseUrl = null,
        shareBaseUrl = server.url("/").toString(),
    ).apply { offlineBaseUrl = server.url("/").toString() }

    private fun ok(data: String) = MockResponse().setBody("""{"code":0,"message":"ok","data":$data}""")

    // ------------------------------------------------------------------
    // resolve —— body 逐字与端点（offline_service.py:66-74）
    // ------------------------------------------------------------------

    @Test
    fun resolveSendsSingleKeyBodyVerbatimToOfflinePath() = runTest {
        server.enqueue(ok("""{"list":[]}"""))

        val result = api().resolve("http://a.com/1.zip\nhttp://b.com/2.zip")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/v2/offline_download/task/resolve", request.path)
        // body 仅一个键 urls，换行原文保留（offline_service.py:68 `json={"urls": urls}`）
        assertEquals(
            "{\"urls\":\"http://a.com/1.zip\\nhttp://b.com/2.zip\"}",
            request.body.readUtf8(),
        )
    }

    @Test
    fun resolveParsesSnakeCaseListPerViewLayerUsage() = runTest {
        // snake_case 字段：id（resource id 主键，offline_download_dialog.py:504）、
        // file_nums、err_msg；result==0 为可下载；files[] 内层主键同样是 id
        server.enqueue(
            ok(
                """{"list":[{"url":"http://a/1.zip","type":1,"result":0,"name":"1.zip",""" +
                    """"size":2048,"id":501,"file_nums":1,"files":[{"id":9001,"name":"1.zip","size":2048}],"""+
                    """"hash":"h1"},{"url":"magnet:?xt=1","type":2,"result":1,"name":"","size":0,""" +
                    """"id":0,"file_nums":0,"files":[],"err_code":100,"err_msg":"链接不支持"}]}""",
            ),
        )

        val items = (api().resolve("u") as ApiResult.Success<List<OfflineResolvedItem>>).data

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("http://a/1.zip", first.url)
        assertEquals(1, first.type)
        assertEquals(true, first.ok)
        assertEquals("1.zip", first.name)
        assertEquals(2048L, first.size)
        assertEquals(501L, first.resourceId)
        assertEquals(1, first.fileNums)
        assertEquals(1, first.files.size)
        assertEquals(9001L, first.files[0].fileId)
        assertEquals("1.zip", first.files[0].name)
        assertEquals(2048L, first.files[0].size)
        assertEquals("", first.errMessage)
        val second = items[1]
        assertEquals(false, second.ok)
        assertEquals(0, second.resourceId)
        assertTrue(second.files.isEmpty())
        assertEquals("链接不支持", second.errMessage)
    }

    @Test
    fun resolveParsesCamelCaseFallbackKeys() = runTest {
        // camelCase 兼容形态：resourceId / fileNums / errMsg；result 缺失按失败（view 层缺省 1）
        server.enqueue(
            ok(
                """{"list":[{"url":"http://a/1.zip","result":1,"resourceId":77,"fileNums":3,""" +
                    """"errMsg":"配额不足"},{"url":"http://b/2.zip","name":"2.zip"}]}""",
            ),
        )

        val items = (api().resolve("u") as ApiResult.Success<List<OfflineResolvedItem>>).data

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals(77L, first.resourceId)
        assertEquals(3, first.fileNums)
        assertEquals(false, first.ok)
        assertEquals("配额不足", first.errMessage)
        val second = items[1]
        assertEquals(false, second.ok) // result 缺失 → 1 → 失败
        assertEquals("", second.errMessage)
    }

    @Test
    fun resolveToleratesMissingDataAndListShapes() = runTest {
        // 参考源 offline_service.py:74 `(data or {}).get("list") or []` 的三种容错形状
        server.enqueue(ok("{}"))
        val missing = api().resolve("u")
        assertTrue(missing is ApiResult.Success)
        assertTrue((missing as ApiResult.Success<List<OfflineResolvedItem>>).data.isEmpty())
        assertTrue(server.requestCount == 1)
        server.takeRequest()

        server.enqueue(ok("""{"list":null}"""))
        val nullList = api().resolve("u")
        assertTrue((nullList as ApiResult.Success<List<OfflineResolvedItem>>).data.isEmpty())
        server.takeRequest()

        server.enqueue(ok("""{"list":["not-an-object"]}"""))
        val junkEntry = api().resolve("u")
        assertTrue((junkEntry as ApiResult.Success<List<OfflineResolvedItem>>).data.isEmpty())
    }

    @Test
    fun resolveNonZeroCodeMapsToApiError() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":4000,"message":"链接格式错误"}"""))

        val result = api().resolve("u")

        assertTrue(result is ApiResult.ApiError)
        val error = result as ApiResult.ApiError
        assertEquals(4000, error.code)
        assertEquals("链接格式错误", error.message)
    }

    @Test
    fun resolveBodyCodeTwoMapsToSessionExpired() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))

        assertTrue(api().resolve("u") is ApiResult.SessionExpired)
    }

    @Test
    fun resolvePostIsNeverRetriedAutomatically() = runTest {
        // 非幂等 POST 绝不自动重试：429 也只发一次
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"频繁"}"""))

        val result = api().resolve("u")

        assertTrue(result is ApiResult.ApiError)
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------------
    // submit —— body 逐字与 task_list 解析（offline_service.py:80-96）
    // ------------------------------------------------------------------

    @Test
    fun submitSendsResourceListBodyVerbatimAndParsesTaskList() = runTest {
        server.enqueue(
            ok(
                """{"task_list":[{"task_id":"t-1","result":0},{"taskId":"t-2","result":1,""" +
                    """"err_msg":"额度不足"}]}""",
            ),
        )

        val result = api().submit(
            listOf(
                OfflineResource(resourceId = 501, selectFileIds = listOf(9001, 9002)),
                OfflineResource(resourceId = 502, selectFileIds = emptyList()),
            ),
        )

        assertTrue(result is ApiResult.Success)
        val tasks = (result as ApiResult.Success<List<OfflineSubmittedTask>>).data
        assertEquals(2, tasks.size)
        assertEquals("t-1", tasks[0].taskId)
        assertEquals(true, tasks[0].ok)
        assertEquals("", tasks[0].errMessage)
        // taskId 双名兼容：camelCase 也能解析
        assertEquals("t-2", tasks[1].taskId)
        assertEquals(false, tasks[1].ok)
        assertEquals("额度不足", tasks[1].errMessage)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/v2/offline_download/task/submit", request.path)
        // resource_list 嵌套结构逐字：select_file_id 空数组表示整个资源（view 层 :521 语义）
        assertEquals(
            """{"resource_list":[{"resource_id":501,"select_file_id":[9001,9002]},""" +
                """{"resource_id":502,"select_file_id":[]}]}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun submitToleratesMissingTaskListAndNonZeroCode() = runTest {
        server.enqueue(ok("{}"))
        val missing = api().submit(listOf(OfflineResource(1, emptyList())))
        assertTrue(missing is ApiResult.Success)
        assertTrue((missing as ApiResult.Success<List<OfflineSubmittedTask>>).data.isEmpty())
        server.takeRequest()

        server.enqueue(MockResponse().setBody("""{"code":500,"message":"服务繁忙"}"""))
        val error = api().submit(listOf(OfflineResource(1, emptyList())))
        assertTrue(error is ApiResult.ApiError)
        assertEquals("服务繁忙", (error as ApiResult.ApiError).message)
    }
}
