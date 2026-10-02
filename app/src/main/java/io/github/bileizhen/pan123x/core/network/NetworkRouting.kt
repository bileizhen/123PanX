package io.github.bileizhen.pan123x.core.network

import java.io.IOException
import java.io.InputStream
import java.net.*
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Routing applies to both independent clients, without adding origin credentials to transfers.
 * Idle connections are closed so a proxy change cannot reuse an old direct or proxy connection.
 * Active requests finish on their existing route; the next request uses the saved configuration.
 * SOCKS authentication is scoped to its socket; no process-wide JVM Authenticator is installed.
 */
class NetworkRouting(
    private val config: () -> ProxyConfig,
    private val ready: () -> Boolean = { true },
    private val systemProxy: (URI) -> Proxy? = { uri -> ProxySelector.getDefault()?.select(uri)?.firstOrNull()?.takeUnless { it.type() == Proxy.Type.DIRECT } },
    private val autoProxy: () -> Proxy? = LocalProxyDetector()::detect,
) {
    private data class Selection(val destination: String, val proxy: Proxy?, val credentials: ProxyConfig?)

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        // OkHttp calls its selector, DNS and socket factory on the exchange thread. Keep one
        // selection through those steps, then discard it; redirects select their own host again.
        val selected = ThreadLocal<Selection>()
        return builder
            .connectionPool(ConnectionPool(0, 5, TimeUnit.SECONDS))
            .addInterceptor { chain ->
                if (!ready()) throw IOException("代理设置尚未读取或已损坏，请在设置中重新保存")
                config().validationError()?.let { throw IOException(it) }
                try { chain.proceed(chain.request()) } finally { selected.remove() }
            }
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> {
                    requireNotNull(uri)
                    if (!ready()) throw IOException("代理设置尚未读取或已损坏，请在设置中重新保存")
                    val snapshot = config()
                    snapshot.validationError()?.let { throw IOException(it) }
                    val route = ProxyPolicy.resolve(snapshot, uri, systemProxy, autoProxy)
                    val credentials = if (snapshot.effectiveMode == ProxyMode.MANUAL) snapshot else null
                    selected.set(Selection(uri.host.orEmpty(), route, credentials))
                    // Custom SOCKS sockets retain RFC1929 authentication without a global Authenticator.
                    return listOf(if (route?.type() == Proxy.Type.HTTP) route else Proxy.NO_PROXY)
                }
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            })
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket {
                    val selection = selected.get()
                    val route = selection?.proxy
                    val address = route?.address() as? InetSocketAddress
                    return if (route?.type() == Proxy.Type.SOCKS && address != null) {
                        Socks5Socket(ProxyConfig(true, "SOCKS5", address.hostString, address.port,
                            selection?.credentials?.username.orEmpty(), selection?.credentials?.password.orEmpty()))
                    } else Socket()
                }
                override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
                override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
                override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
                override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host.hostAddress!!, port, local, localPort)
            })
            .dns(object : Dns {
              override fun lookup(hostname: String): List<InetAddress> {
                val selection = selected.get()
                return if (selection?.proxy?.type() == Proxy.Type.SOCKS && hostname.equals(selection.destination, true)) {
                    listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
                } else Dns.SYSTEM.lookup(hostname)
              }
            })
            .addNetworkInterceptor { chain ->
                val selection = selected.get()
                val actual = chain.connection()?.route()?.proxy
                val expectedAddress = selection?.proxy?.address() as? InetSocketAddress
                val actualAddress = actual?.address() as? InetSocketAddress
                val manualHttp = selection?.credentials?.username?.isNotBlank() == true && actual?.type() == Proxy.Type.HTTP &&
                    expectedAddress != null && actualAddress != null && expectedAddress.hostString.equals(actualAddress.hostString, true) && expectedAddress.port == actualAddress.port
                // Authenticator follow-ups can survive a redirect. Never forward proxy secrets
                // to a bypassed origin or to a different/system proxy.
                // HTTPS uses proxy credentials only for CONNECT, never inside the origin's tunnel.
                val request = if (!manualHttp || chain.request().isHttps) chain.request().newBuilder().removeHeader("Proxy-Authorization").build() else chain.request()
                chain.proceed(request)
            }
            .proxyAuthenticator { route, response ->
                val selection = selected.get()
                val credentials = selection?.credentials
                val expected = selection?.proxy?.address() as? InetSocketAddress
                val actual = route?.proxy?.address() as? InetSocketAddress
                if (credentials == null || credentials.username.isBlank() || route?.proxy?.type() != Proxy.Type.HTTP ||
                    expected == null || actual == null || !actual.hostString.equals(expected.hostString, true) || actual.port != expected.port ||
                    response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(credentials.username, credentials.password)).build()
            }
    }
}

/** RFC 1928/1929 handshake over a real Socket, compatible with Android Conscrypt TLS wrapping. */
internal class Socks5Socket(private val proxy: ProxyConfig) : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val target = endpoint as? InetSocketAddress ?: throw IOException("无效代理目标")
        val originalTimeout = soTimeout
        try {
            super.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
            soTimeout = if (timeout > 0) timeout else 5_000
            val input = getInputStream()
            val output = getOutputStream()
            val authenticated = proxy.username.isNotBlank()
            output.write(if (authenticated) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0)); output.flush()
            if (input.byte() != 5) throw IOException("SOCKS5 代理响应无效")
            when (input.byte()) {
                0 -> Unit
                2 -> {
                    if (!authenticated) throw IOException("SOCKS5 代理需要用户名和密码")
                    val user = proxy.username.toByteArray(Charsets.UTF_8)
                    val password = proxy.password.toByteArray(Charsets.UTF_8)
                    output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
                    output.write(password.size); output.write(password); output.flush()
                    if (input.byte() != 1 || input.byte() != 0) throw IOException("SOCKS5 代理认证失败")
                }
                else -> throw IOException("SOCKS5 代理不支持所配置的认证方式")
            }
            if (target.hostString.contains(':')) {
                val host = InetAddress.getByName(target.hostString).address
                if (host.size != 16) throw IOException("无效代理目标")
                output.write(byteArrayOf(5, 1, 0, 4)); output.write(host)
            } else {
                val host = IDN.toASCII(target.hostString).toByteArray(Charsets.UTF_8)
                if (host.isEmpty() || host.size > 255) throw IOException("无效代理目标")
                output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte())); output.write(host)
            }
            output.write(target.port shr 8); output.write(target.port and 255); output.flush()
            if (input.byte() != 5 || input.byte() != 0 || input.byte() != 0) throw IOException("SOCKS5 代理无法连接目标服务器")
            val addressSize = when (input.byte()) { 1 -> 4; 3 -> input.byte(); 4 -> 16; else -> throw IOException("SOCKS5 代理响应无效") }
            repeat(addressSize + 2) { input.byte() }
            soTimeout = originalTimeout
        } catch (error: Exception) {
            try { close() } catch (closeError: IOException) { error.addSuppressed(closeError) }
            throw IOException("SOCKS5 连接失败，请检查代理地址和认证信息", error)
        }
    }

    private fun InputStream.byte(): Int = read().also { if (it < 0) throw IOException("SOCKS5 代理提前关闭连接") }
}
