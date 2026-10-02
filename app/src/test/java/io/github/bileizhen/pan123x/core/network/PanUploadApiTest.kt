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
 * PanUploadApi 协议回归测试（不触真实网络）。
 *
 * 本里程碑最容易出错的地方就是 **body 键名与大小写**，因此每个端点都逐字断言请求 body：
 * - `s3_list_upload_parts` / `s3_complete_multipart_upload` → 小写 `storageNode`；
 * - `s3_repare_upload_parts_batch`（**repare**）→ 大写 `StorageNode`；
 * - `upload_request` 七字段顺序与键名照抄参考源。
 *
 * 另外覆盖：`code==5060` → `conflict=true` 非失败；`Reuse=true` 的 `FileId` / `Info.FileId`
 * 双路径；非 0 code → ApiError 透传；`code==2` → SessionExpired；非幂等 POST 绝不自动重试。
 */
class PanUploadApiTest {

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

    // ------------------------------------------------------------------
    // upload_request
    // ------------------------------------------------------------------

    @Test
    fun requestUploadSendsSevenFieldBodyVerbatim() = runTest {
        server.enqueue(ok("""{"Reuse":false,"Bucket":"b1","StorageNode":"sn1","Key":"k1","UploadId":"u1","FileId":5}"""))

        val result = api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "d41d8cd98f00b204e9800998ecf8427e",
            parentFileId = 7, duplicate = 0,
        )

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/file/upload_request", request.path)
        // 七字段、顺序、键名与参考源 upload_service.py:297-305 逐字一致
        assertEquals(
            """{"driveId":0,"etag":"d41d8cd98f00b204e9800998ecf8427e","fileName":"a.bin","parentFileId":7,"size":1024,"type":0,"duplicate":0}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun requestUploadConflictCode5060IsNotAFailure() = runTest {
        // 5060 是同名冲突：必须 Success + conflict=true，否则上层会误判为上传失败
        server.enqueue(MockResponse().setBody("""{"code":5060,"message":"存在同名文件","data":{}}"""))

        val result = api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 0,
        )

        assertTrue(result is ApiResult.Success)
        val dto = (result as ApiResult.Success<UploadRequestDto>).data
        assertTrue(dto.conflict)
        assertFalse(dto.reuse)
        assertEquals("存在同名文件", dto.serverMessage)
    }

    @Test
    fun requestUploadResendCarriesDuplicateChoice() = runTest {
        // 冲突经用户决策后带 duplicate=2 重发（覆盖）
        server.enqueue(ok("""{"Bucket":"b1","StorageNode":"sn1","Key":"k1","UploadId":"u1","FileId":5}"""))

        api().requestUpload(fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 2)

        assertEquals(
            """{"driveId":0,"etag":"e","fileName":"a.bin","parentFileId":7,"size":1024,"type":0,"duplicate":2}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun requestUploadParsesReuseWithFileId() = runTest {
        server.enqueue(ok("""{"Reuse":true,"FileId":99}"""))

        val dto = (api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 0,
        ) as ApiResult.Success<UploadRequestDto>).data

        assertTrue(dto.reuse)
        assertFalse(dto.conflict)
        assertEquals(99L, dto.fileId)
    }

    @Test
    fun requestUploadParsesReuseFileIdFromInfoFallback() = runTest {
        // 参考源：FileId 为假值时退到 data.Info.FileId
        server.enqueue(ok("""{"Reuse":true,"Info":{"FileId":77}}"""))

        val dto = (api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 0,
        ) as ApiResult.Success<UploadRequestDto>).data

