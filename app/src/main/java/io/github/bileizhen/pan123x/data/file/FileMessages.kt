package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.network.ApiResult

/**
 * 文件域失败到用户可读文案的映射，风格对齐 data/auth/AuthMessages：
 * 服务器中文 message 非空白时原样透传；连接层 / 解析层 / 会话过期各给固定文案；
 * 绝不把 code、stacktrace 直接丢给用户。
 */
object FileMessages {

    const val NOT_LOGGED_IN = "请先登录"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    const val ACCOUNT_CHANGED = "账户已切换，请重新加载目录"

    fun refreshFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError ->
            if (result.message.isBlank()) "加载失败（错误码 ${result.code}）" else result.message
        is ApiResult.NetworkError -> NETWORK_UNAVAILABLE
        is ApiResult.ParseError -> MALFORMED_RESPONSE
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> UNEXPECTED
    }

    private const val NETWORK_UNAVAILABLE = "网络连接失败，请检查网络后重试"
    private const val MALFORMED_RESPONSE = "服务器响应异常，请稍后重试"
    private const val UNEXPECTED = "加载失败，请稍后重试"
}
