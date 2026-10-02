package io.github.bileizhen.pan123x.core.transfer.download

import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.DownloadLinkDto
import io.github.bileizhen.pan123x.core.network.PanDownloadApi
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 下载来源：由 `CloudFileEntity` 映射，去掉 Room 耦合，便于 JVM 测试。
 * 短期 CDN signed URL 绝不作为任务身份，因此这里只保留 fileId/size/etag 等稳定字段。
 */
data class DownloadSource(
    val fileId: Long,
    val fileName: String,
    val size: Long,
    val etag: String,
    val s3KeyFlag: String,
    val isFolder: Boolean,
)

/** 取链结果：[Success.url] 已是可用 CDN 直链；[Failure] 携带用户可读文案。 */
sealed interface ResolveOutcome {
    data class Success(val url: String, val trafficLimited: Boolean) : ResolveOutcome
    data class Failure(val userMessage: String) : ResolveOutcome
}

/**
 * 下载 URL 纯函数工具（JVM 可测，无 Android / OkHttp 依赖）。
 *
 * 行为逐条移植参考源 `.reference/123pan/src/app/api/download_url.py`：
 * `rewrite_download_url` / `is_safe_download_url` / `b64_encode` / `b64_decode` /
 * `decode_download_v2_params`，以及 `resolve_download_url` 中的 href 正则。
 *
 * 与 Python 的差异仅在实现手段（用 `java.net.URI` + 字符串拼接替代 `urlparse`/`urlunparse`，
 * 以避免 Java URI 多参构造器对 query 二次百分号编码）；语义保持一致。
 */
object DownloadUrlCodec {

    private const val WEB_PRO_MARKER = "web-pro"
    private const val WEB_PRO2_PROXY = "https://web-pro2.123952.com/download-v2/"
    private const val DOWNLOAD_V2_PATH = "/download-v2/"
    private const val HREF_SCAN_CHARS = 500
    private const val AUTO_REDIRECT_KEY = "auto_redirect"
    private const val PARAMS_KEY = "params"

    /** 参考源 `HREF_URL_RE = re.compile(r"href='(https?://[^']+)'")`。 */
    private val HREF_REGEX = Regex("href='(https?://[^']+)'")

    /** 先标准后 URL-safe，对应参考源 `b64_decode` 的 `(base64.b64decode, base64.urlsafe_b64decode)` 顺序。 */
    private val DECODERS = listOf(Base64.getDecoder(), Base64.getUrlDecoder())

    /**
     * 重写下载 URL，模拟 `123pan_unlock.js` 的流量限制绕过（参考源 `rewrite_download_url`）。
     *
     * - host 含 `web-pro` → 解出内层 `params`、写入 `auto_redirect=0`、重新编码；`params` 缺失时原样返回；
     * - 否则把原 URL（追加 `auto_redirect=0`）包成
     *   `https://web-pro2.123952.com/download-v2/?params=<urlsafe-b64>&is_s3=0`。
     *
     * 任何异常都返回原 URL（参考源 `except Exception: return url`），绝不抛出。
     */
    fun rewriteDownloadUrl(url: String): String {
        return try {
            val host = hostOf(url)
            if (host.contains(WEB_PRO_MARKER)) {
                val paramsB64 = parseQuery(queryOf(url))[PARAMS_KEY].orEmpty()
                if (paramsB64.isEmpty()) return url
                val decoded = decodeParams(paramsB64).ifEmpty { paramsB64 }
                val innerQuery = parseQuery(queryOf(decoded)).toMutableMap()
                innerQuery[AUTO_REDIRECT_KEY] = "0"
                val newInner = replaceQuery(decoded, innerQuery)
                val outerQuery = parseQuery(queryOf(url)).toMutableMap()
                outerQuery[PARAMS_KEY] = encodeParams(newInner)
                replaceQuery(url, outerQuery)
            } else {
                val origQuery = parseQuery(queryOf(url)).toMutableMap()
                origQuery[AUTO_REDIRECT_KEY] = "0"
                val rewrittenOrig = replaceQuery(url, origQuery)
                "$WEB_PRO2_PROXY?$PARAMS_KEY=${encodeParams(rewrittenOrig)}&is_s3=0"
            }
        } catch (error: Exception) {
            // 参考源：重写失败时回退原始 URL，绝不向上抛（download_url.py:122-124）
            url
        }
    }

    /**
     * 从 download-v2 URL 解出 base64 编码的下载链接（参考源 `decode_download_v2_params`）。
     * 非 `/download-v2/` 路径、缺 `params`、或解码结果不以 `http` 开头时返回空串。
     */
    fun decodeDownloadV2Params(url: String): String {
        return try {
            val parsed = URI(url)
            if (!parsed.rawPath.orEmpty().contains(DOWNLOAD_V2_PATH)) return ""
            val paramsB64 = parseQuery(queryOf(url))[PARAMS_KEY].orEmpty()
            if (paramsB64.isEmpty()) return ""
            val decoded = decodeParams(paramsB64)
            if (decoded.startsWith("http")) decoded else ""
        } catch (error: Exception) {
            ""
        }
    }

