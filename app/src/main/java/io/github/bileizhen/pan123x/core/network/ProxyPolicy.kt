// SPDX-License-Identifier: GPL-3.0-only
// Modes and domain/local bypass behavior adapted from LeiFetch Proxy.kt.
package io.github.bileizhen.pan123x.core.network

import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlinx.serialization.Serializable

enum class ProxyMode(val label: String, val summary: String) {
    NONE("不使用代理", "所有网络请求直连，忽略系统代理"),
    SYSTEM("系统代理", "跟随 Wi-Fi 或 VPN 应用写入的系统代理"),
    MANUAL("手动配置", "指定代理服务器，可设账号密码与直连名单"),
    AUTO("自动（推荐）", "优先系统代理，其次探测本机常见代理端口，都不可用时直连"),
}

/** Corresponds to 123pan Session.set_proxy_auth; secrets never appear in diagnostics. */
@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: String = "HTTP",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** Empty mode keeps old encrypted enabled=true/false configurations compatible. */
    val mode: String = "",
    val bypass: String = "",
) {
    val effectiveMode: ProxyMode get() = if (mode.isEmpty()) {
        if (enabled) ProxyMode.MANUAL else ProxyMode.NONE
    } else ProxyMode.entries.firstOrNull { it.name == mode } ?: ProxyMode.NONE

    fun withMode(next: ProxyMode) = copy(mode = next.name, enabled = next == ProxyMode.MANUAL)
    override fun toString() = "ProxyConfig(mode=$effectiveMode, type=$type, credentials=[redacted])"

    fun validationError(): String? = when {
        mode.isNotEmpty() && ProxyMode.entries.none { it.name == mode } -> "请选择有效的代理模式"
        bypass.length > 4096 || ProxyBypass.entries(bypass).size > 128 -> "直连名单过长"
        ProxyBypass.entries(bypass).any { !ProxyBypass.valid(it) } -> "直连名单仅支持域名、IP、* 和 <local>"
        type !in listOf("HTTP", "SOCKS5") -> "请选择 HTTP 或 SOCKS5 代理"
        effectiveMode == ProxyMode.MANUAL && (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() || it in "/\\@?#[]" } ||
            (host.contains(':') && !host.matches(Regex("[0-9a-fA-F:]+")))) -> "请输入代理主机，不要包含协议、路径或认证信息"
        effectiveMode == ProxyMode.MANUAL && port !in 1..65535 -> "代理端口必须为 1–65535"
        username.any { it == '\r' || it == '\n' } || password.any { it == '\r' || it == '\n' } -> "代理认证信息不能包含换行"
        username.toByteArray(Charsets.UTF_8).size > 255 || password.toByteArray(Charsets.UTF_8).size > 255 -> "代理用户名或密码过长"
        effectiveMode == ProxyMode.MANUAL && password.isNotEmpty() && username.isBlank() -> "使用代理密码时请填写用户名"
        effectiveMode == ProxyMode.MANUAL && type == "SOCKS5" && username.isNotBlank() && password.isEmpty() -> "SOCKS5 认证需要同时填写用户名和密码"
        else -> null
    }
}

/** Pure host matching; never resolves DNS to decide whether a name is local. */
object ProxyBypass {
    fun entries(raw: String): List<String> = raw.split(Regex("[\\s,;]+"))
        .map { it.trim().removePrefix("*.").trimStart('.').lowercase() }.filter(String::isNotEmpty).distinct()

    private fun normalized(raw: String) = raw.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
    fun valid(entry: String): Boolean {
        if (entry in listOf("*", "<local>")) return true
        val host = normalized(entry)
        if (host.contains(':')) return host.matches(Regex("[0-9a-f:]+")) && runCatching { InetAddress.getByName(host).address.size == 16 }.getOrDefault(false)
        return runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).length in 1..253 }.getOrDefault(false)
    }
    fun matches(raw: String, destination: String): Boolean {
        val host = normalized(destination)
        if (host.isBlank()) return false
        return entries(raw).any { entry ->
            val rule = normalized(entry)
            rule == "*" || (rule == "<local>" && local(host)) || host == rule || host.endsWith(".$rule")
        }
    }
    private fun local(host: String): Boolean {
        if (host == "localhost" || (!host.contains('.') && !host.contains(':'))) return true
        if (host.contains(':') && host.matches(Regex("[0-9a-f:]+"))) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
        }
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        return octets[0] == 127 || octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254) || (octets[0] == 172 && octets[1]!! in 16..31)
    }
}

/** The manual endpoint is never silently replaced with a direct route on failure. */
internal object ProxyPolicy {
    fun resolve(config: ProxyConfig, uri: URI, system: (URI) -> Proxy?, auto: () -> Proxy?): Proxy? {
        if (ProxyBypass.matches(config.bypass, uri.host.orEmpty())) return null
        return when (config.effectiveMode) {
            ProxyMode.NONE -> null
            ProxyMode.MANUAL -> Proxy(if (config.type == "SOCKS5") Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(config.host, config.port))
            ProxyMode.SYSTEM -> system(uri)
            ProxyMode.AUTO -> system(uri)?.takeUnless { it.type() == Proxy.Type.DIRECT } ?: auto()
        }?.takeUnless { it.type() == Proxy.Type.DIRECT }
    }
}
