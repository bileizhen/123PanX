package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.network.ApiResult

/**
 * 文件操作域（M3）失败到用户可读文案的映射，风格对齐 [FileMessages] / AuthMessages：
 * 服务器中文 message 非空白时原样透传；连接层 / 解析层 / 会话过期各给固定文案；
 * 绝不把 code、stacktrace 直接丢给用户。
 */
object FileOpsMessages {

    const val NOT_LOGGED_IN = "请先登录"
    const val SESSION_EXPIRED = "登录状态已失效，请重新登录"
    const val NO_TASK_ID = "响应中未找到任务 ID"
    const val COPY_TIMEOUT = "复制超时，请稍后刷新查看结果"
    const val COPY_TASK_FAILED = "复制任务失败"
    const val ALREADY_IN_TARGET = "所选文件已在该文件夹中"
    const val EMPTY_SELECTION = "未选择任何文件"
    const val EMPTY_NAME = "名称不能为空"
    const val NAME_EXISTS = "已存在同名文件夹"

    /** 非幂等 POST 的失败映射：ApiError 透传 message（空白回退错误码），其余各给固定文案。 */
    fun opFailure(result: ApiResult<*>): String = when (result) {
        is ApiResult.ApiError ->
            if (result.message.isBlank()) "操作失败（错误码 ${result.code}）" else result.message
        is ApiResult.NetworkError -> NETWORK_UNAVAILABLE
        is ApiResult.ParseError -> MALFORMED_RESPONSE
        ApiResult.SessionExpired -> SESSION_EXPIRED
        // 防御分支：调用方只应在失败结果上调用本函数，Success 到这里属于编程错误。
        is ApiResult.Success -> UNEXPECTED
    }

    /** trash / restore 批量逐个循环后的聚合理由（部分失败要说明成败数量）。 */
    fun aggregate(successCount: Int, failedCount: Int, firstReason: String): String =
        "成功 $successCount 个，失败 $failedCount 个：$firstReason"

    private const val NETWORK_UNAVAILABLE = "网络连接失败，请检查网络后重试"
    private const val MALFORMED_RESPONSE = "服务器响应异常，请稍后重试"
    private const val UNEXPECTED = "操作失败，请稍后重试"
}
