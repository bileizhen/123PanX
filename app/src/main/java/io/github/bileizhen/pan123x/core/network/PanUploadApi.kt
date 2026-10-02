package io.github.bileizhen.pan123x.core.network

/**
 * `upload_request` 的响应结果（协议真源 `.reference/123pan`
 * `src/app/service/upload_service.py`）。
 *
 * 三种结局共用本 DTO：
 * - [conflict]=true 表示服务端返回 `code==5060`（**同名冲突，不是失败**）；此时其余字段无意义，
 *   由上层询问用户后带 `duplicate` 1/2 重发；
 * - [reuse]=true 表示秒传命中：服务端已存在相同 `etag + size` 的文件，[fileId] 即最终文件 id，
 *   无需上传任何字节；
 * - 否则为新建 S3 multipart 会话，[bucket] / [storageNode] / [key] / [uploadId] / [fileId]
 *   五项即会话凭据（对应 `data.Bucket` / `data.StorageNode` / `data.Key` / `data.UploadId` / `data.FileId`）。
 *
 * [serverMessage] 是包络 `message`/`msg` 原文，用于把服务端原因透传给用户。
 */
data class UploadRequestDto(
    val conflict: Boolean = false,
    val reuse: Boolean = false,
    val fileId: Long = 0,
    val bucket: String = "",
    val storageNode: String = "",
    val key: String = "",
    val uploadId: String = "",
    val serverMessage: String = "",
)

/**
 * 上传协议 API，供 `UploadEngine` / `UploadCoordinator` 消费；
 * 测试可用 MockWebServer 或替身实现。
 *
 * 协议要点（逐字对齐 `upload_service.py`，**禁止**"顺手统一"大小写）：
 * - 端点名拼写就是 `s3_repare_upload_parts_batch`（**repare**，不是 prepare）；
 * - `s3_list_upload_parts` 与 `s3_complete_multipart_upload` 的 body 用**小写** `storageNode`；
 * - `s3_repare_upload_parts_batch` 的 body 用**大写** `StorageNode`。
 *
 * 五个端点均为非幂等 POST，协议层**不**自动重试，读超时 15s
 * （对应参考源 `_UPLOAD_REQUEST_TIMEOUT`）。分片本身的 `PUT` 不在这里，走
 * `TransferClient`（不带 API 认证头）。
 */
interface PanUploadApi {

    /**
     * 申请上传（`POST /b/api/file/upload_request`）。
     *
     * [duplicate] 取值 `0` = 提示（交上层询问用户）、`1` = 保留两者、`2` = 覆盖。
     * 参考源首次请求固定用 `0`。`code==5060` 走 [UploadRequestDto.conflict]，**不算失败**，
     * 因此 5060 与 0 同为成功码；其余非 0 code 透传 message；`code==2` 映射为
     * [ApiResult.SessionExpired]（由既有包络解析统一处理）。
     */
    suspend fun requestUpload(
        fileName: String,
        size: Long,
        etag: String,
        parentFileId: Long,
        duplicate: Int,
    ): ApiResult<UploadRequestDto>

    /**
     * 列出该 S3 会话已上传的分片号（`POST /b/api/file/s3_list_upload_parts`）。
     * 返回 **1-based 升序**分片号；无法转成整数的条目跳过（参考源 `int(PartNumber)` 容错）。
     */
    suspend fun listUploadedParts(
        bucket: String,
        key: String,
        uploadId: String,
        storageNode: String,
    ): ApiResult<List<Int>>

    /**
     * 批量预签名（`POST /b/api/file/s3_repare_upload_parts_batch`，注意是 **repare**）。
     *
     * [partNumberStart] 含、[partNumberEnd] **不含**（：`end = min(pn + batch, totalParts + 1)`）。
     * 返回 `partNumber -> presignedUrl`；响应可能少于请求窗口（键为非数字的条目跳过），
     * 由调用方对缺失分片做单分片兜底。
     */
    suspend fun presignParts(
        bucket: String,
        key: String,
        uploadId: String,
        storageNode: String,
        partNumberStart: Int,
        partNumberEnd: Int,
    ): ApiResult<Map<Int, String>>

    /** 合并分片（`POST /b/api/file/s3_complete_multipart_upload`），`code==0` 即成功。 */
    suspend fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        storageNode: String,
    ): ApiResult<Unit>

    /**
     * 上传完成确认（`POST /b/api/file/upload_complete`），`code==0` 即成功。
     *
     *  的"大文件（>64MiB）先延迟 3s"由协调器负责，本方法**不含** sleep：
     * 网络层不做与协议无关的等待，否则无法单测也无法取消。
     */
    suspend fun finishUpload(fileId: Long): ApiResult<Unit>
}