        assertTrue(dto.reuse)
        assertEquals(77L, dto.fileId)
    }

    @Test
    fun requestUploadParsesS3SessionFields() = runTest {
        server.enqueue(ok("""{"Reuse":false,"Bucket":"b1","StorageNode":"sn1","Key":"k1","UploadId":"u1","FileId":5}"""))

        val dto = (api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 0,
        ) as ApiResult.Success<UploadRequestDto>).data

        assertFalse(dto.reuse)
        assertEquals("b1", dto.bucket)
        assertEquals("sn1", dto.storageNode)
        assertEquals("k1", dto.key)
        assertEquals("u1", dto.uploadId)
        assertEquals(5L, dto.fileId)
    }

    @Test
    fun otherNonZeroCodePassesThroughAsApiError() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":5000,"message":"云盘空间不足"}"""))

        val result = api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 0,
        )

        assertTrue(result is ApiResult.ApiError)
        assertEquals(5000, (result as ApiResult.ApiError).code)
        assertEquals("云盘空间不足", result.message)
    }

    @Test
    fun bodyCodeTwoMapsToSessionExpired() = runTest {
        server.enqueue(MockResponse().setBody("""{"code":2,"message":"登录已过期"}"""))

        val result = api().requestUpload(
            fileName = "a.bin", size = 1024, etag = "e", parentFileId = 7, duplicate = 0,
        )

        assertTrue(result is ApiResult.SessionExpired)
    }

    // ------------------------------------------------------------------
    // s3_list_upload_parts —— 小写 storageNode
    // ------------------------------------------------------------------

    @Test
    fun listUploadedPartsSendsLowercaseStorageNodeAndParsesSortedInts() = runTest {
        server.enqueue(ok("""{"parts":[{"PartNumber":2},{"PartNumber":1},{"PartNumber":"3"},{"PartNumber":"x"}]}"""))

        val result = api().listUploadedParts(bucket = "b", key = "k", uploadId = "u", storageNode = "sn")

        val parts = (result as ApiResult.Success<List<Int>>).data
        assertEquals(listOf(1, 2, 3), parts)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/file/s3_list_upload_parts", request.path)
        // 小写 storageNode（与预签名端点的大写 StorageNode 相反）
        assertEquals("""{"bucket":"b","key":"k","uploadId":"u","storageNode":"sn"}""", request.body.readUtf8())
    }

    // ------------------------------------------------------------------
    // s3_repare_upload_parts_batch —— 大写 StorageNode + repare 拼写
    // ------------------------------------------------------------------

    @Test
    fun presignPartsSendsUppercaseStorageNodeOnRepareEndpoint() = runTest {
        server.enqueue(ok("""{"presignedUrls":{"1":"https://s3.example.com/1","2":"https://s3.example.com/2","abc":"https://s3.example.com/x","3":null}}"""))

        val result = api().presignParts(
            bucket = "b", key = "k", uploadId = "u", storageNode = "sn",
            partNumberStart = 1, partNumberEnd = 9,
        )

        val urls = (result as ApiResult.Success<Map<Int, String>>).data
        // 非数字键与 null 值跳过
        assertEquals(mapOf(1 to "https://s3.example.com/1", 2 to "https://s3.example.com/2"), urls)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        // 端点名拼写是 repare（不是 prepare）
        assertEquals("/b/api/file/s3_repare_upload_parts_batch", request.path)
        // 大写 StorageNode，且 partNumberEnd 在 partNumberStart 之前
        assertEquals(
            """{"bucket":"b","key":"k","partNumberEnd":9,"partNumberStart":1,"uploadId":"u","StorageNode":"sn"}""",
            request.body.readUtf8(),
        )
    }

    // ------------------------------------------------------------------
    // s3_complete_multipart_upload / upload_complete
    // ------------------------------------------------------------------

    @Test
    fun completeMultipartUploadSendsLowercaseStorageNode() = runTest {
        server.enqueue(ok("{}"))

        val result = api().completeMultipartUpload(bucket = "b", key = "k", uploadId = "u", storageNode = "sn")

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/file/s3_complete_multipart_upload", request.path)
        assertEquals("""{"bucket":"b","key":"k","uploadId":"u","storageNode":"sn"}""", request.body.readUtf8())
    }

    @Test
    fun finishUploadSendsFileIdOnly() = runTest {
        server.enqueue(ok("{}"))

        val result = api().finishUpload(fileId = 123)

        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/b/api/file/upload_complete", request.path)
        assertEquals("""{"fileId":123}""", request.body.readUtf8())
    }

    @Test
    fun uploadPostIsNeverRetriedAutomatically() = runTest {
        // 非幂等 POST 绝不自动重试：429 也只发一次
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":429,"message":"请求过于频繁"}"""))

        val result = api().finishUpload(fileId = 1)

        assertTrue(result is ApiResult.ApiError)
        assertEquals(1, server.requestCount)
    }
}
