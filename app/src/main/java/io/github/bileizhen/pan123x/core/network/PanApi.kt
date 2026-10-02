package io.github.bileizhen.pan123x.core.network

import io.github.bileizhen.pan123x.core.account.DeviceIdentity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 认证相关 API，供 AuthRepository 消费；测试可用 MockWebServer 或替身实现。 */
interface PanAuthApi {

    /** 登录成功返回 authorization 值（"Bearer " + token，含空格）。 */
    suspend fun login(passport: String, password: String): ApiResult<String>

    /** A login candidate can be verified without replacing the active account's credentials. */
    suspend fun getUserInfo(authorization: String? = null): ApiResult<UserInfoDto>
}

/** 文件列表 API，供 FileRepository 消费；测试可用 MockWebServer 或替身实现。 */
interface PanFileApi {

    /**
     * 拉取指定目录的一页文件列表（`GET /api/file/list/new`）。
     * parentFileId=0 为根目录；page 从 1 开始；服务端恒按 file_id desc 返回
     * （排序 / 搜索由客户端完成），next 为分页游标（"-1" 表示无更多）。
     */
    suspend fun getFileList(
        parentFileId: Long,
        page: Int,
        limit: Int = 100,
        trashed: Boolean = false,
    ): ApiResult<FileListDto>
}

/**
 * 文件操作 API（M3），供 FileOpsRepository 消费；测试可用 MockWebServer
 * 或替身实现。全部操作 body code==0 成功；均为非幂等 POST（复制轮询为幂等 GET），
 * 协议层不自动重试，请求级读超时 10s 对应参考源 session_file.py。
 */
interface PanFileOpsApi {

    /** 创建文件夹（`POST /a/api/file/upload_request`），成功返回新目录 id（`data.Info.FileId`）。 */
    suspend fun createFolder(parentFileId: Long, folderName: String): ApiResult<Long>

    /** 删除 / 恢复单个文件（`POST /a/api/file/trash`），[restore]=false 删除、true 恢复。 */
    suspend fun trashFile(fileId: Long, restore: Boolean): ApiResult<TrashData>

    /** 回收站永久删除（`POST /b/api/file/delete`），整列表一次提交。 */
    suspend fun deleteForever(fileIds: List<Long>): ApiResult<Unit>

    /** 重命名（`POST /a/api/file/rename`）。 */
    suspend fun renameFile(fileId: Long, newFileName: String): ApiResult<Unit>

    /** 移动到目标目录（`POST /b/api/file/mod_pid`），整列表一次提交。 */
    suspend fun moveFiles(fileIds: List<Long>, targetParentId: Long): ApiResult<Unit>

    /**
     * 发起异步复制任务（`POST /b/api/restful/goapi/v1/file/copy/async`）。
     * [fileList] 为调用方构造的完整文件对象（PascalCase）或降级 `{"FileId":x}` 的透传列表；
     * taskId 双大小写键均可解析，两键均缺失时 [CopySubmitData.taskId] 为 null。
     */
    suspend fun submitCopy(fileList: List<JsonObject>, targetFileId: Long): ApiResult<CopySubmitData>

    /** 查询复制任务状态（`GET .../copy/task?taskId=`），status 缺失时 [CopyTaskData.status] 为 null。 */
    suspend fun pollCopyTask(taskId: String): ApiResult<CopyTaskData>
}

/**
 * OkHttp 客户端工厂（API 与传输双客户端）。
 *
 * PanApiClient：面向 123pan API（登录/文件/分享/离线/上传协议），连接 3s / 读取 5s，
 * 携带设备伪装头与认证头。
 *
 * TransferClient：CDN 下载 / S3 上传 / 视频流专用，禁止携带 authorization / loginuuid 等
 * API 头，并需要更大的连接池。
 */
object PanHttpClientFactory {

    /** 无注入时进程内仅生成一枚默认指纹，避免每个请求换 loginuuid。 */
    private val defaultDevice: DeviceProfile by lazy { DeviceProfile.generate() }