    /**
     * 下载重定向安全性校验（参考源 `is_safe_download_url`）：仅接受 `https`、必须有 hostname、
     * 无 userinfo、host 非 `localhost`；若 host 是字面量 IP，则私有 / 回环 / 链路本地 / 保留 /
     * 未指定地址一律拒绝。普通域名放行。
     */
    fun isSafeDownloadUrl(url: String): Boolean {
        val parsed = try {
            URI(url)
        } catch (error: Exception) {
            return false
        }
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host
        if (host.isNullOrEmpty()) return false
        if (!parsed.userInfo.isNullOrEmpty()) return false
        val hostname = host.lowercase().trimEnd('.')
        if (hostname == "localhost" || hostname == "localhost.localdomain") return false
        val address = literalAddress(hostname) ?: return true
        return !isUnsafeAddress(address)
    }

    /** 提取 body 前 500 字符中 `href='https?://...'` 的第一个链接（参考源 `resolve_download_url`）。 */
    fun extractHref(body: String): String? =
        HREF_REGEX.find(body.take(HREF_SCAN_CHARS))?.groupValues?.get(1)

    /**
     * URL-safe base64，**保留 `=` 填充**——与参考源 `b64_encode = base64.urlsafe_b64encode(...)`
     * 完全一致（先保持已验证行为，不擅自改变请求形态）。
     */
    fun encodeParams(url: String): String =
        Base64.getUrlEncoder().encodeToString(url.toByteArray(Charsets.UTF_8))

    /**
     * 解码 base64：先标准、后 URL-safe；两者都失败时返回原串（参考源 `b64_decode` 的兜底语义）。
     * 兼容带/不带 `=` 填充的输入。
     */
    fun decodeParams(data: String): String {
        if (data.isEmpty()) return ""
        for (decoder in DECODERS) {
            try {
                return String(decoder.decode(padBase64(data)), Charsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                // 换下一种字母表；与参考实现 b64_decode 的 try/except 顺序一致
            }
        }
        return data
    }

    /** 补齐 base64 到 4 的倍数，容忍去填充的 URL-safe 输入。 */
    private fun padBase64(data: String): String {
        val remainder = data.length % 4
        return if (remainder == 0) data else data + "=".repeat(4 - remainder)
    }

    /** 取 URL 的 query（去掉 fragment），无 `?` 时返回 null。 */
    private fun queryOf(url: String): String? {
        val withoutFragment = url.substringBefore('#')
        val index = withoutFragment.indexOf('?')
        return if (index >= 0) withoutFragment.substring(index + 1) else null
    }

    /** 解析 query 为有序 map：跳过无 `=` 的片段，键值不做百分号解码（对齐参考源 `_qs_to_dict`）。 */
    private fun parseQuery(query: String?): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (query.isNullOrEmpty()) return result
        for (part in query.split('&')) {
            val index = part.indexOf('=')
            if (index < 0) continue
            result[part.substring(0, index)] = part.substring(index + 1)
        }
        return result
    }

    /** 用给定有序 map 替换 URL 的 query，其余部分（含 fragment）原样保留（对齐 `urlunparse(_replace(query=...))`）。 */
    private fun replaceQuery(url: String, query: Map<String, String>): String {
        val fragmentIndex = url.indexOf('#')
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val queryIndex = withoutFragment.indexOf('?')
        val base = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val queryString = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        return if (queryString.isEmpty()) base + fragment else "$base?$queryString$fragment"
    }

    /** 取 host（优先 `java.net.URI`，失败时回退宽松解析），供分支判断与安全日志使用。 */
    private fun hostOf(url: String): String {
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return lenientHost(url)
    }

    /** 宽松 host 提取：`scheme://[userinfo@]host[:port]` 中取 host（容忍 URI 无法解析的输入）。 */
    private fun lenientHost(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val rest = url.substring(schemeEnd + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val hostPort = authority.substringAfterLast('@')
        return if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
    }

    /** 仅当 hostname 是字面量 IP（点分 v4 / 冒号 v6）时才解析，避免触发 DNS 查询。 */
    private fun literalAddress(hostname: String): InetAddress? {
        val literal = hostname.removePrefix("[").removeSuffix("]")
        val looksLikeIp = literal.contains(':') || literal.all { it.isDigit() || it == '.' }
        if (!looksLikeIp) return null
        return try {
            InetAddress.getByName(literal)
        } catch (error: Exception) {
            null
        }
    }

    /** 私有 / 回环 / 链路本地 / 保留 / 未指定地址判定（含 IPv6 唯一本地 fc00::/7）。 */
    private fun isUnsafeAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                first == 0 || first >= 240 ||
                    (first == 192 && second == 0) ||
                    (first == 198 && (second == 18 || second == 19 || second == 51)) ||
                    (first == 203 && second == 0)
            }
            // fc00::/7 唯一本地地址（Java 的 isSiteLocalAddress 只覆盖已废弃的 fec0::/10）
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }
}

