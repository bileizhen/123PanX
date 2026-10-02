package io.github.bileizhen.pan123x.core.transfer.upload

import io.github.bileizhen.pan123x.core.network.ApiResult
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

/** UploadMessages 失败文案映射测试：中文 message 透传、空白回退、异常分类。 */
class UploadMessagesTest {

    @Test
    fun requestFailurePassesThroughServerMessage() {
        assertEquals(
            "云盘空间不足",
            UploadMessages.requestFailure(ApiResult.ApiError(code = 5000, message = "云盘空间不足")),
        )
    }

    @Test
    fun requestFailureFallsBackWhenServerMessageBlank() {
        assertEquals(UploadMessages.UPLOAD_FAILED, UploadMessages.requestFailure(ApiResult.ApiError(5000, "")))
        assertEquals(UploadMessages.UPLOAD_FAILED, UploadMessages.requestFailure(ApiResult.ApiError(5000, "   ")))
    }

    @Test
    fun requestFailureMapsTransportAndParseFailures() {
        assertEquals(UploadMessages.NETWORK, UploadMessages.requestFailure(ApiResult.NetworkError("boom")))
        assertEquals(UploadMessages.MALFORMED, UploadMessages.requestFailure(ApiResult.ParseError("bad json")))
        assertEquals(UploadMessages.SESSION_EXPIRED, UploadMessages.requestFailure(ApiResult.SessionExpired))
    }

    @Test
    fun requestFailureTreatsSuccessAsProgrammingError() {
        // 防御分支：Success 不该调用本函数，返回通用文案而非崩溃
        assertEquals(UploadMessages.UPLOAD_FAILED, UploadMessages.requestFailure(ApiResult.Success(Unit)))
    }

    @Test
    fun transferFailureClassifiesTimeoutAndConnection() {
        assertEquals("连接超时，请稍后重试", UploadMessages.transferFailure(SocketTimeoutException("t")))
        assertEquals(UploadMessages.NETWORK, UploadMessages.transferFailure(UnknownHostException("dns")))
        assertEquals(UploadMessages.NETWORK, UploadMessages.transferFailure(ConnectException("refused")))
    }

    @Test
    fun transferFailureClassifiesUnreadableSource() {
        assertEquals(UploadMessages.SOURCE_UNREADABLE, UploadMessages.transferFailure(FileNotFoundException("gone")))
        assertEquals(UploadMessages.SOURCE_UNREADABLE, UploadMessages.transferFailure(SecurityException("saf")))
    }

    @Test
    fun transferFailureFallsBackForOtherErrors() {
        assertEquals(UploadMessages.NETWORK, UploadMessages.transferFailure(IOException("http 500")))
        assertEquals(UploadMessages.UPLOAD_FAILED, UploadMessages.transferFailure(IllegalStateException("weird")))
    }

    @Test
    fun constantsMatchFrozenContract() {
        assertEquals("请先登录", UploadMessages.NOT_LOGGED_IN)
        assertEquals("登录状态已失效，请重新登录", UploadMessages.SESSION_EXPIRED)
        assertEquals("加入上传队列失败，请稍后重试", UploadMessages.ENQUEUE_FAILED)
        assertEquals("无法读取所选文件，请重新选择", UploadMessages.SOURCE_UNREADABLE)
        assertEquals("文件已变化，请重新选择后上传", UploadMessages.SOURCE_CHANGED)
        assertEquals("文件校验失败，请重试", UploadMessages.HASH_FAILED)
        assertEquals("存在同名文件", UploadMessages.CONFLICT_TITLE)
        assertEquals("已取消：同名文件冲突未处理", UploadMessages.CONFLICT_RESOLVED_CANCELED)
        assertEquals("云盘空间不足", UploadMessages.QUOTA_EXCEEDED)
        assertEquals("网络连接失败，请检查网络后重试", UploadMessages.NETWORK)
        assertEquals("服务器响应异常，请稍后重试", UploadMessages.MALFORMED)
        assertEquals("上传失败，请稍后重试", UploadMessages.UPLOAD_FAILED)
    }
}
