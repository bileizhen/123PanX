package io.github.bileizhen.pan123x.core.network

/**
 * 下载链接原始信息（协议真源 `session_file.py#get_file_link`）。
 *
 * - [rawUrl] 对应响应 `data.DownloadUrl | downloadUrl`：需经 web-pro2 重写 + 跟随重定向才能得到真实 CDN 直链；
 * - [directUrl] 对应响应 `data.RedirectUrl | redirect_url`：已是 CDN 直链，可跳过重写与跟随；
 * - [trafficLimited] 表示服务端返回 5113/5114（下载流量超限）。这不是失败：
 *   上层会继续走 web-pro2 重写绕过（参考源记录警告后继续，不返回错误）。
 */
data class DownloadLinkDto(
    val rawUrl: String = "",
    val directUrl: String = "",
    val trafficLimited: Boolean = false,
    /**
     * 包络里的 `message`/`msg` 原文。5113/5114 这类"成功码但无 data"的响应只有它能说明原因，
     * 用于把服务端原因透传给用户（服务器中文 message 非空白时原样透传）。
     */
    val serverMessage: String = "",
)

/**
 * 下载取链 API，供 `PanDownloadResolver` 消费；测试可用 MockWebServer 或替身实现。
 *
 * 两端点均为非幂等 POST，协议层不自动重试，读超时 10s
 * （与 M3 文件操作一致，对应参考源 `session_file.py#get_file_link` 的 `timeout=10`）。
 */
interface PanDownloadApi {

    /**
     * 取下载链接。[isFolder]=true 走 `POST /a/api/file/batch_download_info`
     * （body `fileIdList:[{"fileId":x}]`，内层键**小写**，与 trash 的大写 `FileId` 相反）；
     * 否则走 `POST /a/api/file/download_info`（7 字段逐字对齐参考源）。
     *
     * `code in {5113,5114}`（下载流量超限）不是失败：返回 Success 且 [DownloadLinkDto.trafficLimited]=true。
     * 其余非 0 code 透传 message；`code==2` 映射为 [ApiResult.SessionExpired]。
     */
    suspend fun getDownloadLink(
        fileId: Long,
        fileName: String,
        size: Long,
        etag: String,
        s3KeyFlag: String,
        isFolder: Boolean,
    ): ApiResult<DownloadLinkDto>
}
