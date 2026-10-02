package io.github.bileizhen.pan123x.core.transfer.download

import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.HttpFailure
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.RangeFailure
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SizeMismatch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 下载域失败到用户可读文案的映射，风格对齐 [io.github.bileizhen.pan123x.data.file.FileOpsMessages]：
 * 服务器中文 message 非空白时原样透传；连接层 / 解析层 / 会话过期各给固定文案；
 * **绝不**把 code、stacktrace 或 signed URL 丢给用户。
 *
 * 分两个入口：
 * - [resolveFailure]：取链阶段（`ApiResult` 失败）；
 * - [transferFailure]：下载执行阶段的异常（NSFX 抛出的 `HttpFailure` / `RangeFailure` /
 *   `SizeMismatch` 及 `IOException` 子类）。
 */
object DownloadMessages {

    const val NOT_LOGGED_IN = "请先登录"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    const val ENQUEUE_FAILED = "加入下载队列失败，请稍后重试"
    const val NO_DOWNLOAD_URL = "响应中未找到下载链接"
    const val UNSAFE_REDIRECT = "下载地址不可信，已拒绝"
    const val TRAFFIC_LIMITED = "下载流量已超出限制，正在尝试绕过"

    /**
     * 5113/5114 且响应中没有任何可取链的 URL（无法走 web-pro2 重写绕过）时的文案。
     * 必须与 [NO_DOWNLOAD_URL] 区分：这两种原因对用户的可操作动作完全不同。
     */
    const val TRAFFIC_LIMITED_BLOCKED = "下载流量已超出限制，暂时无法获取下载地址"
    const val SAF_PERMISSION = "保存位置权限已失效，请重新选择"
    const val DISK_FULL = "存储空间不足"
    const val RANGE_UNSUPPORTED = "服务器不支持分段下载，已回退单连接"
    const val URL_EXPIRED = "下载地址已失效，请重试"
    const val NETWORK = "网络连接失败，请检查网络后重试"
    const val MALFORMED = "服务器响应异常，请稍后重试"

    /** 取链失败映射：ApiError 透传 message（空白回退固定文案），其余各给固定文案。 */
    fun resolveFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError -> result.message.ifBlank { RESOLVE_FAILED }
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> RESOLVE_FAILED
    }

    /**
     * 下载执行层异常 → 用户可读文案。按异常类型分派：
     * NSFX 的 `HttpFailure` 依 HTTP 状态码细分（429/5xx/403/404/410/416）；`RangeFailure` → Range 不支持；
     * `SizeMismatch` → signed URL 失效（长度不符）；磁盘满 / SAF 权限失效单独识别。
     */
    fun transferFailure(error: Throwable): String = when (error) {
        is HttpFailure -> httpFailureMessage(error.status)
        is RangeFailure -> RANGE_UNSUPPORTED
        is SizeMismatch -> URL_EXPIRED
        is SocketTimeoutException -> TIMEOUT
        is UnknownHostException -> NETWORK
        is IOException -> if (isDiskFull(error)) DISK_FULL else NETWORK
        is SecurityException -> SAF_PERMISSION
        else -> MALFORMED
    }

    private fun httpFailureMessage(status: Int): String = when {
        status == HTTP_TOO_MANY_REQUESTS -> RATE_LIMITED
        status == 401 || status == 403 -> URL_EXPIRED
        status == 404 || status == 410 -> URL_EXPIRED
        status == 416 -> RANGE_UNSUPPORTED
        status in 500..599 -> SERVER_ERROR
        else -> MALFORMED
    }

    private fun isDiskFull(error: IOException): Boolean {
        val text = error.message.orEmpty()
        return text.contains("ENOSPC", ignoreCase = true) ||
            text.contains("No space left", ignoreCase = true) ||
            text.contains("disk full", ignoreCase = true) ||
            text.contains("空间不足")
    }

    private const val RESOLVE_FAILED = "获取下载链接失败，请稍后重试"
    private const val TIMEOUT = "连接超时，请稍后重试"
    private const val RATE_LIMITED = "请求过于频繁，请稍后重试"
    private const val SERVER_ERROR = "服务器暂时不可用，请稍后重试"
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
