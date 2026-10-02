package io.github.bileizhen.pan123x.core.network

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
 * PanFileApi 协议回归测试（`GET /api/file/list/new`）：query 十参数逐项断言（含大小写
 * 混用、trashed 字符串化、SearchData 空串）、InfoList / 分页元数据双键解析、时间戳
 * 容错、code==2、非法 JSON、429 幂等退避。全部走 MockWebServer，不访问真实 123 云盘。
 */
class PanFileApiTest {

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

    private fun fileListOk(data: String) =
        MockResponse().setBody("""{"code":0,"message":"ok","data":$data}""")

    @Test
    fun getFileListSendsAllTenQueryParametersVerbatim() = runTest {
        server.enqueue(fileListOk("""{"InfoList":[],"Total":0,"Next":"-1","Len":0,"IsFirst":true}"""))

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        // 整串回归：参数名大小写、顺序、空值编码与参考源 session_file.py 一致
        assertEquals(
            "/api/file/list/new?driveId=0&limit=100&next=0&orderBy=file_id&orderDirection=desc" +
                "&parentFileId=0&trashed=false&SearchData=&Page=1&OnlyLookAbnormalFile=0",
            request.path,
        )
        val url = requireNotNull(request.requestUrl)
        assertEquals(10, url.querySize)
        assertEquals("0", url.queryParameter("driveId"))
        assertEquals("100", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("next"))
        assertEquals("file_id", url.queryParameter("orderBy"))
        assertEquals("desc", url.queryParameter("orderDirection"))
        assertEquals("0", url.queryParameter("parentFileId"))
        assertEquals("false", url.queryParameter("trashed"))
        assertEquals("", url.queryParameter("SearchData"))
        assertEquals("1", url.queryParameter("Page"))
        assertEquals("0", url.queryParameter("OnlyLookAbnormalFile"))
    }

    @Test
    fun getFileListStringifiesTrashedPageLimitAndParentFileId() = runTest {
        server.enqueue(fileListOk("""{"InfoList":[],"Total":0,"Next":"-1","Len":0,"IsFirst":false}"""))

        api().getFileList(parentFileId = 123456789012L, page = 2, limit = 50, trashed = true)

        val url = requireNotNull(server.takeRequest().requestUrl)
        assertEquals("50", url.queryParameter("limit"))
        assertEquals("123456789012", url.queryParameter("parentFileId"))
        assertEquals("true", url.queryParameter("trashed"))
        assertEquals("2", url.queryParameter("Page"))
    }

    @Test
    fun getFileListParsesPascalCaseItemsWithAllThirteenFields() = runTest {
        server.enqueue(
            fileListOk(
                """
                {"InfoList":[
                  {"FileId":1001,"ParentFileId":0,"FileName":"文档","Type":1,"Size":0,
                   "Hidden":false,"Etag":"","S3KeyFlag":"","ContentType":"","PinYin":"wendang",
                   "StarredStatus":true,"CreateAt":1700000000,"UpdateAt":"1750000000"},
                  {"FileId":1002,"ParentFileId":1001,"FileName":"report.pdf","Type":0,"Size":2048,
                   "Hidden":1,"Etag":"etag-1","S3KeyFlag":"s3k","ContentType":"application/pdf","PinYin":"",
                   "StarredStatus":0,"CreateAt":"2020-01-02T03:04:05Z","UpdateAt":1700001234.5}
                ],"Total":2,"Next":"-1","Len":2,"IsFirst":true}
                """.trimIndent(),
            ),
        )

        val result = api().getFileList(parentFileId = 1001, page = 1)

        assertTrue(result is ApiResult.Success)
        val list = (result as ApiResult.Success<FileListDto>).data
        assertEquals(2, list.total)
        assertEquals("-1", list.next)
        assertEquals(2, list.len)
        assertTrue(list.isFirst)
        assertEquals(2, list.infoList.size)

        val folder = list.infoList[0]
        assertEquals(1001L, folder.fileId)
        assertEquals(0L, folder.parentFileId)
        assertEquals("文档", folder.fileName)
        assertTrue(folder.isFolder)
        assertEquals(0L, folder.size)
        assertFalse(folder.hidden)
        assertEquals("", folder.etag)
        assertEquals("", folder.s3KeyFlag)
        assertEquals("", folder.contentType)
        assertEquals("wendang", folder.pinyin)
        assertTrue(folder.starred)
        assertEquals(1700000000000L, folder.createAt)
        assertEquals(1750000000000L, folder.updateAt)

        val file = list.infoList[1]
        assertEquals(1002L, file.fileId)
        assertEquals(1001L, file.parentFileId)
        assertEquals("report.pdf", file.fileName)
        assertFalse(file.isFolder)
        assertEquals(2048L, file.size)
        assertTrue(file.hidden)
        assertEquals("etag-1", file.etag)
        assertEquals("s3k", file.s3KeyFlag)
        assertEquals("application/pdf", file.contentType)
        assertEquals("", file.pinyin)
        assertFalse(file.starred)
        assertEquals(1577934245000L, file.createAt)
        assertEquals(1700001234500L, file.updateAt)
    }