    fun defaultClient(
        device: () -> DeviceProfile = { defaultDevice },
        auth: AuthorizationProvider = AuthorizationProvider { null },
        simulation: () -> Boolean = { true },
        routing: NetworkRouting? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(DeviceInterceptor(device, simulation))
        .addInterceptor(AuthInterceptor(auth))
        .apply { routing?.apply(this) }
        .build()

    /**
     * 头像等 CDN 图片资源专用：干净的 OkHttpClient，不携带 123pan API 的
     * authorization / loginuuid / 设备头（签名 URL 已含鉴权参数）。
     */
    fun imageClient(routing: NetworkRouting? = null): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .apply { routing?.apply(this) }
        .build()

    /**
     * 传输专用客户端：CDN 下载 / S3 上传 / 视频流。
     * **不携带**任何 123pan API 头（authorization / loginuuid / 设备头）。
     *
     * 关闭自动重定向（followRedirects / followSslRedirects = false）：signed URL 常经多跳 CDN
     * 跳转，必须由 NSFX 客户端逐跳手动跟随——跨 origin 需要剥离 authorization / cookie / referer，
     * 并拒绝 HTTPS→HTTP 降级。这些安全判定无法插入 OkHttp 的自动跟随流程，
     * 因此把每一跳的控制权交还调用方。连接池与调度器放宽以支撑多分段并发。
     */
    fun transferClient(
        connectTimeoutSeconds: Int = 30,
        readTimeoutSeconds: Int = 30,
        routing: NetworkRouting? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(ConnectionPool(TRANSFER_MAX_IDLE_CONNECTIONS, TRANSFER_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = TRANSFER_MAX_REQUESTS
            maxRequestsPerHost = TRANSFER_MAX_REQUESTS_PER_HOST
        })
        .apply { routing?.apply(this) }
        .build()

    private const val TRANSFER_MAX_IDLE_CONNECTIONS = 32
    private const val TRANSFER_KEEP_ALIVE_MINUTES = 5L
    private const val TRANSFER_MAX_REQUESTS = 64
    private const val TRANSFER_MAX_REQUESTS_PER_HOST = 16
}

/**
 * 123 云盘 API 客户端：包络解析、主/备用 host 切换、幂等 GET 退避重试。
 *
 * 行为契约（对应参考源 `session.py`）：
 * - 每个请求经拦截器携带设备头；authorization 仅在 provider 返回非空时附加。
 * - 响应 body 可解析为 JSON 时以 body code 为准；code==2 → SessionExpired。
 * - 连接层 IOException 且请求主 host 的 /api/ 路径 → 换备用 host 重发一次，
 *   得到任何 HTTP 应答即进程内粘滞备用线路。
 * - 仅幂等 GET 对 429/5xx 做最多 2 次指数退避重试；非幂等 POST 绝不自动重试。
 * - 个别慢端点可在 CallSpec 上覆盖读超时（文件列表 30s，对应参考源 session_file.py
 *   timeout=30）：经 newBuilder 派生的 client 共享连接池与拦截器，仅替换读超时，
 *   不改变其他端点行为。
 * - 分享三端点（M6）固定走 [shareBaseUrl]（参考源 share_service.py:22 SHARE_API_BASE），
 *   不参与主 / 备线路切换。
 * - 协程取消（CancellationException 不是 IOException）直接向上传播，不会被吞成 NetworkError。
 */
class PanApi(
    client: OkHttpClient = PanHttpClientFactory.defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    primaryBaseUrl: String = ApiHosts.BASE_URL,
    private val fallbackBaseUrl: String? = ApiHosts.FALLBACK_BASE_URL,
    private val logger: AppLogger? = null,
    /** 分享三端点的固定 base；测试注入 MockWebServer 地址。 */
    shareBaseUrl: String = ApiHosts.SHARE_BASE_URL,
) : PanAuthApi, PanDeviceApi, PanFileApi, PanFileOpsApi, PanDownloadApi, PanUploadApi, PanShareApi, PanOfflineApi {

    private val http = client
    private val primaryBaseUrl: String = primaryBaseUrl.trimEnd('/')
    /** 分享端点固定 base：与主 base 同样去尾斜杠，避免与 path 拼出双斜杠。 */
    private val shareBaseUrl: String = shareBaseUrl.trimEnd('/')
    private val primaryHost: String = runCatching { URI(this.primaryBaseUrl).host }.getOrNull() ?: ""
    private val random = Random.Default

    /** 读超时覆盖端点的派生 client 缓存（见类注释），并发安全、按超时秒数复用。 */
    private val readTimeoutClients = ConcurrentHashMap<Int, OkHttpClient>()

    /** 备用线路粘滞标记：得到 HTTP 应答后置位，进程内后续请求直达备用线路。 */
    @Volatile
    private var stickyFallback = false

    override suspend fun login(passport: String, password: String): ApiResult<String> {
        val body = buildJsonObject {
            put("type", 1)
            put("passport", passport)
            put("password", password)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = LOGIN_PATH,
            body = body,
            successCodes = setOf(LOGIN_SUCCESS_CODE),
            retryOnServerError = false,
        )
        return execute(spec) { root, httpCode ->
            val data = root["data"] as? JsonObject
                ?: return@execute ApiResult.ParseError("登录响应缺少 data 字段 (HTTP $httpCode)")
            val token = (data["token"] as? JsonPrimitive)?.content.orEmpty()
            if (token.isBlank()) {
                ApiResult.ParseError("登录响应缺少有效 token (HTTP $httpCode)")
            } else {
                ApiResult.Success("Bearer $token")
            }
        }
    }

    override suspend fun getUserInfo(authorization: String?): ApiResult<UserInfoDto> {
        val spec = CallSpec(
            method = "GET",
            path = USER_INFO_PATH,
            body = null,
            successCodes = setOf(USER_INFO_SUCCESS_CODE),
            retryOnServerError = true,
            authorization = authorization,
        )
        return execute(spec) { root, httpCode ->
            val info = UserInfoDto.fromJsonElement(root)
                ?: return@execute ApiResult.ParseError("用户信息响应缺少 data 字段 (HTTP $httpCode)")
            ApiResult.Success(info)
        }
    }

    /** session.py get_device_list: exact query fields and common device/auth headers. */
    override suspend fun getLoginDevices(): ApiResult<List<LoginDeviceDto>> = execute(
        CallSpec(
            method = "GET", path = "/b/api/user/device_list", body = null,
            query = linkedMapOf("operateType" to "2", "event" to "deviceManagement"),
            successCodes = setOf(0), retryOnServerError = true,
        ),
    ) { root, _ ->
        LoginDeviceDto.listFromJson(root)?.let { ApiResult.Success(it) }
            ?: ApiResult.ParseError("登录设备响应格式异常")
    }

    override suspend fun getFileList(
        parentFileId: Long,
        page: Int,
        limit: Int,
        trashed: Boolean,
    ): ApiResult<FileListDto> {
        // query 十参数与参考源 session_file.py 逐字一致；大小写混用是服务端要求，禁止"规范化"
        val query = linkedMapOf(
            "driveId" to "0",
            "limit" to limit.toString(),
            "next" to "0",
            "orderBy" to "file_id",
            "orderDirection" to "desc",
            "parentFileId" to parentFileId.toString(),
            "trashed" to if (trashed) "true" else "false",
            "SearchData" to "",
            "Page" to page.toString(),
            "OnlyLookAbnormalFile" to "0",
        )
        val spec = CallSpec(
            method = "GET",
            path = FILE_LIST_PATH,
            body = null,
            successCodes = setOf(FILE_LIST_SUCCESS_CODE),
            retryOnServerError = true,
            query = query,
            readTimeoutSeconds = FILE_LIST_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, httpCode ->
            val list = FileListDto.fromJsonElement(root)
                ?: return@execute ApiResult.ParseError("文件列表响应缺少 data 字段 (HTTP $httpCode)")
            ApiResult.Success(list)
        }
    }

    override suspend fun createFolder(parentFileId: Long, folderName: String): ApiResult<Long> {
        // 十字段 body 与参考源 create_dir（session_file.py）逐字一致；键名大小写混用是服务端要求
        val body = buildJsonObject {
            put("driveId", 0)
            put("etag", "")
            put("fileName", folderName)
            put("parentFileId", parentFileId)
            put("size", 0)
            put("type", 1)
            put("duplicate", 1)
            put("NotReuse", true)
            put("event", "newCreateFolder")
            put("operateType", 1)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = CREATE_FOLDER_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, httpCode ->
            val info = (root["data"] as? JsonObject)?.get("Info") as? JsonObject
                ?: return@execute ApiResult.ParseError("创建文件夹响应缺少 Info (HTTP $httpCode)")
            ApiResult.Success(info.optLong("FileId", "fileId"))
        }
    }

    override suspend fun trashFile(fileId: Long, restore: Boolean): ApiResult<TrashData> {
        // 服务器仅接受 fileTrashInfoList 为 [{"FileId":X}]（内层大写、只含 FileId）：
        // 传完整文件对象时返回 code=0 但静默忽略（session_file.py trash_file 注释）
        val body = buildJsonObject {
            put("driveId", 0)
            put("fileTrashInfoList", buildJsonArray {
                add(buildJsonObject { put("FileId", fileId) })
            })
            put("operation", !restore)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = TRASH_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, _ -> ApiResult.Success(TrashData.fromJsonElement(root)) }
    }

    override suspend fun deleteForever(fileIds: List<Long>): ApiResult<Unit> {
        // 内层键小写 fileId，与 trash 的内层大写 FileId 相反；RequestSource 固定 null
        val body = buildJsonObject {
            put("fileIdList", buildJsonArray {
                fileIds.forEach { add(buildJsonObject { put("fileId", it) }) }
            })
            put("event", "recycleDelete")
            put("operatePlace", 1)
            put("RequestSource", JsonNull)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = DELETE_FOREVER_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { _, _ -> ApiResult.Success(Unit) }
    }

    override suspend fun renameFile(fileId: Long, newFileName: String): ApiResult<Unit> {
        val body = buildJsonObject {
            put("driveId", 0)
            put("fileId", fileId)
            put("fileName", newFileName)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = RENAME_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { _, _ -> ApiResult.Success(Unit) }
    }

    override suspend fun moveFiles(fileIds: List<Long>, targetParentId: Long): ApiResult<Unit> {
        // 内层键大写 FileId，目标目录字段为小驼峰 parentFileId
        val body = buildJsonObject {
            put("fileIdList", buildJsonArray {
                fileIds.forEach { add(buildJsonObject { put("FileId", it) }) }
            })
            put("parentFileId", targetParentId)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = MOVE_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { _, _ -> ApiResult.Success(Unit) }
    }

    override suspend fun submitCopy(fileList: List<JsonObject>, targetFileId: Long): ApiResult<CopySubmitData> {
        val body = buildJsonObject {
            put("fileList", buildJsonArray { fileList.forEach { add(it) } })
            put("targetFileId", targetFileId)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = COPY_SUBMIT_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, httpCode ->
            val data = CopySubmitData.fromJsonElement(root)
                ?: return@execute ApiResult.ParseError("复制任务响应格式异常 (HTTP $httpCode)")
            ApiResult.Success(data)
        }
    }

    override suspend fun pollCopyTask(taskId: String): ApiResult<CopyTaskData> {
        // 参考源 copy_file_task 的 GET 同样 timeout=10，故不走默认 5s 读超时
        val spec = CallSpec(
            method = "GET",
            path = COPY_TASK_PATH,
            body = null,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = true,
            query = linkedMapOf("taskId" to taskId),
            readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, httpCode ->
            val data = CopyTaskData.fromJsonElement(root)
                ?: return@execute ApiResult.ParseError("复制任务状态响应格式异常 (HTTP $httpCode)")
            ApiResult.Success(data)
        }
    }

    override suspend fun getDownloadLink(
        fileId: Long,
        fileName: String,
        size: Long,
        etag: String,
        s3KeyFlag: String,
        isFolder: Boolean,
    ): ApiResult<DownloadLinkDto> {
        val spec = if (isFolder) {
            // 文件夹批量取链：内层键小写 fileId（与 trash 的内层大写 FileId 相反）
            CallSpec(
                method = "POST",
                path = BATCH_DOWNLOAD_INFO_PATH,
                body = buildJsonObject {
                    put("fileIdList", buildJsonArray { add(buildJsonObject { put("fileId", fileId) }) })
                }.toString(),
                successCodes = DOWNLOAD_SUCCESS_CODES,
                retryOnServerError = false,
                readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
            )
        } else {
            // 7 字段逐字对齐参考源 session_file.py#get_file_link；顺序即 body 序列化顺序
            CallSpec(
                method = "POST",
                path = DOWNLOAD_INFO_PATH,
                body = buildJsonObject {
                    put("driveId", 0)
                    put("etag", etag)
                    put("fileId", fileId)
                    put("s3keyFlag", s3KeyFlag)
                    put("type", 0)
                    put("fileName", fileName)
                    put("size", size)
                }.toString(),
                successCodes = DOWNLOAD_SUCCESS_CODES,
                retryOnServerError = false,
                readTimeoutSeconds = OPS_READ_TIMEOUT_SECONDS,
            )
        }
        return execute(spec) { root, _ -> ApiResult.Success(parseDownloadLink(root)) }
    }

    /**
     * 解析取链响应。5113/5114（下载流量超限）在 [DOWNLOAD_SUCCESS_CODES] 中，因此仍会走到这里，
     * 由本函数标记 trafficLimited=true（参考源记录警告后继续重写绕过，不视为失败）。
     *
     * 除参考源显式处理的 `data.RedirectUrl|redirect_url` 与 `data.DownloadUrl|downloadUrl` 外，
     * 额外容忍链接出现在 `data.InfoList[0]` / `data.infoList[0]`——这是防御性兜底，
     * 参考源 `get_file_link` 并未处理该形状。
     */
    private fun parseDownloadLink(root: JsonObject): DownloadLinkDto {
        val code = root.optLong("code", default = -1L).toInt()
        val trafficLimited = code == DOWNLOAD_LIMIT_CODE_5113 || code == DOWNLOAD_LIMIT_CODE_5114
        val data = root["data"] as? JsonObject
        val info = ((data?.get("InfoList") ?: data?.get("infoList")) as? JsonArray)
            ?.firstOrNull() as? JsonObject
        val directUrl = data?.optString("RedirectUrl", "redirect_url").orEmpty()
            .ifBlank { info?.optString("RedirectUrl", "redirect_url").orEmpty() }
        val rawUrl = data?.optString("DownloadUrl", "downloadUrl").orEmpty()
            .ifBlank { info?.optString("DownloadUrl", "downloadUrl").orEmpty() }
        if (directUrl.isBlank() && rawUrl.isBlank()) {
            // 取链是下载链路的第一个可能失败点：只记 data 的键名与 InfoList 形状，不记值
            // （URL 带签名，LogRedactor 也会再兜一层）。message 原文本可读性最好，一并记录。
            val detail = buildString {
                append("取链响应未包含链接：code=").append(code)
                append(", data 键=[")
                append(data?.keys?.joinToString(",").orEmpty().ifEmpty { "<无 data>" })
                append(']')
                info?.keys?.let { append(", InfoList[0] 键=[").append(it.joinToString(",")).append(']') }
                root.optString("message", "msg").takeIf { it.isNotBlank() }
                    ?.let { append(", message=").append(it) }
            }
            logger?.w(LogSource.API, detail)
        }
        return DownloadLinkDto(
            rawUrl = rawUrl,
            directUrl = directUrl,
            trafficLimited = trafficLimited,
            serverMessage = root.optString("message", "msg"),
        )
    }

    // ------------------------------------------------------------------
    // M5 上传端点
    //
    // 全部为**非幂等** POST：`retryOnServerError = false`，协议层绝不自动重试。
    // body 键名与大小写逐字对齐参考源 upload_service.py，**禁止**"顺手统一"。
    // ------------------------------------------------------------------

    override suspend fun requestUpload(
        fileName: String,
        size: Long,
        etag: String,
        parentFileId: Long,
        duplicate: Int,
    ): ApiResult<UploadRequestDto> {
        // 七字段顺序即序列化顺序，逐字对齐参考源 upload_service.py:297-305
        val body = buildJsonObject {
            put("driveId", 0)
            put("etag", etag)
            put("fileName", fileName)
            put("parentFileId", parentFileId)
            put("size", size)
            put("type", 0)
            put("duplicate", duplicate)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = UPLOAD_REQUEST_PATH,
            body = body,
            // 5060 是同名冲突而非失败：必须与 0 一起放进成功码，否则会被误判为 ApiError
            successCodes = UPLOAD_REQUEST_SUCCESS_CODES,
            retryOnServerError = false,
            readTimeoutSeconds = UPLOAD_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, _ -> ApiResult.Success(parseUploadRequest(root)) }
    }

    override suspend fun listUploadedParts(
        bucket: String,
        key: String,
        uploadId: String,
        storageNode: String,
    ): ApiResult<List<Int>> {
        // 键名 storageNode 为**小写 s**（参考源 upload_service.py:365-370），与大写 StorageNode 的预签名端点不同
        val body = buildJsonObject {
            put("bucket", bucket)
            put("key", key)
            put("uploadId", uploadId)
            put("storageNode", storageNode)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = S3_LIST_UPLOAD_PARTS_PATH,
            body = body,
            successCodes = setOf(UPLOAD_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = UPLOAD_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, _ -> ApiResult.Success(parseUploadedParts(root)) }
    }

    override suspend fun presignParts(
        bucket: String,
        key: String,
        uploadId: String,
        storageNode: String,
        partNumberStart: Int,
        partNumberEnd: Int,
    ): ApiResult<Map<Int, String>> {
        // 本端点 body 的 StorageNode 是**大写 S**（参考源 upload_service.py:149-156）；
        // 端点名拼写就是 repare（不是 prepare）；partNumberEnd 在 partNumberStart 之前，顺序照抄
        val body = buildJsonObject {
            put("bucket", bucket)
            put("key", key)
            put("partNumberEnd", partNumberEnd)
            put("partNumberStart", partNumberStart)
            put("uploadId", uploadId)
            put("StorageNode", storageNode)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = S3_PRESIGN_BATCH_PATH,
            body = body,
            successCodes = setOf(UPLOAD_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = UPLOAD_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { root, _ -> ApiResult.Success(parsePresignedUrls(root)) }
    }

    override suspend fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        storageNode: String,
    ): ApiResult<Unit> {
        // 键名 storageNode 为**小写 s**（参考源 upload_service.py:547-552）
        val body = buildJsonObject {
            put("bucket", bucket)
            put("key", key)
            put("uploadId", uploadId)
            put("storageNode", storageNode)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = S3_COMPLETE_MULTIPART_PATH,
            body = body,
            successCodes = setOf(UPLOAD_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = UPLOAD_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { _, _ -> ApiResult.Success(Unit) }
    }

    override suspend fun finishUpload(fileId: Long): ApiResult<Unit> {
        val body = buildJsonObject { put("fileId", fileId) }.toString()
        val spec = CallSpec(
            method = "POST",
            path = UPLOAD_COMPLETE_PATH,
            body = body,
            successCodes = setOf(UPLOAD_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = UPLOAD_READ_TIMEOUT_SECONDS,
        )
        return execute(spec) { _, _ -> ApiResult.Success(Unit) }
    }

    // ------------------------------------------------------------------
    // M6 分享端点
    //
    // 三个端点的 canonical host 都是 shareBaseUrl（参考源 share_service.py:22
    // SHARE_API_BASE = FALLBACK_BASE_URL），不走主 / 备线路切换：参考源本身直连该域，
    // 其下再无 fallback。全部读超时 10s（参考源三处调用均 timeout=10）。
    // create / delete 为非幂等 POST：retryOnServerError = false，协议层绝不自动重试
    // ；list 为幂等 GET，可退避重试。包络解析（含 code==2 → SessionExpired）
    // 复用 execute 同一套 parseResponse，不重复实现。
    // ------------------------------------------------------------------

    override suspend fun createShare(fileIds: List<Long>, options: io.github.bileizhen.pan123x.core.share.ShareCreateOptions): ApiResult<CreateShareDto> {
        // 十四字段顺序即序列化顺序，逐字对齐参考源 file_service.py:391-406；
        // fileIdList 是逗号连接的**字符串**（不是数组），fileNum = 归一化后的文件数
        val body = buildJsonObject {
            put("driveId", 0)
            put("expiration", options.expirationAt(java.time.Instant.now()))
            put("fileIdList", fileIds.joinToString(","))
            put("shareName", options.name.trim())
            put("sharePwd", options.password)
            put("event", "shareCreate")
            put("fileNum", fileIds.size)
            put("renameVisible", false)
            put("shareModality", SHARE_MODALITY)
            put("operatePlace", 2)
            put("trafficLimitSwitch", 1)
            put("trafficLimit", 0)
            put("trafficSwitch", 1)
            put("fillPwdSwitch", 0)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = SHARE_CREATE_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = SHARE_READ_TIMEOUT_SECONDS,
        )
        return executeOnShareHost(spec) { root, httpCode ->
            // 参考源 file_service.py:415 直接下标取 data.ShareKey，仅此一个键名；缺失转 ParseError
            val dto = (root["data"] as? JsonObject)?.let(CreateShareDto::fromData)
                ?: return@executeOnShareHost ApiResult.ParseError("创建分享响应缺少 ShareKey (HTTP $httpCode)")
            ApiResult.Success(dto)
        }
    }

    override suspend fun listShares(limit: Int, next: String, search: String): ApiResult<SharePageDto> {
        // 无更多游标直接短路（接口契约），不发网络请求
        if (next == SHARE_NEXT_NO_MORE) {
            return ApiResult.Success(SharePageDto(next = SHARE_NEXT_NO_MORE, total = 0, items = emptyList()))
        }
        // 八个 query 参数与参考源 share_service.py:43-52 逐字一致；SearchData 大写 S 是服务端要求
        val query = linkedMapOf(
            "driveId" to "0",
            "limit" to limit.toString(),
            "next" to next,
            "orderBy" to "fileId",
            "orderDirection" to "desc",
            "SearchData" to search,
            "event" to "shareListFile",
            "operateType" to "1",
        )
        val spec = CallSpec(
            method = "GET",
            path = SHARE_LIST_PATH,
            body = null,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = true,
            query = query,
            readTimeoutSeconds = SHARE_READ_TIMEOUT_SECONDS,
        )
        return executeOnShareHost(spec) { root, _ -> ApiResult.Success(SharePageDto.fromJsonElement(root)) }
    }

    override suspend fun deleteShare(shareId: Long): ApiResult<Unit> {
        // 五字段顺序与内层小写 shareId 逐字对齐参考源 share_service.py:135-141
        // （与 trash 的内层大写 FileId 相反，禁止"顺手统一"）
        val body = buildJsonObject {
            put("driveId", 0)
            put("shareInfoList", buildJsonArray { add(buildJsonObject { put("shareId", shareId) }) })
            put("isPayShare", 0)
            put("event", "shareCancel")
            put("operatePlace", 2)
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = SHARE_DELETE_PATH,
            body = body,
            successCodes = setOf(OPS_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = SHARE_READ_TIMEOUT_SECONDS,
        )
        return executeOnShareHost(spec) { _, _ -> ApiResult.Success(Unit) }
    }

    /**
     * 分享端点专用执行：base 固定为 [shareBaseUrl]，无主 / 备切换与粘滞逻辑。
     * 重试循环、退避、包络解析（parseResponse：code==2 → SessionExpired、ApiError 透传、
     * 诊断日志）与 [execute] 完全同源，仅 base 不同——execute 绑定主备线路无法按调用点
     * 换 base，故单独成方法而非复制解析逻辑。
     */
    private suspend fun <T> executeOnShareHost(
        spec: CallSpec,
        parse: (root: JsonObject, httpCode: Int) -> ApiResult<T>,
    ): ApiResult<T> {
        val maxAttempts = if (spec.retryOnServerError) MAX_ATTEMPTS else 1
        var attemptIndex = 0
        while (true) {
            val request = buildRequest(shareBaseUrl, spec)
            val raw = try {
                withContext(Dispatchers.IO) {
                    clientFor(spec).newCall(request).execute().use { response ->
                        logger?.d(LogSource.API, "${request.method} ${spec.path} HTTP ${response.code}")
                        RawHttpResult(response.code, response.body?.string())
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                return ApiResult.NetworkError(NETWORK_FAILURE)
            }
            val attempt = parseResponse(raw, spec, parse)
            if (attemptIndex < maxAttempts - 1 && isRetryable(attempt)) {
                currentCoroutineContext().ensureActive()
                delay(backoffMillis(attemptIndex))
                attemptIndex++
                continue
            }
            return attempt.result
        }
    }

    /**
     * 解析 upload_request 响应。调用方已保证 code ∈ {0, 5060}。
     *
     * 秒传与新建会话共用解析：`Reuse=true` 时 fileId 取 `data.FileId`，为 0/缺失再退到
     * `data.Info.FileId`（参考源 `up_file_id = data.get("FileId", 0)`，为假值再取 Info）。
     */
    private fun parseUploadRequest(root: JsonObject): UploadRequestDto {
        val code = root.optLong("code", default = -1L).toInt()
        val conflict = code == UPLOAD_CONFLICT_CODE
        val serverMessage = root.optString("message", "msg")
        val data = root["data"] as? JsonObject
            ?: return UploadRequestDto(conflict = conflict, serverMessage = serverMessage)
        val primaryFileId = data.optLong("FileId", "fileId")
        val infoFileId = (data["Info"] as? JsonObject)?.optLong("FileId", "fileId") ?: 0L
        return UploadRequestDto(
            conflict = conflict,
            reuse = data.optBoolean("Reuse", "reuse"),
            fileId = if (primaryFileId != 0L) primaryFileId else infoFileId,
            bucket = data.optString("Bucket", "bucket"),
            storageNode = data.optString("StorageNode", "storageNode"),
            key = data.optString("Key", "key"),
            uploadId = data.optString("UploadId", "uploadId"),
            serverMessage = serverMessage,
        )
    }

    /**
     * 解析 `data.parts[].PartNumber`：转整数失败的条目跳过（参考源 `int(PartNumber)` 容错）；
     * 结果按 1-based 升序去重（协议保证分片号 ≥ 1，0 与负数视为脏数据丢弃）。
     */
    private fun parseUploadedParts(root: JsonObject): List<Int> {
        val parts = (root["data"] as? JsonObject)?.get("parts") as? JsonArray ?: return emptyList()
        return parts
            .mapNotNull { (it as? JsonObject)?.optLongOrNull("PartNumber", "partNumber")?.toInt() }
            .filter { it >= 1 }
            .distinct()
            .sorted()
    }

    /**
     * 解析 `data.presignedUrls`：形如 `{"1": "https://...", "2": "..."}`，键是**字符串数字**。
     * 非数字键、空值、空白 URL 一律跳过（参考源 `_fetch_presigned_urls` 的 `int(k)` 容错），
     * 返回 `partNumber -> url`；不保证覆盖请求窗口，缺失由调用方兜底。
     */
    private fun parsePresignedUrls(root: JsonObject): Map<Int, String> {
        val presigned = (root["data"] as? JsonObject)?.get("presignedUrls") as? JsonObject
            ?: return emptyMap()
        val urls = LinkedHashMap<Int, String>()
        for ((rawKey, rawValue) in presigned) {
            val partNumber = rawKey.toIntOrNull() ?: continue
            if (rawValue is JsonNull) continue
            val url = (rawValue as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank()) continue
            urls[partNumber] = url
        }
        return urls
    }

    /** 依次尝试多个键，容错 true/false 与 0/1（服务端布尔字段两种形态都出现过）。 */
    private fun JsonObject.optBoolean(vararg keys: String): Boolean {
        for (key in keys) {
            val value = this[key] as? JsonPrimitive ?: continue
            value.booleanOrNull?.let { return it }
            value.longOrNull?.let { return it != 0L }
        }
        return false
    }

    /** 单次请求的静态描述。query 保持插入顺序追加到 URL；readTimeoutSeconds 为该次调用的读超时覆盖。 */
    private class CallSpec(
        val method: String,
        val path: String,
        val body: String?,
        val successCodes: Set<Int>,
        val retryOnServerError: Boolean,
        val query: Map<String, String>? = null,
        val readTimeoutSeconds: Int? = null,
        val authorization: String? = null,
    )

    /** 一次 HTTP 往返结果：HTTP 状态码与包络解析结果分开携带。 */
    private class Attempt<T>(val httpCode: Int, val result: ApiResult<T>)

    /** 已读完 body 的原始应答。 */
    private class RawHttpResult(val code: Int, val body: String?)

    private fun buildRequest(base: String, spec: CallSpec): Request {
        val builder = Request.Builder().url(buildUrl(base, spec))
        spec.authorization?.let { builder.header("authorization", it) }
        return if (spec.body != null) {
            // 参考源固定 application/json，不带 charset 后缀；String RequestBody 会自动补 charset，需走字节 body
            builder.post(spec.body.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType())).build()
        } else {
            builder.get().build()
        }
    }

    /** 无 query 时保持原样拼接；有 query 时经 HttpUrl 追加（键值编码 + 保持插入顺序）。 */
    private fun buildUrl(base: String, spec: CallSpec): String {
        val query = spec.query ?: return base + spec.path
        if (query.isEmpty()) return base + spec.path
        return (base + spec.path).toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build().toString()
    }

    /** 默认走注入的 client；覆盖读超时的端点走共享连接池的派生 client。 */
    private fun clientFor(spec: CallSpec): OkHttpClient {
        val seconds = spec.readTimeoutSeconds ?: return http
        return readTimeoutClients.getOrPut(seconds) {
            http.newBuilder().readTimeout(seconds.toLong(), TimeUnit.SECONDS).build()
        }
    }

    private suspend fun <T> execute(
        spec: CallSpec,
        parse: (root: JsonObject, httpCode: Int) -> ApiResult<T>,
    ): ApiResult<T> {
        val maxAttempts = if (spec.retryOnServerError) MAX_ATTEMPTS else 1
        var attemptIndex = 0
        while (true) {
            val attempt = executeOnce(spec, parse)
            if (attemptIndex < maxAttempts - 1 && isRetryable(attempt)) {
                currentCoroutineContext().ensureActive()
                delay(backoffMillis(attemptIndex))
                attemptIndex++
                continue
            }
            return attempt.result
        }
    }

    private suspend fun <T> executeOnce(
        spec: CallSpec,
        parse: (root: JsonObject, httpCode: Int) -> ApiResult<T>,
    ): Attempt<T> {
        var useFallback = stickyFallback && fallbackBaseUrl != null
        while (true) {
            val base = if (useFallback) fallbackBaseUrl.orEmpty().trimEnd('/') else primaryBaseUrl
            val request = buildRequest(base, spec)
            val raw = try {
                withContext(Dispatchers.IO) {
                    clientFor(spec).newCall(request).execute().use { response ->
                        RawHttpResult(response.code, response.body?.string())
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (!useFallback && canFallback(request, spec.path)) {
                    useFallback = true
                    continue
                }
                val message = if (useFallback) BOTH_LINES_UNREACHABLE else NETWORK_FAILURE
                return Attempt(-1, ApiResult.NetworkError(message))
            }
            // 走到备用线路并得到任何 HTTP 应答（含 429/错误页）即粘滞，与参考源一致
            if (useFallback) stickyFallback = true
            return parseResponse(raw, spec, parse)
        }
    }

    /** 备用线路仅对主 host 的 /api/ 路径、连接层失败且尚未粘滞时启用。 */
    private fun canFallback(request: Request, path: String): Boolean =
        fallbackBaseUrl != null &&
            !stickyFallback &&
            request.url.host == primaryHost &&
            path.contains("/api/")

    private fun <T> parseResponse(
        raw: RawHttpResult,
        spec: CallSpec,
        parse: (root: JsonObject, httpCode: Int) -> ApiResult<T>,
    ): Attempt<T> {
        val httpCode = raw.code
        val root = parseEnvelopeObject(raw.body)
            ?: return Attempt(httpCode, ApiResult.ParseError("服务器返回无效 JSON (HTTP $httpCode)"))
        val envelope = try {
            json.decodeFromJsonElement(ApiEnvelope.serializer(), root)
        } catch (error: IllegalArgumentException) {
            return Attempt(httpCode, ApiResult.ParseError("服务器返回无效 JSON (HTTP $httpCode)"))
        }
        val result = when {
            envelope.code == SESSION_EXPIRED_CODE -> ApiResult.SessionExpired
            envelope.code in spec.successCodes -> parse(root, httpCode)
            else -> ApiResult.ApiError(envelope.code, envelope.message ?: envelope.msg ?: "")
        }
        // 诊断埋点：协议行为只能对齐参考源，一旦服务端形状变化就必须能从应用内日志看出
        // 是哪条端点、哪个 code。AppLogger 会经 LogRedactor 隐藏 URL 与凭据字段。
        if (result is ApiResult.ApiError) {
            logger?.w(LogSource.API, "${spec.path} 返回 code=${result.code}：${result.message}")
        } else if (result is ApiResult.ParseError) {
            logger?.w(LogSource.API, "${spec.path} 响应无法解析：${result.message}")
        }
        return Attempt(httpCode, result)
    }

    /** 空响应 / 非 JSON / 非对象均按解析失败处理，返回 null。 */
    private fun parseEnvelopeObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    /** 仅 429/5xx 重试；body 可解析时以 body code 为准，否则以 HTTP 状态码为准。 */
    private fun isRetryable(attempt: Attempt<*>): Boolean = when (val result = attempt.result) {
        is ApiResult.ApiError -> result.code == TOO_MANY_REQUESTS_CODE || result.code >= 500
        is ApiResult.ParseError -> attempt.httpCode == TOO_MANY_REQUESTS_CODE || attempt.httpCode >= 500
        else -> false
    }

    /** 指数退避：基础 500ms 翻倍、上限 4s、±20% jitter。 */
    private fun backoffMillis(attemptIndex: Int): Long {
        val base = (RETRY_BASE_DELAY_MS shl attemptIndex).coerceAtMost(RETRY_MAX_DELAY_MS)
        val jitter = (random.nextDouble() * 2.0 - 1.0) * RETRY_JITTER_RATIO * base
        return (base + jitter).toLong().coerceAtLeast(1L)
    }

    private companion object {
        const val LOGIN_PATH = "/b/api/user/sign_in"
        const val USER_INFO_PATH = "/b/api/user/info"
        const val FILE_LIST_PATH = "/api/file/list/new"
        const val CREATE_FOLDER_PATH = "/a/api/file/upload_request"
        const val TRASH_PATH = "/a/api/file/trash"
        const val DELETE_FOREVER_PATH = "/b/api/file/delete"
        const val RENAME_PATH = "/a/api/file/rename"
        const val MOVE_PATH = "/b/api/file/mod_pid"
        const val COPY_SUBMIT_PATH = "/b/api/restful/goapi/v1/file/copy/async"
        const val COPY_TASK_PATH = "/b/api/restful/goapi/v1/file/copy/task"
        const val DOWNLOAD_INFO_PATH = "/a/api/file/download_info"
        const val BATCH_DOWNLOAD_INFO_PATH = "/a/api/file/batch_download_info"

        /** M5 上传端点路径。`repare` 拼写与大小写均照抄参考源。 */
        const val UPLOAD_REQUEST_PATH = "/b/api/file/upload_request"
        const val S3_LIST_UPLOAD_PARTS_PATH = "/b/api/file/s3_list_upload_parts"
        const val S3_PRESIGN_BATCH_PATH = "/b/api/file/s3_repare_upload_parts_batch"
        const val S3_COMPLETE_MULTIPART_PATH = "/b/api/file/s3_complete_multipart_upload"
        const val UPLOAD_COMPLETE_PATH = "/b/api/file/upload_complete"

        /** M6 分享端点路径（参考源 share_service.py / file_service.py）。 */
        const val SHARE_CREATE_PATH = "/b/api/share/create"
        const val SHARE_LIST_PATH = "/b/api/share/list"
        const val SHARE_DELETE_PATH = "/b/api/share/delete"

        /** 创建分享的固定字段值（参考源 file_service.py:393 / :395 / :400，逐字不改）。 */
        const val SHARE_EXPIRATION = "2099-12-12T08:00:00+08:00"
        const val SHARE_NAME = "123云盘分享"
        const val SHARE_MODALITY = 4

        const val LOGIN_SUCCESS_CODE = 200
        const val USER_INFO_SUCCESS_CODE = 0
        const val FILE_LIST_SUCCESS_CODE = 0

        /** M3 文件操作全部以 body code==0 为成功。 */
        const val OPS_SUCCESS_CODE = 0

        /**
         * M3 操作读超时：参考源 session_file.py 各操作（含复制轮询 GET）均为 timeout=10，
         * 不同于列表的 30s 与默认 5s。
         */
        const val OPS_READ_TIMEOUT_SECONDS = 10
        const val FILE_LIST_READ_TIMEOUT_SECONDS = 30

        /** 下载流量超限码（参考源 session_file.py `_DOWNLOAD_LIMIT_CODES`），不是失败。 */
        const val DOWNLOAD_LIMIT_CODE_5113 = 5113
        const val DOWNLOAD_LIMIT_CODE_5114 = 5114

        /** 取链成功码：0 正常；5113/5114 记为成功并标记 trafficLimited 以便上层重写绕过。 */
        val DOWNLOAD_SUCCESS_CODES = setOf(OPS_SUCCESS_CODE, DOWNLOAD_LIMIT_CODE_5113, DOWNLOAD_LIMIT_CODE_5114)

        /** 上传端点成功码：M5 全部上传端点以 body code==0 为成功。 */
        const val UPLOAD_SUCCESS_CODE = 0

        /** 5060 = 同名文件冲突（参考源 upload_service.py:313），不是失败，需交上层决策。 */
        const val UPLOAD_CONFLICT_CODE = 5060

        /** requestUpload 成功码含 5060：冲突与成功共用同一次解析，由 DTO 的 conflict 区分。 */
        val UPLOAD_REQUEST_SUCCESS_CODES = setOf(UPLOAD_SUCCESS_CODE, UPLOAD_CONFLICT_CODE)

        /** 上传元数据请求读超时 15s（参考源 `_UPLOAD_REQUEST_TIMEOUT`）。 */
        const val UPLOAD_READ_TIMEOUT_SECONDS = 15

        /** M6 分享三端点读超时 10s（参考源 share_service.py / file_service.py 各调用 timeout=10）。 */
        const val SHARE_READ_TIMEOUT_SECONDS = 10

        const val SESSION_EXPIRED_CODE = 2
        const val TOO_MANY_REQUESTS_CODE = 429
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 500L
        const val RETRY_MAX_DELAY_MS = 4_000L
        const val RETRY_JITTER_RATIO = 0.2
        const val NETWORK_FAILURE = "网络连接失败，请检查网络"
        const val BOTH_LINES_UNREACHABLE = "网络连接失败，主/备用线路均不可用"
    }

    // ------------------------------------------------------------------
    // ---- M7 离线下载（/）----
    //
    // 两个端点的 canonical host 都是 [offlineBaseUrl]（ApiHosts.OFFLINE_BASE_URL）：
    // 参考源 offline_service.py:66-71 / :88-92 经 api_url(path, OFFLINE_BASE_URL) 直连该域，
    // 不走主 / 备线路切换，因此不进 [execute] 而走本段专用的 [executeOnOfflineHost]
    // （与 executeOnShareHost 同源，仅 base 不同；按  归属约束本段只在文件尾部追加，
    // 不复用 / 不改动既有方法）。均为非幂等 POST：retryOnServerError = false，协议层
    // 绝不自动重试；读超时 30s（参考源两处调用均 timeout=30）。
    // 包络解析（code==2 → SessionExpired、ApiError 透传、诊断日志）复用 parseResponse。
    // ------------------------------------------------------------------

    /**
     * 离线端点固定 base。默认取 [ApiHosts.OFFLINE_BASE_URL]；var 仅为让单测
     * （PanOfflineApiTest， 不触真实网络）能指向 MockWebServer——与
     * shareBaseUrl 走构造参数不同，该字段仅供单测替换地址，生产接线不修改它。
     */
    internal var offlineBaseUrl: String = ApiHosts.OFFLINE_BASE_URL
        set(value) {
            field = value.trimEnd('/')
        }

    override suspend fun resolve(urls: String): ApiResult<List<OfflineResolvedItem>> {
        // body 仅一个键 urls（参考源 offline_service.py:68 `json={"urls": urls}`）
        val spec = CallSpec(
            method = "POST",
            path = OFFLINE_RESOLVE_PATH,
            body = buildJsonObject { put("urls", urls) }.toString(),
            successCodes = setOf(OFFLINE_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OFFLINE_READ_TIMEOUT_SECONDS,
        )
        return executeOnOfflineHost(spec) { root, _ ->
            ApiResult.Success(OfflineResolvedItem.listFromRoot(root))
        }
    }

    override suspend fun submit(resources: List<OfflineResource>): ApiResult<List<OfflineSubmittedTask>> {
        // body 形如 {"resource_list":[{"resource_id":X,"select_file_id":[...]}]}
        // （参考源 offline_service.py:80/:90；select_file_id 空数组 = 整个资源）
        val body = buildJsonObject {
            put("resource_list", buildJsonArray {
                resources.forEach { resource ->
                    add(buildJsonObject {
                        put("resource_id", resource.resourceId)
                        put("select_file_id", buildJsonArray {
                            resource.selectFileIds.forEach { add(it) }
                        })
                    })
                }
            })
        }.toString()
        val spec = CallSpec(
            method = "POST",
            path = OFFLINE_SUBMIT_PATH,
            body = body,
            successCodes = setOf(OFFLINE_SUCCESS_CODE),
            retryOnServerError = false,
            readTimeoutSeconds = OFFLINE_READ_TIMEOUT_SECONDS,
        )
        return executeOnOfflineHost(spec) { root, _ ->
            ApiResult.Success(OfflineSubmittedTask.listFromRoot(root))
        }
    }

    /**
     * 离线端点专用执行：base 固定为 [offlineBaseUrl]，无主 / 备切换与粘滞逻辑。
     * 重试循环、退避、包络解析与 [execute] 完全同源（executeOnShareHost 同款结构，
     * 仅绑定不同 base，因此使用独立方法）。
     */
    private suspend fun <T> executeOnOfflineHost(
        spec: CallSpec,
        parse: (root: JsonObject, httpCode: Int) -> ApiResult<T>,
    ): ApiResult<T> {
        val maxAttempts = if (spec.retryOnServerError) MAX_ATTEMPTS else 1
        var attemptIndex = 0
        while (true) {
            val request = buildRequest(offlineBaseUrl, spec)
            val raw = try {
                withContext(Dispatchers.IO) {
                    clientFor(spec).newCall(request).execute().use { response ->
                        RawHttpResult(response.code, response.body?.string())
                    }
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                return ApiResult.NetworkError(NETWORK_FAILURE)
            }
            val attempt = parseResponse(raw, spec, parse)
            if (attemptIndex < maxAttempts - 1 && isRetryable(attempt)) {
                currentCoroutineContext().ensureActive()
                delay(backoffMillis(attemptIndex))
                attemptIndex++
                continue
            }
            return attempt.result
        }
    }

    // 以下常量随 offline 段一并尾部追加（归属约束，不入上方 companion）。

    /** 离线解析端点（参考源 offline_service.py:21）。 */
    private val OFFLINE_RESOLVE_PATH = "/b/api/v2/offline_download/task/resolve"

    /** 离线提交端点（参考源 offline_service.py:22）。 */
    private val OFFLINE_SUBMIT_PATH = "/b/api/v2/offline_download/task/submit"

    /** 两端点 body code==0 为成功（参考源 offline_service.py:72 / :94）。 */
    private val OFFLINE_SUCCESS_CODE = 0

    /** 离线端点读超时 30s（参考源 offline_service.py:69 / :92 timeout=30）。 */
    private val OFFLINE_READ_TIMEOUT_SECONDS = 30
}

// ---- M7 QR 登录----
//
// 协议真源：`.reference/123pan/src/app/api/session.py:453-623`（_qr_headers / qr_generate /
// qr_poll）与 `tasks/qr_login_tasks.py:80-130`（确认后编排）。两个端点固定走 LOGIN_BASE_URL
// （参考源 urljoin(LOGIN_BASE_URL, ...) 直连：无主备 fallback、无重试），因此不进 PanApi 的
// 主备线路逻辑；又因 CallSpec/execute 是 PanApi 私有、本段按  限定为
// "文件尾部追加、不改既有类"，实现为独立 client 类而非 PanApi 的第 N 个接口。

/** `GET /api/user/qr-code/generate` 成功时的 data（session.py:499-509，键名 uniID/url 逐字）。 */
data class QrGenerateDto(val uniId: String, val url: String)

/**
 * `GET /api/user/qr-code/result` 归一化后的扫码状态（session.py:511-577）。
 * loginStatus：0=等待扫码 1=已扫码待确认 2=拒绝 3=确认登录 4=过期；scanPlatform：4=微信
 * 7=123云盘App（确认时取 `data.login_type`）；token 仅确认且服务端直发时非空。
 */
data class QrPollDto(val loginStatus: Int, val scanPlatform: Int, val token: String)

/** 扫码登录 API，供 AuthRepository 消费；测试可用 MockWebServer 或替身实现。 */
interface PanQrApi {

    /** 获取二维码登录会话（uniID + 二维码内容 url）。 */
    suspend fun qrGenerate(): ApiResult<QrGenerateDto>

    /** 轮询扫码状态；code==200（用户已确认）归一化为 loginStatus=3 + token（session.py:545-559）。 */
    suspend fun qrPoll(uniId: String): ApiResult<QrPollDto>
}

/**
 * 扫码登录协议实现（M7）。
 *
 * 请求头是"会话级 + per-request"的合并结果：参考源 requests.Session 先带
 * CLIENT_SIMULATION_HEADERS（constants.py:30-35）与 _build_headers 动态头（session.py:250-262），
 * _qr_headers（session.py:456-463）再以同名覆盖 platform/app-version/loginuuid/content-type，
 * 因此最终请求是 platform=web / app-version=3 覆盖 android / 61 后的合并集。这里逐字段显式携带
 * 同一结果；client 不带任何拦截器——DeviceInterceptor 会用 `.header` 强制覆盖 platform，
 * 复用 defaultClient 将无法发出 web 头（保持参考源已验证行为）。
 * loginuuid/osversion/devicetype 来自持久化设备身份（DeviceIdentityStore），与请求指纹一致。
 */
class PanQrApiClient(
    /** 持久化设备身份提供方；AppContainer 接线 `DeviceIdentityStore.loadOrCreate`。 */
    private val identity: suspend () -> DeviceIdentity,
    baseUrl: String = ApiHosts.LOGIN_BASE_URL,
    /** 干净客户端：无拦截器；(3, 10)s 超时对应参考源 timeout=(3, 10)（session.py:474/:529）。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(QR_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .readTimeout(QR_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val logger: AppLogger? = null,
) : PanQrApi {

    private val baseUrl: String = baseUrl.trimEnd('/')

    override suspend fun qrGenerate(): ApiResult<QrGenerateDto> =
        execute(path = QR_GENERATE_PATH, query = null, successCodes = setOf(QR_SUCCESS_CODE)) { root, httpCode ->
            // session.py:499-509 只取 data.uniID / data.url 两个键，无大小写回退；
            // 空缺按解析失败处理（参考源会返回空串导致坏码，这里不给 UI 假成功）
            val data = root["data"] as? JsonObject
            val uniId = data.stringOf("uniID")
            val url = data.stringOf("url")
            if (uniId.isBlank() || url.isBlank()) {
                ApiResult.ParseError("二维码响应缺少 uniID/url (HTTP $httpCode)")
            } else {
                ApiResult.Success(QrGenerateDto(uniId = uniId, url = url))
            }
        }

    override suspend fun qrPoll(uniId: String): ApiResult<QrPollDto> =
        execute(
            path = QR_RESULT_PATH,
            // query 键名 uniID 逐字（session.py:522），禁止"规范化"成 uniId
            query = linkedMapOf("uniID" to uniId),
            successCodes = setOf(QR_SUCCESS_CODE, QR_CONFIRMED_CODE),
        ) { root, _ ->
            val data = root["data"] as? JsonObject
            when (root.codeOrDefault()) {
                // code==200 = 用户已确认（session.py:548-559）：loginStatus 恒 3，
                // scanPlatform 取 data.login_type，token 直传
                QR_CONFIRMED_CODE -> ApiResult.Success(
                    QrPollDto(
                        loginStatus = LOGIN_STATUS_CONFIRMED,
                        scanPlatform = data.longOf("login_type"),
                        token = data.stringOf("token"),
                    ),
                )
                // code==0（session.py:568-577）：loginStatus / scanPlatform 透传，
                // 缺失按参考源默认 -1 / 0；token 无值
                else -> ApiResult.Success(
                    QrPollDto(
                        loginStatus = data.longOf("loginStatus", default = -1),
                        scanPlatform = data.longOf("scanPlatform"),
                        token = "",
                    ),
                )
            }
        }

    /**
     * 单一实现：无主备切换、无自动重试（参考源 qr_* 均为一次性请求，失败即返回）；
     * 包络语义与 PanApi.parseResponse 同源（code==2 → SessionExpired、successCodes → parse、
     * 其余 ApiError），不重复实现第二套错误模型。
     */
    private suspend fun <T> execute(
        path: String,
        query: Map<String, String>?,
        successCodes: Set<Int>,
        parse: (root: JsonObject, httpCode: Int) -> ApiResult<T>,
    ): ApiResult<T> {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            query?.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        // header 素材依赖持久化身份，逐请求取一次（首次会触发 loadOrCreate 落盘）
        val request = Request.Builder().url(url).headers(qrHeaders()).get().build()
        val raw = try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    RawQrResponse(code = response.code, body = response.body?.string())
                }
            }
        } catch (error: IOException) {
            // 协程取消不是 IOException，不会被吞；ensureActive 兜底与 PanApi.executeOnce 一致
            currentCoroutineContext().ensureActive()
            return ApiResult.NetworkError(QR_NETWORK_FAILURE)
        }
        val root = raw.body?.takeIf { it.isNotBlank() }?.let { body ->
            try {
                json.parseToJsonElement(body) as? JsonObject
            } catch (error: IllegalArgumentException) {
                null
            }
        } ?: return ApiResult.ParseError("服务器返回无效 JSON (HTTP ${raw.code})")
        val envelope = try {
            json.decodeFromJsonElement(ApiEnvelope.serializer(), root)
        } catch (error: IllegalArgumentException) {
            return ApiResult.ParseError("服务器返回无效 JSON (HTTP ${raw.code})")
        }
        val result = when {
            envelope.code == SESSION_EXPIRED_CODE -> ApiResult.SessionExpired
            envelope.code in successCodes -> parse(root, raw.code)
            else -> ApiResult.ApiError(envelope.code, envelope.message ?: envelope.msg ?: "")
        }
        // 诊断埋点与 PanApi.parseResponse 同源：只记端点与 code，不记 query 与响应值
        // （uniID 虽非凭据，仍不落日志，LogRedactor 之外再少一层暴露面）。
        if (result is ApiResult.ApiError) {
            logger?.w(LogSource.API, "$path 返回 code=${result.code}：${result.message}")
        } else if (result is ApiResult.ParseError) {
            logger?.w(LogSource.API, "$path 响应无法解析：${result.message}")
        }
        return result
    }

    /** 会话级头 + _qr_headers 的合并结果（见类 KDoc）；GET 无 body 也带 content-type，与 requests 会话级行为一致。 */
    private suspend fun qrHeaders(): Headers {
        val device = identity()
        return Headers.headersOf(
            "platform", "web",                                       // session.py:461 覆盖 android
            "devicename", "Xiaomi",                                  // constants.py:32 固定字面量
            "app-version", "3",                                      // session.py:460 覆盖 61
            "x-app-version", "2.4.0",                                // constants.py:34 会话级
            "user-agent", "123pan/v2.4.0(${device.osVersion};Xiaomi)", // session.py:255
            "osversion", device.osVersion,                           // session.py:256
            "devicetype", device.deviceType,                         // session.py:257
            "loginuuid", device.loginUuid,                           // session.py:459
            "content-type", "application/json;charset=UTF-8",        // session.py:462
        )
    }

    private fun JsonObject.codeOrDefault(): Int = (this["code"] as? JsonPrimitive)?.longOrNull?.toInt() ?: -1

    private fun JsonObject?.stringOf(key: String): String = (this?.get(key) as? JsonPrimitive)?.content ?: ""

    private fun JsonObject?.longOf(key: String, default: Int = 0): Int =
        (this?.get(key) as? JsonPrimitive)?.longOrNull?.toInt() ?: default

    /** 一次已读完 body 的原始应答（结构与 PanApi.RawHttpResult 同型，独立成类避免跨私有成员）。 */
    private class RawQrResponse(val code: Int, val body: String?)

    companion object {
        /** 扫码状态归一化取值（session.py:517：0=等待 1=已扫 2=拒绝 3=确认 4=过期）。 */
        const val LOGIN_STATUS_WAITING = 0
        const val LOGIN_STATUS_SCANNED = 1
        const val LOGIN_STATUS_REJECTED = 2
        const val LOGIN_STATUS_CONFIRMED = 3
        const val LOGIN_STATUS_EXPIRED = 4

        /** 扫码来源（session.py:518）：4=微信（无 token，明确拒绝）7=123云盘App。 */
        const val SCAN_PLATFORM_WECHAT = 4
        const val SCAN_PLATFORM_APP = 7

        private const val QR_GENERATE_PATH = "/api/user/qr-code/generate"
        private const val QR_RESULT_PATH = "/api/user/qr-code/result"
        private const val QR_SUCCESS_CODE = 0
        private const val QR_CONFIRMED_CODE = 200
        private const val SESSION_EXPIRED_CODE = 2
        private const val QR_CONNECT_TIMEOUT_SECONDS = 3
        private const val QR_READ_TIMEOUT_SECONDS = 10
        private const val QR_NETWORK_FAILURE = "网络连接失败，请检查网络"
    }
}
