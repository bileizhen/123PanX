package io.github.bileizhen.pan123x.core.transfer.upload.engine

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import io.github.bileizhen.pan123x.core.transfer.LiveRateLimiter

/**
 * 分片 PUT 收到非 2xx（如预签名失效 403、S3 5xx）。继承 [IOException] 以满足
 * [UploadPartTransport.put] 的"失败抛 IOException"契约；引擎对它**不重试**、立即放弃
 * 整个上传（重试只针对超时/连接错误，参考源 `_put_part_with_retry` 的异常清单，
 * upload_service.py:203-208）。
 */
class UploadPartHttpException(val code: Int) : IOException("HTTP $code")

/**
 * 分片字节 PUT 接缝。实现**必须**走 TransferClient——
 * 即不携带 123pan API 的 `authorization` / `loginuuid` / 设备头的干净 OkHttpClient
 * （预签名 URL 本身已含鉴权参数，再加 API 头既多余又泄露凭据）。
 */
fun interface UploadPartTransport {

    /**
     * `PUT` [data] 到预签名 [url]；HTTP 非 2xx 或 IO 失败抛 [IOException]。
     *
     * [timeoutSeconds] 为单次 PUT 的整体超时预算（参考源 `_UPLOAD_PART_TIMEOUT = 120`，
     * upload_service.py:26；限速放宽逻辑 M5 不做，见 "不做"清单）。
     */
    suspend fun put(url: String, data: ByteArray, timeoutSeconds: Int)
}

/**
 * [UploadPartTransport] 的 OkHttp 实现。
 *
 * - 网络执行切到 [Dispatchers.IO]（OkHttp `execute` 是阻塞调用，不得占用调用方调度器；
 *   引擎的编排协程保持可测试的调用方上下文）；
 * - 协程取消时立刻 `Call.cancel` 断开在途连接（对齐 NsfxHttpClient 的取消传播方式），
 *   保证"取消立即停止"；
 * - 每次调用用 `Call.timeout` 设整体超时预算，超过时 OkHttp 抛 [java.net.SocketTimeoutException]
 *   ——恰好落在引擎"可重试"的异常清单内；
 * - **绝不**记录 [url]（预签名 URL 含鉴权 query， 禁止进入日志）。
 */
class OkHttpUploadPartTransport(
    private val client: OkHttpClient,
    private val limiter: LiveRateLimiter? = null,
    private val speedLimit: () -> Long = { 0 },
    private val concurrentTasks: () -> Int = { 1 },
) : UploadPartTransport {

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun put(url: String, data: ByteArray, timeoutSeconds: Int) {
        withContext(Dispatchers.IO) {
            // 解析失败只抛异常，不把原始 URL 带进消息
            val target = url.toHttpUrlOrNull() ?: throw IOException("无效上传地址")
            val jobContext = currentCoroutineContext()
            val body = object : RequestBody() {
                override fun contentType() = null
                override fun contentLength() = data.size.toLong()
                override fun writeTo(sink: BufferedSink) {
                    var offset = 0
                    while (offset < data.size) {
                        jobContext.ensureActive()
                        val count = minOf(16 * 1024, data.size - offset)
                        limiter?.consumeBlocking(count) { jobContext.ensureActive() }
                        sink.write(data, offset, count)
                        sink.emit()
                        offset += count
                    }
                }
            }
            val request = Request.Builder()
                .url(target)
                .put(body)
                .build()
            val call = client.newCall(request)
            // 协程取消 → 取消在途 Call；onCancelling 保证挂起/阻塞阶段也能及时中断
            val handle = currentCoroutineContext().job.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause != null) call.cancel()
            }
            try {
                // Intentional throttling must not consume the 120 s network failure budget.
                val rate = speedLimit()
                val throttleSeconds = if (rate > 0) (data.size.toLong() * concurrentTasks().coerceIn(1, 32) * 4 + rate - 1) / rate else 0
                call.timeout().timeout(timeoutSeconds.coerceAtLeast(1) + throttleSeconds, TimeUnit.SECONDS)
                currentCoroutineContext().ensureActive()
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw UploadPartHttpException(response.code)
                    }
                    // body 必须 close（use 已做）以归还连接；PUT 响应通常为空体
                }
            } catch (timedOut: InterruptedIOException) {
                // OkHttp 的整体调用超时（Call.timeout）以 InterruptedIOException("timeout") 表现，
                // 而引擎只把 SocketTimeoutException 视为可重试超时（upload_service.py:203-208 的
                // requests Timeout 语义）。归一化类型，否则真实网络抖动不会被重试。
                if (timedOut.message == "timeout") {
                    throw SocketTimeoutException("上传分片超时（${timeoutSeconds}s）")
                }
                throw timedOut
            } finally {
                handle.dispose()
            }
        }
    }
}