    @Test
    fun getFileListFallsBackToCamelCaseKeys() = runTest {
        server.enqueue(
            fileListOk(
                """
                {"infoList":[
                  {"fileId":7,"parentFileId":3,"fileName":"a.txt","type":0,"size":11,
                   "hidden":true,"etag":"e2","s3keyFlag":"k2","contentType":"text/plain",
                   "pinYin":"a","starredStatus":false,"createAt":1600000000,"updateAt":"1600000099"}
                ],"total":1,"next":"9","len":1,"isFirst":false}
                """.trimIndent(),
            ),
        )

        val result = api().getFileList(parentFileId = 3, page = 1)

        assertTrue(result is ApiResult.Success)
        val list = (result as ApiResult.Success<FileListDto>).data
        assertEquals(1, list.total)
        assertEquals("9", list.next)
        assertEquals(1, list.len)
        assertFalse(list.isFirst)
        val file = list.infoList.single()
        assertEquals(7L, file.fileId)
        assertEquals(3L, file.parentFileId)
        assertEquals("a.txt", file.fileName)
        assertFalse(file.isFolder)
        assertEquals(11L, file.size)
        assertTrue(file.hidden)
        assertEquals("e2", file.etag)
        assertEquals("k2", file.s3KeyFlag)
        assertEquals("text/plain", file.contentType)
        assertEquals("a", file.pinyin)
        assertFalse(file.starred)
        assertEquals(1600000000000L, file.createAt)
        assertEquals(1600000099000L, file.updateAt)
    }

    @Test
    fun getFileListNormalizesTimestampVariantsToEpochMillis() = runTest {
        server.enqueue(
            fileListOk(
                """
                {"InfoList":[
                  {"FileId":1,"CreateAt":1600000000,"UpdateAt":"1600000099"},
                  {"FileId":2,"CreateAt":"","UpdateAt":null},
                  {"FileId":3,"CreateAt":"2020-01-02T03:04:05Z"},
                  {"FileId":4,"CreateAt":"definitely-not-a-date"}
                ],"Total":4,"Next":"-1","Len":4,"IsFirst":true}
                """.trimIndent(),
            ),
        )

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.Success)
        val items = (result as ApiResult.Success<FileListDto>).data.infoList
        // unix 秒 int / 数字字符串
        assertEquals(1600000000000L, items[0].createAt)
        assertEquals(1600000099000L, items[0].updateAt)
        // 空串 / JSON null
        assertEquals(0L, items[1].createAt)
        assertEquals(0L, items[1].updateAt)
        // ISO8601 字符串；缺失键
        assertEquals(1577934245000L, items[2].createAt)
        assertEquals(0L, items[2].updateAt)
        // 含 "-" 但非法的 ISO8601 -> 0
        assertEquals(0L, items[3].createAt)
    }

    @Test
    fun getFileListTreatsRootAsDataWhenDataKeyMissing() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"Total":5,"Len":0,"IsFirst":false,"InfoList":[]}"""))

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.Success)
        val list = (result as ApiResult.Success<FileListDto>).data
        assertEquals(5, list.total)
        assertEquals(0, list.infoList.size)
        assertFalse(list.isFirst)
        // Next / next 两键均缺失时取参考源默认值 "-1"
        assertEquals("-1", list.next)
    }

    @Test
    fun bodyCodeTwoMapsToSessionExpired() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.SessionExpired)
    }

    @Test
    fun invalidJsonBodyReturnsParseError() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>Bad Gateway</body></html>"))

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.ParseError)
        assertEquals("服务器返回无效 JSON (HTTP 200)", (result as ApiResult.ParseError).message)
    }

    @Test
    fun nonObjectDataFieldReturnsParseError() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":0,"data":[]}"""))

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.ParseError)
    }

    @Test
    fun getFileListRetriesRateLimitOnceThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}"""))
        server.enqueue(fileListOk("""{"InfoList":[],"Total":0,"Next":"-1","Len":0,"IsFirst":true}"""))

        val result = api().getFileList(parentFileId = 0, page = 1)

        assertTrue(result is ApiResult.Success)
        assertEquals(2, server.requestCount)
        // 重试请求仍携带完整 query
        val retry = server.takeRequest()
        val url = requireNotNull(retry.requestUrl)
        assertEquals("/api/file/list/new", url.encodedPath)
        assertEquals("file_id", url.queryParameter("orderBy"))
        assertEquals("desc", url.queryParameter("orderDirection"))
    }
}