/**
 * 下载 URL 解析器（的 `PanDownloadResolver`）。
 *
 * 职责：向 123pan 取链（[PanDownloadApi]）→ 直链短路 / web-pro2 重写 → 用传输客户端手动跟随
 * 重定向 → 逐跳安全性校验 → JSON 重定向预检。**不**下载文件本体（那是 NSFX 的职责）。
 *
 * 安全与隐私：
 * - 每个重定向候选都要通过 [DownloadUrlCodec.isSafeDownloadUrl]，否则拒绝并保留原地址；
 * - 日志只记录文件名与 URL host，绝不记录 signed URL 或其 query。
 *
 * 会话：`code==2` 时经 [relogin] 重登一次并重试整次解析一次（绝不重试第二次）。
 *
 * @param transfer `PanHttpClientFactory.transferClient`：`followRedirects=false`，每一跳由本类控制。
 * @param relogin 重登回调；返回 false 表示重登失败。
 */
class PanDownloadResolver(
    private val api: PanDownloadApi,
    private val transfer: OkHttpClient,
    private val logger: AppLogger,
    private val relogin: suspend () -> Boolean,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 [source] 为可用 CDN 直链。
     *
     * 顺序：取链 → `directUrl` 非空且安全则直接返回（不发额外请求）→ 否则重写 `rawUrl` 并跟随
     * 重定向（① 3xx `Location`；② body `href`；③ `download-v2` params 兜底）→ JSON 重定向预检。
     */
    suspend fun resolve(source: DownloadSource): ResolveOutcome = resolveOnce(source, allowRelogin = true)

    private suspend fun resolveOnce(source: DownloadSource, allowRelogin: Boolean): ResolveOutcome {
        val link = api.getDownloadLink(
            fileId = source.fileId,
            fileName = source.fileName,
            size = source.size,
            etag = source.etag,
            s3KeyFlag = source.s3KeyFlag,
            isFolder = source.isFolder,
        )
        return when (link) {
            is ApiResult.Success -> resolveFromLink(source, link.data)
            ApiResult.SessionExpired -> {
                if (allowRelogin && relogin()) {
                    resolveOnce(source, allowRelogin = false)
                } else {
                    ResolveOutcome.Failure(DownloadMessages.SESSION_EXPIRED)
                }
            }
            else -> ResolveOutcome.Failure(DownloadMessages.resolveFailure(link))
        }
    }

    private suspend fun resolveFromLink(source: DownloadSource, link: DownloadLinkDto): ResolveOutcome {
        val direct = link.directUrl
        if (direct.isNotBlank()) {
            if (DownloadUrlCodec.isSafeDownloadUrl(direct)) {
                logger.i(LogSource.DOWNLOAD, "获取直链成功：${source.fileName}（主机 ${hostOf(direct)}）")
                return ResolveOutcome.Success(direct, link.trafficLimited)
            }
            logger.w(LogSource.DOWNLOAD, "直链不可信，已拒绝：${source.fileName}（主机 ${hostOf(direct)}）")
        }

        val raw = link.rawUrl
        if (raw.isBlank()) {
            // 5113/5114（下载流量超限）的响应可能只带 code、完全没有 data，此时既没有 directUrl
            // 也没有 rawUrl 可供 web-pro2 重写绕过——必须把真实原因告诉用户，否则会误报成
            // "响应中未找到下载链接"（参考源同样会走到它的 NO_DOWNLOAD_URL 分支，只是文案不区分）。
            // 刻意不透传服务端 message：成功响应的 message 常是 "ok" 这类无意义文本，
            // 服务端原文只进诊断日志（见 PanApi.parseDownloadLink），不进用户文案。
            if (link.trafficLimited) {
                logger.w(LogSource.DOWNLOAD, "${DownloadMessages.TRAFFIC_LIMITED_BLOCKED}：${source.fileName}")
                return ResolveOutcome.Failure(DownloadMessages.TRAFFIC_LIMITED_BLOCKED)
            }
            return ResolveOutcome.Failure(DownloadMessages.NO_DOWNLOAD_URL)
        }
        if (link.trafficLimited) {
            logger.w(LogSource.DOWNLOAD, "${DownloadMessages.TRAFFIC_LIMITED}：${source.fileName}")
        }

        val rewritten = DownloadUrlCodec.rewriteDownloadUrl(raw)
        val followed = followRedirects(rewritten)
        val resolved = jsonRedirect(followed) ?: followed
        logger.i(LogSource.DOWNLOAD, "下载地址解析完成：${source.fileName}（主机 ${hostOf(resolved)}）")
        return ResolveOutcome.Success(resolved, link.trafficLimited)
    }

    /**
     * 手动跟随一次重定向（参考源 `resolve_download_url`）：① 3xx 的 `Location`；② body 前 500 字符
     * 的 `href`；③ `download-v2` params 兜底。候选必须通过安全校验，否则拒绝并保留 [url]。
     */
    private suspend fun followRedirects(url: String): String = withContext(Dispatchers.IO) {
        val response = try {
            val request = Request.Builder().url(url).get().build()
            transfer.newCall(request).execute().use { it.snapshot(FOLLOW_BODY_LIMIT) }
        } catch (error: IOException) {
            logger.w(LogSource.DOWNLOAD, "跟随下载重定向失败：${error.message.orEmpty()}")
            return@withContext url
        }

        val location = if (response.code in REDIRECT_CODES) response.location else null
        if (!location.isNullOrBlank()) {
            return@withContext safeOrOriginal(location, url, "重定向 Location")
        }
        val href = DownloadUrlCodec.extractHref(response.body)
        if (href != null) {
            return@withContext safeOrOriginal(href, url, "HTML href")
        }
        val decoded = DownloadUrlCodec.decodeDownloadV2Params(url)
        if (decoded.isNotEmpty() && DownloadUrlCodec.isSafeDownloadUrl(decoded)) {
            logger.i(LogSource.DOWNLOAD, "下载地址已通过 download-v2 params 解码（主机 ${hostOf(decoded)}）")
            return@withContext decoded
        }
        url
    }

    /**
     * JSON 重定向预检（参考源 `download_engine.py#_resolve_json_redirect_url` / `_check_json_redirect`）：
     * 用 `Range: bytes=0-0` 探测；`Content-Type` 含 `json` 且 `code==0` 且
     * `data.RedirectUrl|redirect_url` 安全时改用该链接，否则返回 null（保留原 URL）。
     */
    private suspend fun jsonRedirect(url: String): String? = withContext(Dispatchers.IO) {
        val response = try {
            val request = Request.Builder().url(url).get().header("Range", "bytes=0-0").build()
            transfer.newCall(request).execute().use { it.snapshot(JSON_BODY_LIMIT) }
        } catch (error: IOException) {
            logger.w(LogSource.DOWNLOAD, "JSON 重定向预检失败：${error.message.orEmpty()}")
            return@withContext null
        }
        if (!response.contentType.contains("json", ignoreCase = true)) return@withContext null

        val root = runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
            ?: return@withContext null
        if (root.longOf("code") != 0L) return@withContext null
        val data = root["data"] as? JsonObject ?: return@withContext null
        val redirect = data.stringOf("RedirectUrl", "redirect_url")
        if (redirect.isNotBlank() && DownloadUrlCodec.isSafeDownloadUrl(redirect)) {
            logger.i(LogSource.DOWNLOAD, "CDN 返回 JSON 重定向，已切换到真实链接（主机 ${hostOf(redirect)}）")
            return@withContext redirect
        }
        null
    }

    private fun safeOrOriginal(candidate: String, original: String, kind: String): String =
        if (DownloadUrlCodec.isSafeDownloadUrl(candidate)) {
            logger.i(LogSource.DOWNLOAD, "下载地址已通过 $kind 解析（主机 ${hostOf(candidate)}）")
            candidate
        } else {
            logger.w(LogSource.DOWNLOAD, "拒绝不可信的 $kind（主机 ${hostOf(candidate)}），保留原地址")
            original
        }

    /** 只记录 host，绝不记录 signed URL / query。 */
    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { "未知主机" }

    /** 读取应答快照；body 用 [Response.peekBody] 截断，避免把整个文件读入内存。 */
    private fun Response.snapshot(limit: Long) = HttpResponseSnapshot(
        code = code,
        location = header("Location"),
        contentType = header("Content-Type").orEmpty(),
        body = peekBody(limit).string(),
    )

    private class HttpResponseSnapshot(
        val code: Int,
        val location: String?,
        val contentType: String,
        val body: String,
    )

    private fun JsonObject.stringOf(vararg keys: String): String {
        for (key in keys) {
            val value = this[key] ?: continue
            if (value is JsonNull) continue
            (value as? JsonPrimitive)?.let { return it.content }
        }
        return ""
    }

    private fun JsonObject.longOf(vararg keys: String): Long? {
        for (key in keys) {
            (this[key] as? JsonPrimitive)?.longOrNull?.let { return it }
        }
        return null
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val FOLLOW_BODY_LIMIT = 500L
        const val JSON_BODY_LIMIT = 64L * 1024L
    }
}
