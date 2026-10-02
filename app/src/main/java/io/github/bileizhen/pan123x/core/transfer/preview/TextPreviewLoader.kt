package io.github.bileizhen.pan123x.core.transfer.preview

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 文本预览拉取结果（冻结模型）。
 *
 * @param text UTF-8 解码后的内容（非法序列按 REPLACE 语义替换为 U+FFFD，不抛异常）。
 * @param bytes 实际读取的字节数（≤ maxBytes）。
 * @param truncated true = 服务端还有更多内容被上限截断；恰好等于上限时不误报。
 * @param totalSize 响应 Content-Length（可能为压缩后长度），缺失为 null。
 */
data class TextContent(
    val text: String,
    val bytes: Long,
    val truncated: Boolean,
    val totalSize: Long?,
)

/**
 * 文本预览直链拉取器（纯 JVM 可测）。
 *
 * 职责边界：只经调用方注入的 [OkHttpClient]（TransferClient，无 API 认证头，
 *）读 InputStream，不感知 Room / UI；本类自身不做任何日志——签名直链含 query，
 * 任何异常消息都不允许携带 URL。
 *
 * 网络纪律：GET 幂等，网络层 IOException 与 5xx 各重试 1 次，退避指数 + jitter、
 * 有上限、感知取消；4xx 属永久失败（signed URL 过期 / 无权限），立即抛出不浪费请求。
 */
object TextPreviewLoader {

    /** 首次 + 重试 1 次；更多次数对预览场景收益为零，只会拖长用户等待。 */
    private const val MAX_ATTEMPTS = 2

    private const val BASE_DELAY_MS = 400L
    private const val MAX_DELAY_MS = 4_000L
    private const val READ_BUFFER_SIZE = 64 * 1024

    /**
     * GET [url] 并至多读取 [maxBytes] 字节——读满即关流，不下载剩余部分
     * （大文本避免一次性全量加载）。
     *
     * @throws IOException 网络失败 / 非 2xx（消息只含状态码，绝不含 URL）或重试后仍失败。
     * @throws IllegalArgumentException [maxBytes] <= 0。
     */
    suspend fun load(url: String, maxBytes: Long, client: OkHttpClient): TextContent {
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        return withContext(Dispatchers.IO) {
            var lastError: IOException? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(backoffMillis(attempt))
                try {
                    return@withContext loadOnce(url, maxBytes, client)
                } catch (error: IOException) {
                    lastError = error
                    if (error is HttpError && !error.retryable) throw error
                }
            }
            throw lastError ?: IOException("文本预览请求失败")
        }
    }

    /**
     * 单次尝试：建立调用后立刻登记取消回调——协程被取消（离开预览页 / URL 更换）时
     * call.cancel 会中断阻塞中的 connect/read，避免 IO 线程挂在 socket 上；
     * 正常完成后调用已结束，cancel 是 no-op，finally 中解除登记。
     */
    private suspend fun loadOnce(url: String, maxBytes: Long, client: OkHttpClient): TextContent {
        val call = client.newCall(Request.Builder().url(url).get().build())
        val canceller = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val response = runInterruptible { call.execute() }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw HttpError(code = resp.code, retryable = resp.code in 500..599)
                }
                coroutineContext.ensureActive()
                return readBody(resp, maxBytes)
            }
        } finally {
            canceller?.dispose()
        }
    }

    private fun readBody(response: Response, maxBytes: Long): TextContent {
        val totalSize = response.header("Content-Length")?.toLongOrNull()
        val input = BufferedInputStream(
            response.body?.byteStream() ?: throw IOException("文本预览响应缺少内容"),
        )
        input.use { stream ->
            val output = ByteArrayOutputStream(min(maxBytes, READ_BUFFER_SIZE.toLong()).toInt())
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var total = 0L
            while (total < maxBytes) {
                val wanted = min(buffer.size.toLong(), maxBytes - total).toInt()
                val read = stream.read(buffer, 0, wanted)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
            }
            // 截断精确判定：读满上限后再探 1 字节，文件恰好等于上限时不把"完整内容"误报为截断；
            // 多读的这一字节随流关闭即弃，不会触发剩余内容的持续下载。
            val truncated = total >= maxBytes && stream.read() >= 0
            return TextContent(
                text = String(output.toByteArray(), Charsets.UTF_8),
                bytes = total,
                truncated = truncated,
                totalSize = totalSize,
            )
        }
    }

    /** 指数退避 + jitter，区间 [cap/2, cap]，有上限；delay 自身感知取消。 */
    private fun backoffMillis(attempt: Int): Long {
        val exponential = BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 4)
        val capped = exponential.coerceAtMost(MAX_DELAY_MS)
        val half = capped / 2
        return half + Random.nextLong(capped - half + 1)
    }

    /** 非 2xx 应答：消息只含状态码（禁止 signed URL 进异常/日志）。 */
    private class HttpError(val code: Int, val retryable: Boolean) :
        IOException("文本预览请求失败（HTTP $code）")
}
