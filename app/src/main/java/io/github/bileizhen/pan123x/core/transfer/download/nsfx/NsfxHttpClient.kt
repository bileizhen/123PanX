// Kotlin adaptation of Hanabi-Download-Manager-X NSFX via bileizhen/LeiFetch. SPDX-License-Identifier: GPL-3.0-only
//
// 有意偏离 LeiFetch（偏离 3）：传输层 `HttpURLConnection` → OkHttp。
// 原因：OkHttp 是项目标准栈，且 `PanHttpClientFactory.transferClient` 已提供
// 携带 `followRedirects=false` 的干净客户端——NSFX 需要自行控制每一跳重定向以实施
// HTTPS 降级拦截与跨 origin 头部剥离。
package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** 服务器返回了不可继续的状态码（如 4xx/5xx）。 */
class HttpFailure(val status: Int) : IOException("HTTP $status")

/** Range 语义被破坏：期望 206 却得到 200，或校验器 / Content-Range 不匹配，或响应被压缩。 */
class RangeFailure(message: String) : IOException(message)

/** 实际下载字节数与已知大小不符。 */
class SizeMismatch(message: String) : IOException(message)

/**
 * NSFX 的 OkHttp 传输层。
 *
 * 行为契约（对应 LeiFetch NsfxHttpClient）：
 * - 手动跟随 301/302/303/307/308，最多 6 跳；拒绝非 http(s)、拒绝含 userinfo、
 *   拒绝 HTTPS→HTTP 降级；跨 origin 时剥离 authorization/cookie/referer。
 * - 固定发送 `Accept-Encoding: identity`、`User-Agent: 123PanX-NSFX/0.1`、`Accept` 通配值
 *   （星号 斜杠 星号，此处不写出字面量以免提前闭合本注释），可选 `Range` 与 `If-Range`。
 * - 全局 `Semaphore(config.globalMaxConnections)` 限制并发连接数。
 * - 协程取消时取消在途 OkHttp `Call`（`invokeOnCompletion(onCancelling = true)`）。
 *
 * @param config NSFX 配置（超时、全局连接上限）。
 * @param client 传输专用 OkHttpClient（应为 `PanHttpClientFactory.transferClient`，不携带 API 头）。
 */
class NsfxHttpClient(private val config: NsfxConfig, private val client: OkHttpClient) {

    private val connections = Semaphore(config.globalMaxConnections.coerceIn(1, 128))

    /**
     * 一次已建立的应答。[stream] 为响应体输入流；读取到 EOF 后调用 [markConsumed]，
     * 让 [close] 把连接归还连接池（否则取消 Call 丢弃连接）。
     */
    class Response(
        private val call: Call,
        private val raw: okhttp3.Response,
        private val release: (Boolean) -> Unit,
    ) : Closeable {

        private val closed = AtomicBoolean(false)
        private var body: InputStream? = null
        private var reusable = false

        /** HTTP 状态码。 */
        val code: Int get() = raw.code

        /** 最终（跟随重定向后）的 URL。 */
        val url: String get() = raw.request.url.toString()

        /** Content-Length；缺失（如 chunked）时为 -1。 */
        val length: Long get() = header("Content-Length")?.toLongOrNull() ?: -1L

        /** 读取响应头。 */
        fun header(name: String): String? = raw.header(name)

        /** 响应体输入流（惰性打开）。 */
        val stream: InputStream
            get() = body ?: (raw.body ?: throw IOException("响应缺少 body")).byteStream().also { body = it }

        /** 仅在已读到 EOF 后调用；此时连接才可安全复用。 */
        fun markConsumed() {
            reusable = true
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { body?.close() }
            release(reusable)
        }
    }

    /**
     * 发起 GET，手动跟随重定向。
     *
     * @param url 起始地址。
     * @param headers 额外请求头（仅透传 authorization/cookie/user-agent/referer/accept/accept-language）。
     * @param range 可选的 `Range` 头值（如 `bytes=0-0`）。
     * @param validator 可选的 `If-Range` 校验器（ETag 或 Last-Modified）。
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        range: String? = null,
        validator: String? = null,
    ): Response {
        var current = parseUrl(url)
        var origin = originOf(current)
        var extra = headers.filterKeys { it.lowercase() in ALLOWED_HEADERS }
        repeat(MAX_REDIRECTS) {
            connections.acquire()
            var call: Call? = null
            var cancellation: kotlinx.coroutines.DisposableHandle? = null
            var handedOff = false
            try {
                val request = Request.Builder().url(current).apply {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "*/*")
                    extra.forEach { (k, v) ->
                        require('\r' !in v && '\n' !in v) { "请求头含非法字符" }
                        header(k, v)
                    }
                    header("Accept-Encoding", "identity")
                    range?.let { header("Range", it) }
                    validator?.let { header("If-Range", it) }
                }.build()
                val c = client.newCall(request)
                call = c
                cancellation = registerCancellation(c)
                currentCoroutineContext().ensureActive()
                val response = withContext(Dispatchers.IO) { c.execute() }
                if (response.code in REDIRECT_CODES) {
                    response.close()
                    val location = response.header("Location") ?: throw IOException("重定向缺少 Location")
                    val next = current.resolve(location) ?: throw IOException("无效重定向地址")
                    require(next.scheme == "http" || next.scheme == "https") { "拒绝非 HTTP 重定向" }
                    require(next.username.isEmpty() && next.password.isEmpty()) { "拒绝含凭据的重定向" }
                    require(!(current.scheme == "https" && next.scheme == "http")) { "拒绝 HTTPS 降级" }
                    val nextOrigin = originOf(next)
                    if (nextOrigin != origin) {
                        extra = extra.filterKeys { it.lowercase() !in STRIPPED_ON_ORIGIN_CHANGE }
                    }
                    origin = nextOrigin
                    current = next
                } else {
                    val handle = cancellation
                    handedOff = true
                    return Response(c, response) { reusable ->
                        handle?.dispose()
                        if (!reusable) c.cancel()
                        connections.release()
                    }
                }
            } finally {
                if (!handedOff) {
                    cancellation?.dispose()
                    call?.cancel()
                    connections.release()
                }
            }
        }
        throw IOException("重定向超过上限")
    }

    /**
     * 注册协程取消回调：取消时立刻 `Call.cancel` 断开在途连接。
     * 必须是 suspend——`currentCoroutineContext` 只在挂起上下文中可用（LeiFetch 原实现直接在
     * `suspend fun get` 内联注册，此处抽出为方法，故同样声明为 suspend）。
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun registerCancellation(call: Call): kotlinx.coroutines.DisposableHandle =
        currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }

    private fun parseUrl(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: throw IOException("无效下载地址")
        require(url.scheme == "http" || url.scheme == "https") { "仅支持 http(s) 地址" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "拒绝含凭据的下载地址" }
        return url
    }

    private fun originOf(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    private companion object {
        const val MAX_REDIRECTS = 6
        const val USER_AGENT = "123PanX-NSFX/0.1"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val ALLOWED_HEADERS = setOf("authorization", "cookie", "user-agent", "referer", "accept", "accept-language")
        val STRIPPED_ON_ORIGIN_CHANGE = setOf("authorization", "cookie", "referer")
    }
}
