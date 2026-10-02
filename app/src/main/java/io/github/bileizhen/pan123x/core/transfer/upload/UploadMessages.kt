package io.github.bileizhen.pan123x.core.transfer.upload

import io.github.bileizhen.pan123x.core.network.ApiResult
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 上传域失败到用户可读文案的映射，风格对齐
 * [io.github.bileizhen.pan123x.core.transfer.download.DownloadMessages]：
 * 服务器中文 message 非空白时原样透传；连接层 / 解析层 / 会话过期各给固定文案；
 * **绝不**把 code、stacktrace 或 signed URL 丢给用户。
 *
 * 分两个入口：
 * - [requestFailure]：上传元数据阶段（`ApiResult` 失败，含 `upload_request` / list parts /
 *   预签名 / complete / upload_complete）；
 * - [transferFailure]：分片传输与引擎执行阶段的异常（超时 / 连接 / 本地来源不可读）。
 */
object UploadMessages {

    const val NOT_LOGGED_IN = "请先登录"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    const val ENQUEUE_FAILED = "加入上传队列失败，请稍后重试"
    const val SOURCE_UNREADABLE = "无法读取所选文件，请重新选择"
    const val SOURCE_CHANGED = "文件已变化，请重新选择后上传"
    const val HASH_FAILED = "文件校验失败，请重试"
    const val CONFLICT_TITLE = "存在同名文件"
    const val CONFLICT_RESOLVED_CANCELED = "已取消：同名文件冲突未处理"
    const val QUOTA_EXCEEDED = "云盘空间不足"
    const val NETWORK = "网络连接失败，请检查网络后重试"
    const val MALFORMED = "服务器响应异常，请稍后重试"
    const val UPLOAD_FAILED = "上传失败，请稍后重试"

    /**
     * 元数据请求失败映射：[ApiResult.ApiError] 透传服务端 message，
     * 空白时回退 [UPLOAD_FAILED]（服务器中文 message 常为空，不能直接给用户看空串）。
     */
    fun requestFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError -> result.message.ifBlank { UPLOAD_FAILED }
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> UPLOAD_FAILED
    }

    /**
     * 分片传输 / 引擎异常 → 用户可读文案。按异常类型分派：
     * 超时 → [TIMEOUT]；DNS / 连接失败 → [NETWORK]；来源文件不可读（SAF 失效、文件被删）→
     * [SOURCE_UNREADABLE]；其余 `IOException` 视为网络问题。
     *
     * 说明：M5 冻结的 `UploadPartTransport` 契约只规定"HTTP 非 2xx 或 IO 失败抛 `IOException`"，
     * 并未冻结携带状态码的专用异常类型，故这里不对 HTTP 状态码再细分——
     * 分片级重试与状态码判定属于引擎职责（`UploadEngine` 只重试超时/连接错误）。
     */
    fun transferFailure(error: Throwable): String = when (error) {
        is SocketTimeoutException -> TIMEOUT
        is UnknownHostException, is ConnectException -> NETWORK
        is FileNotFoundException -> SOURCE_UNREADABLE
        is SecurityException -> SOURCE_UNREADABLE
        is IOException -> NETWORK
        else -> UPLOAD_FAILED
    }

    private const val TIMEOUT = "连接超时，请稍后重试"
}
