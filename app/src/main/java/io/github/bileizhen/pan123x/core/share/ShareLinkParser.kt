package io.github.bileizhen.pan123x.core.share

import java.net.URI
import java.net.URLDecoder

data class SharedLink(val url: String, val password: String) {
    val text: String get() = if (password.isBlank()) url else "$url\n提取码：$password"
    val identity: String get() = "$url#$password"
}

/** Only known official share pages; never treat an arbitrary CDN URL as a share. */
object ShareLinkParser {
    private val hosts = setOf("123pan.cn", "123pan.com", "123684.com", "123865.com", "123912.com")
    private val links = Regex("(?i)(?:https?://)?(?:www\\.)?123(?:pan\\.(?:cn|com)|684\\.com|865\\.com|912\\.com)/s/[A-Za-z0-9_-]+(?:\\.html)?(?:\\?[^\\s<>\"'，。；）)]+)?")
    private val code = Regex("(?i)(?:提取码|提取密码|访问码|密码|extraction\\s*code|password|code)\\s*[:：=]?\\s*([A-Za-z0-9]{4})(?![A-Za-z0-9])")

    fun parse(text: String): SharedLink? {
        if (text.length > 32_768) return null
        for (match in links.findAll(text)) {
            // Reject a known-looking URL embedded in an attacker host, path, or username.
            if (match.range.first > 0 && text[match.range.first - 1] in ".@/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") continue
            val raw = match.value.let { if (it.startsWith("http", true)) it else "https://$it" }
            val uri = runCatching { URI(raw) }.getOrNull() ?: continue
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: continue
            if (host !in hosts || uri.userInfo != null || uri.port != -1) continue
            val key = uri.path.removePrefix("/s/").removeSuffix(".html")
            if (!key.matches(Regex("[A-Za-z0-9_-]{2,128}"))) continue
            val queryCode = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size != 2 || pair[0].lowercase() !in setOf("pwd", "password", "code")) null
                else runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9]{4}")) }
            }
            val nearby = text.substring(match.range.last + 1).take(160)
            val password = queryCode ?: code.find(nearby)?.groupValues?.get(1).orEmpty()
            return SharedLink("https://${uri.host.lowercase()}/s/$key", password)
        }
        return null
    }
}
