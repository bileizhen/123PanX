package io.github.bileizhen.pan123x.data.auth

import io.github.bileizhen.pan123x.core.network.ApiResult

/**
 * 登录失败到用户可读文案的映射。
 *
 * 规则：服务器 message 已是中文（如"用户名或密码错误"）且非空白时原样透传；
 * 连接层 / 解析层 / 会话过期各给固定文案；绝不把 code、stacktrace 直接丢给用户。
 */
object AuthMessages {

    fun loginFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError ->
            if (result.message.isBlank()) "登录失败（错误码 ${result.code}）" else result.message
        is ApiResult.NetworkError -> NETWORK_UNAVAILABLE
        is ApiResult.ParseError -> MALFORMED_RESPONSE
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在登录失败时调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> UNEXPECTED
    }

    private const val NETWORK_UNAVAILABLE = "网络连接失败，请检查网络后重试"
    private const val MALFORMED_RESPONSE = "服务器响应异常，请稍后重试"
    private const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    private const val UNEXPECTED = "登录失败，请稍后重试"
}
