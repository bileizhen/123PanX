package io.github.bileizhen.pan123x.core.network

/**
 * 统一 API 结果模型。HTTP 状态码与响应 body 中的业务 code 分开处理：
 * body 可解析为 JSON 时以 body code 为准，否则按解析失败处理。
 */
sealed interface ApiResult<out T> {

    /** 业务成功，携带解析后的 data。 */
    data class Success<T>(val data: T) : ApiResult<T>

    /** 服务器返回了可解析的包络，但业务 code 表示失败。 */
    data class ApiError(val code: Int, val message: String) : ApiResult<Nothing>

    /** 连接层失败（DNS / 连接拒绝 / 超时等 IOException）。 */
    data class NetworkError(val message: String) : ApiResult<Nothing>

    /** 响应不是合法 JSON，或成功 code 下 data 缺失/不符。 */
    data class ParseError(val message: String) : ApiResult<Nothing>

    /** body code == 2，token 已过期，需要重新登录。 */
    object SessionExpired : ApiResult<Nothing>
}
