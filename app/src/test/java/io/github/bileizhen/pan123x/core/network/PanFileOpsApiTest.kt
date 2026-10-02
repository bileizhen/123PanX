package io.github.bileizhen.pan123x.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
 * PanFileOpsApi 协议回归测试（接口契约， 不触真实网络）。
 * 重点断言大小写陷阱：trash 内层大写 `FileId` 只含该键、永久删除内层小写 `fileId`、
 * mod_pid 内层大写 `FileId`、rename 全小驼峰、upload_request 十字段固定 body；
 * 以及非幂等 POST 绝不自动重试与复制轮询 GET 的幂等退避。
 */
class PanFileOpsApiTest {

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

    private fun okNoData() = MockResponse().setBody("""{"code":0,"message":"ok"}""")

    private fun lastBody(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun createFolderSendsTenFieldBodyVerbatim() = runTest {
        server.enqueue(ok("""{"Info":{"FileId":501}}"""))

        val result = api().createFolder(parentFileId = 0, folderName = "新建文件夹")

        assertTrue(result is ApiResult.Success)
        assertEquals(501L, (result as ApiResult.Success<Long>).data)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/a/api/file/upload_request", request.path)
        // 十字段逐字回归：键名（含大写开头的 NotReuse）、顺序、值类型与参考源 create_dir 一致
        assertEquals(
            """{"driveId":0,"etag":"","fileName":"新建文件夹","parentFileId":0,"size":0,""" +
                """"type":1,"duplicate":1,"NotReuse":true,"event":"newCreateFolder","operateType":1}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun createFolderMissingInfoReturnsParseError() = runTest {
        server.enqueue(ok("""{}"""))

        val result = api().createFolder(parentFileId = 0, folderName = "a")

        assertTrue(result is ApiResult.ParseError)
    }

    @Test
    fun trashFileSendsInnerUppercaseFileIdOnly() = runTest {
        server.enqueue(ok("""{"InfoList":[{"FileId":123}],"AbnormalFileIdList":[]}"""))

        val result = api().trashFile(fileId = 123, restore = false)

        assertTrue(result is ApiResult.Success)
        assertEquals(listOf(123L), (result as ApiResult.Success<TrashData>).data.infoList)
        assertTrue(result.data.abnormalFileIds.isEmpty())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/a/api/file/trash", request.path)
        // 内层大写 FileId 且只含该键：传完整文件对象会被服务器静默忽略（code=0 但不删）
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(3, body.size)
        assertEquals("0", body["driveId"]!!.jsonPrimitive.content)
        assertEquals("123", body["fileTrashInfoList"]!!.jsonArray[0].jsonObject["FileId"]!!.jsonPrimitive.content)
        assertEquals(1, body["fileTrashInfoList"]!!.jsonArray[0].jsonObject.size)
        assertEquals("true", body["operation"]!!.jsonPrimitive.content)
    }

    @Test
    fun restoreFileSendsOperationFalse() = runTest {
        server.enqueue(ok("""{"InfoList":[{"FileId":9}],"AbnormalFileIdList":[]}"""))

        val result = api().trashFile(fileId = 9, restore = true)

        assertTrue(result is ApiResult.Success)
        assertEquals("false", lastBody()["operation"]!!.jsonPrimitive.content)
    }

    @Test
    fun trashDataToleratesMissingOrNullDataAndCamelCaseKeys() = runTest {
        server.enqueue(ok("""{"infoList":[{"fileId":7},{"FileId":8}],"abnormalFileIdList":[9,"10"]}"""))
        val withLists = api().trashFile(fileId = 7, restore = false)
        assertTrue(withLists is ApiResult.Success)
        assertEquals(listOf(7L, 8L), (withLists as ApiResult.Success<TrashData>).data.infoList)
        assertEquals(listOf(9L, 10L), withLists.data.abnormalFileIds)

        server.enqueue(ok("null"))
        val nullData = api().trashFile(fileId = 7, restore = false)
        assertTrue(nullData is ApiResult.Success)
        assertTrue((nullData as ApiResult.Success<TrashData>).data.infoList.isEmpty())

        server.enqueue(okNoData())
        val noData = api().trashFile(fileId = 7, restore = false)
        assertTrue(noData is ApiResult.Success)
        assertTrue((noData as ApiResult.Success<TrashData>).data.abnormalFileIds.isEmpty())
    }

    @Test
    fun deleteForeverSendsLowercaseInnerIdsWithRecycleDeleteEvent() = runTest {
        server.enqueue(okNoData())

        val result = api().deleteForever(listOf(11, 12))

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/file/delete", request.path)
        // 内层小写 fileId（与 trash 相反）+ event/operatePlace/RequestSource:null
        assertEquals(
            """{"fileIdList":[{"fileId":11},{"fileId":12}],"event":"recycleDelete",""" +
                """"operatePlace":1,"RequestSource":null}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun renameFileSendsLowerCamelBody() = runTest {
        server.enqueue(okNoData())

        val result = api().renameFile(fileId = 33, newFileName = "b.txt")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/a/api/file/rename", request.path)
        assertEquals("""{"driveId":0,"fileId":33,"fileName":"b.txt"}""", request.body.readUtf8())
    }

    @Test
    fun moveFilesSendsInnerUppercaseFileIdWithTargetParent() = runTest {
        server.enqueue(okNoData())

        val result = api().moveFiles(fileIds = listOf(1, 2), targetParentId = 77)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("/b/api/file/mod_pid", request.path)
        assertEquals("""{"fileIdList":[{"FileId":1},{"FileId":2}],"parentFileId":77}""", request.body.readUtf8())
    }

    @Test
    fun submitCopySendsFileListAndParsesDualCaseTaskId() = runTest {
        val passthrough = listOf(
            Json.parseToJsonElement("""{"FileId":1,"FileName":"a.txt"}""") as JsonObject,
            Json.parseToJsonElement("""{"FileId":2}""") as JsonObject,
        )
        server.enqueue(ok("""{"taskId":"t-1"}"""))

        val result = api().submitCopy(passthrough, targetFileId = 77)

        assertTrue(result is ApiResult.Success)
        assertEquals("t-1", (result as ApiResult.Success<CopySubmitData>).data.taskId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/restful/goapi/v1/file/copy/async", request.path)
        // fileList 原样透传（完整对象或降级 {"FileId":x} 均由仓库构造）
        assertEquals(
            """{"fileList":[{"FileId":1,"FileName":"a.txt"},{"FileId":2}],"targetFileId":77}""",
            request.body.readUtf8(),
        )

        server.enqueue(ok("""{"taskID":"T-2"}"""))
        val upper = api().submitCopy(passthrough, targetFileId = 77)
        assertEquals("T-2", (upper as ApiResult.Success<CopySubmitData>).data.taskId)

        server.enqueue(ok("""{}"""))
        val missing = api().submitCopy(passthrough, targetFileId = 77)
        assertNull((missing as ApiResult.Success<CopySubmitData>).data.taskId)
    }

    @Test
    fun pollCopyTaskUsesTaskIdQueryAndParsesStatusFailMsg() = runTest {
        server.enqueue(ok("""{"status":1}"""))

        val running = api().pollCopyTask("t-1")

        assertTrue(running is ApiResult.Success)
        assertEquals(1, (running as ApiResult.Success<CopyTaskData>).data.status)
        assertNull(running.data.failMsg)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/b/api/restful/goapi/v1/file/copy/task", request.requestUrl!!.encodedPath)
        assertEquals("t-1", request.requestUrl!!.queryParameter("taskId"))

        server.enqueue(ok("""{"status":3,"failMsg":"目标空间不足"}"""))
        val failed = api().pollCopyTask("t-1")
        assertEquals(3, (failed as ApiResult.Success<CopyTaskData>).data.status)
        assertEquals("目标空间不足", failed.data.failMsg)

        server.enqueue(ok("""{"status":"2"}"""))
        val stringStatus = api().pollCopyTask("t-1")
        assertEquals(2, (stringStatus as ApiResult.Success<CopyTaskData>).data.status)

        server.enqueue(ok("""{}"""))
        val noStatus = api().pollCopyTask("t-1")
        assertNull((noStatus as ApiResult.Success<CopyTaskData>).data.status)
    }

    @Test
    fun postOperationDoesNotRetryOnRateLimitOrServerError() = runTest {
        // 非幂等 POST 绝不自动重试：429 / 5xx 也只发一次
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":500,"message":"服务器错误"}"""))

        val limited = api().trashFile(fileId = 1, restore = false)
        assertTrue(limited is ApiResult.ApiError)
        assertEquals(429, (limited as ApiResult.ApiError).code)
        val broken = api().renameFile(fileId = 1, newFileName = "x")
        assertTrue(broken is ApiResult.ApiError)
        assertEquals(500, (broken as ApiResult.ApiError).code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun pollGetRetriesRateLimitOnceThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}"""))
        server.enqueue(ok("""{"status":2}"""))

        val result = api().pollCopyTask("t-9")

        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun opsBodyCodeTwoMapsToSessionExpired() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))
        assertTrue(api().trashFile(fileId = 1, restore = false) is ApiResult.SessionExpired)

        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))
        assertTrue(api().pollCopyTask("t") is ApiResult.SessionExpired)
    }

    @Test
    fun deleteForeverCarriesJsonNullRequestSource() = runTest {
        server.enqueue(okNoData())
        api().deleteForever(listOf(1))
        // RequestSource 必须是 JSON null 字面量而不是字符串 "null"（参考源 data 固定 None）
        assertEquals(JsonNull, lastBody()["RequestSource"])
    }
}
