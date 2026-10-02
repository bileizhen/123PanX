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
 * PanShareApi 协议回归测试（不触真实网络）。
 *
 * 协议真源 `.reference/123pan` `share_service.py` / `file_service.py#share`：本套件重点
 * 逐字断言 create 的 14 字段 body、list 的 8 个 query 参数与 delete 的 5 字段 body；
 * 另覆盖 `shareLinkList` 三种缺失形状（缺失 / null / 空对象）、分页 `Next=="-1"` 短路、
 * 非 0 code → ApiError、`code==2` → SessionExpired、非幂等 POST 绝不自动重试。
 *
 * 分享三端点不走主 / 备线路（参考源 SHARE_API_BASE 直连），因此 PanApi 需注入
 * shareBaseUrl 指向 MockWebServer。
 */
class PanShareApiTest {
    @Test fun customTitleExpiryAndPasswordReachTheWireWithoutChangingOtherFields() = runTest {
        server.enqueue(ok("""{"ShareKey":"abc"}"""))
        api().createShare(listOf(7), io.github.bileizhen.pan123x.core.share.ShareCreateOptions("我的档案", "Ab12", 7))
        val body = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertEquals("我的档案", (body["shareName"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Ab12", (body["sharePwd"] as kotlinx.serialization.json.JsonPrimitive).content)
        val expiry = java.time.OffsetDateTime.parse((body["expiration"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(java.time.Duration.between(java.time.Instant.now(), expiry.toInstant()).toDays() in 6L..7L)
        assertEquals("4", body["shareModality"].toString())
        assertEquals(14, body.size)
    }

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
    )

    private fun ok(data: String) = MockResponse().setBody("""{"code":0,"message":"ok","data":$data}""")

    // ------------------------------------------------------------------
    // create —— body 十四字段逐字（file_service.py:391-406）
    // ------------------------------------------------------------------

    @Test
    fun createShareSendsFourteenFieldBodyVerbatimAndBuildsUrl() = runTest {
        server.enqueue(ok("""{"ShareKey":"abc123"}"""))

        val result = api().createShare(fileIds = listOf(101, 102), sharePwd = "ab12")

        assertTrue(result is ApiResult.Success)
        val dto = (result as ApiResult.Success<CreateShareDto>).data
        // 链接 = "https://www.123pan.cn/s/" + ShareKey（file_service.py:415-416）
        assertEquals("abc123", dto.shareKey)
        assertEquals("https://www.123pan.cn/s/abc123", dto.url)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/share/create", request.path)
        // 十四字段键名、取值与顺序逐字照抄参考源；fileIdList 是逗号连接的字符串而非数组
        assertEquals(
            """{"driveId":0,"expiration":"2099-12-12T08:00:00+08:00",""" +
                """"fileIdList":"101,102","shareName":"123云盘分享","sharePwd":"ab12","event":"shareCreate",""" +
                """"fileNum":2,"renameVisible":false,"shareModality":4,"operatePlace":2,""" +
                """"trafficLimitSwitch":1,"trafficLimit":0,"trafficSwitch":1,"fillPwdSwitch":0}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun createShareDefaultsBlankPasswordAndCountsFiles() = runTest {
        server.enqueue(ok("""{"ShareKey":"k"}"""))

        api().createShare(fileIds = listOf(7))

        // 默认 sharePwd 为空串（参考源 `share_pwd or ""`），fileNum = 归一化后的个数
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"sharePwd\":\"\""))
        assertTrue(body.contains("\"fileNum\":1"))
    }

    @Test
    fun createSharePostIsNeverRetriedAutomatically() = runTest {
        // 非幂等 POST 绝不自动重试：429 也只发一次
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}"""))

        val result = api().createShare(fileIds = listOf(1))

        assertTrue(result is ApiResult.ApiError)
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------------
    // list —— query 八参数逐字（share_service.py:43-52）
    // ------------------------------------------------------------------

    @Test
    fun listSharesSendsQueryVerbatimInReferenceOrder() = runTest {
        server.enqueue(ok("""{"Next":"-1","Total":0,"InfoList":[]}"""))

        val result = api().listShares()

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        val url = request.requestUrl!!
        assertEquals("/b/api/share/list", url.encodedPath)
        // 参数名顺序与取值逐字对齐参考源；SearchData 大写 S 是服务端要求，禁止"规范化"
        assertEquals(
            listOf("driveId", "limit", "next", "orderBy", "orderDirection", "SearchData", "event", "operateType"),
            url.queryParameterNames.toList(),
        )
        assertEquals("0", url.queryParameter("driveId"))
        assertEquals("500", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("next"))
        assertEquals("fileId", url.queryParameter("orderBy"))
        assertEquals("desc", url.queryParameter("orderDirection"))
        assertEquals("", url.queryParameter("SearchData"))
        assertEquals("shareListFile", url.queryParameter("event"))
        assertEquals("1", url.queryParameter("operateType"))
    }

    @Test
    fun listSharesParsesItemsWithDualCaseAndPagination() = runTest {
        server.enqueue(
            ok(
                """{"Next":"12","Total":2,"InfoList":[${pascalItem()},{"shareId":12,"shareKey":"keyB","expired":true}]}""",
            ),
        )

        val page = (api().listShares(limit = 100, next = "0") as ApiResult.Success<SharePageDto>).data

        assertEquals("12", page.next)
        assertEquals(2, page.total)
        val first = page.items[0]
        assertEquals(11L, first.shareId)
        assertEquals("keyA", first.shareKey)
        assertEquals("901,902", first.fileIdList)
        assertEquals(3, first.downloadCount)
        assertEquals(4, first.previewCount)
        assertEquals(5, first.saveCount)
        assertEquals("相册", first.shareName)
        assertEquals("2099-12-12T08:00:00+08:00", first.expiration)
        assertEquals(false, first.expired)
        assertEquals("ab12", first.sharePwd)
        assertEquals(1, first.status)
        assertEquals("2026-01-01 10:00:00", first.createAt)
        assertEquals("2026-01-02 10:00:00", first.updateAt)
        assertEquals("https://www.123pan.cn/s/keyA?pwd=ab12", first.shareUrl)
        // shareLink 取 shareLinkList.list[0]
        assertEquals("https://www.123pan.cn/s/keyA", first.shareLink)
        // 小驼峰回退键（model.py:278-296 get("ShareId", get("shareId", ...)) 语义）
        val second = page.items[1]
        assertEquals(12L, second.shareId)
        assertEquals("keyB", second.shareKey)
        assertEquals(true, second.expired)
        assertEquals("", second.shareLink)
    }

    @Test
    fun listSharesToleratesMissingShareLinkListShapes() = runTest {
        // shareLinkList 三种形状均按参考源 `json.get("shareLinkList", {}) or {}` 处理为无链接
        server.enqueue(
            ok(
                """{"Next":"-1","Total":3,"InfoList":[{"ShareId":1,"ShareKey":"a","shareLinkList":{}},""" +
                    """{"ShareId":2,"ShareKey":"b","shareLinkList":null},""" +
                    """{"ShareId":3,"ShareKey":"c","shareLinkList":{"list":[]}}]}""",
            ),
        )

        val page = (api().listShares() as ApiResult.Success<SharePageDto>).data

        assertEquals(listOf("", "", ""), page.items.map { it.shareLink })
    }

    @Test
    fun listSharesNextNegativeOneMarksNoMore() = runTest {
        server.enqueue(ok("""{"Next":"-1","Total":0,"InfoList":[]}"""))

        val page = (api().listShares(next = "12") as ApiResult.Success<SharePageDto>).data

        assertEquals("-1", page.next)
        assertTrue(page.items.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun listSharesShortCircuitsWithoutRequestWhenNoMore() = runTest {
        // 游标 "-1" 直接返回空页，不发网络请求（接口接口约定）
        val page = (api().listShares(next = "-1") as ApiResult.Success<SharePageDto>).data

        assertEquals("-1", page.next)
        assertTrue(page.items.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun listGetRetriesOnServerErrorThenSucceeds() = runTest {
        // list 为幂等 GET（参考源 timeout=10），429/5xx 允许有限退避重试
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":500,"message":"oops"}"""))
        server.enqueue(ok("""{"Next":"-1","Total":0,"InfoList":[]}"""))

        val result = api().listShares()

        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun bodyCodeTwoMapsToSessionExpired() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))

        val result = api().listShares()

        assertTrue(result is ApiResult.SessionExpired)
    }

    // ------------------------------------------------------------------
    // delete —— body 五字段逐字（share_service.py:135-141）
    // ------------------------------------------------------------------

    @Test
    fun deleteShareSendsBodyVerbatimWithLowercaseInnerKey() = runTest {
        server.enqueue(ok("{}"))

        val result = api().deleteShare(shareId = 123)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/share/delete", request.path)
        // 内层键是小写 shareId（与 trash 的内层大写 FileId 相反），五字段顺序照抄参考源
        assertEquals(
            """{"driveId":0,"shareInfoList":[{"shareId":123}],"isPayShare":0,"event":"shareCancel","operatePlace":2}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun nonZeroCodePassesThroughAsApiError() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":4000,"message":"分享不存在"}"""))

        val result = api().deleteShare(shareId = 123)

        assertTrue(result is ApiResult.ApiError)
        val error = result as ApiResult.ApiError
        assertEquals(4000, error.code)
        assertEquals("分享不存在", error.message)
    }

    /** 大驼峰完整分享条目（shareLinkList 携带一条链接）。 */
    private fun pascalItem() =
        """{"ShareId":11,"ShareKey":"keyA","FileIdList":"901,902","DownloadCount":3,"PreviewCount":4,""" +
            """"SaveCount":5,"ShareName":"相册","Expiration":"2099-12-12T08:00:00+08:00","Expired":false,""" +
            """"SharePwd":"ab12","Status":1,"CreateAt":"2026-01-01 10:00:00","UpdateAt":"2026-01-02 10:00:00",""" +
            """"ShareUrl":"https://www.123pan.cn/s/keyA?pwd=ab12","shareLinkList":{"list":["https://www.123pan.cn/s/keyA"]}}"""
}
