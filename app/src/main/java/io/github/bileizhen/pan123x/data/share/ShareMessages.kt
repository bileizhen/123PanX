package io.github.bileizhen.pan123x.data.share

import io.github.bileizhen.pan123x.core.network.ApiResult

/**
 * 分享域失败到用户可读文案的映射，风格对齐 data/transfer/UploadMessages：
 * 服务器中文 message 非空白时原样透传；连接层 / 解析层 / 会话过期各给固定文案；
 * **绝不**把 code、stacktrace 或签名 URL 丢给用户。
 *
 * 创建 / 列表 / 撤销三个入口分开：同一 `ApiResult` 失败在不同操作下对用户的措辞不同
 * （如"创建分享失败"与"撤销分享失败"），但映射规则完全一致。
 */
object ShareMessages {

    const val NOT_LOGGED_IN = "请先登录"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    const val CREATE_FAILED = "创建分享失败，请稍后重试"
    const val CREATE_EMPTY_SELECTION = "请先选择要分享的文件"
    const val LIST_FAILED = "分享列表加载失败，请稍后重试"
    const val REVOKE_FAILED = "撤销分享失败，请稍后重试"
    const val NETWORK = "网络连接失败，请检查网络后重试"
    const val MALFORMED = "服务器响应异常，请稍后重试"

    /** 创建分享失败映射：[ApiResult.ApiError] 透传服务端 message，空白回退 [CREATE_FAILED]。 */
    fun createFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError -> result.message.ifBlank { CREATE_FAILED }
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> CREATE_FAILED
    }

    /** 列表加载失败映射：空白服务端 message 回退 [LIST_FAILED]。 */
    fun listFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError -> result.message.ifBlank { LIST_FAILED }
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> LIST_FAILED
    }

    /** 撤销分享失败映射：空白服务端 message 回退 [REVOKE_FAILED]。 */
    fun revokeFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError -> result.message.ifBlank { REVOKE_FAILED }
        is ApiResult.NetworkError -> NETWORK
        is ApiResult.ParseError -> MALFORMED
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> REVOKE_FAILED
    }
}
