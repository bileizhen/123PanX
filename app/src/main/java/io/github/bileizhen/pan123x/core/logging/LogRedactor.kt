package io.github.bileizhen.pan123x.core.logging

import java.net.URI

/** All log inputs pass through this boundary before retention or display. */
object LogRedactor {
    private const val HIDDEN = "[已隐藏]"
    private const val KEYS = "authorization|proxy-authorization|cookie|set-cookie|password|passwd|pwd|share[_-]?pwd|share[_-]?password|提取码|提取密码|密码|token|access[_-]?token|refresh[_-]?token|login[_-]?uuid|(?:client[_-]?)?secret|x-amz-signature|x-amz-credential|x-amz-security-token"
    // Drop the rest of a sensitive line rather than trying to parse arbitrary/malformed JSON,
    // a Cookie's multiple values, authorization schemes, or a password containing spaces.
    private val credentialTail = Regex("(?im)([\"']?(?<![A-Za-z0-9_])(?:$KEYS)[\"']?\\s*[:：=]\\s*)[^\\r\\n]+")
    private val bearer = Regex("(?i)\\bBearer\\s+[^\\s,;\"'}]+")
    private val url = Regex("(?i)https?://[^\\s<>\"']+")
    private val query = Regex("\\?[^\\s<>\"']+")

    fun redact(message: String): String {
        var safe = credentialTail.replace(message) { "${it.groupValues[1]}$HIDDEN" }
        safe = bearer.replace(safe, "Bearer $HIDDEN")
        safe = url.replace(safe) { match ->
            val host = runCatching { URI(match.value).host }.getOrNull()
            if (host.isNullOrBlank()) "[地址已隐藏]" else "https://$host/[路径已隐藏]"
        }
        return query.replace(safe, "?[参数已隐藏]")
    }
}
