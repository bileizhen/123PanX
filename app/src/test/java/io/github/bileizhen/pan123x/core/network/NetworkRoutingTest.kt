package io.github.bileizhen.pan123x.core.network

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NetworkRoutingTest {
    @Test fun systemRouteAndBypassActuallyReachDifferentServers() {
        MockWebServer().use { proxy -> MockWebServer().use { origin ->
            proxy.enqueue(MockResponse().setBody("system")); origin.enqueue(MockResponse().setBody("bypassed"))
            var config = ProxyConfig().withMode(ProxyMode.SYSTEM)
            val endpoint = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxy.hostName, proxy.port))
            val client = NetworkRouting({ config }, systemProxy = { endpoint }, autoProxy = { error("auto consulted") }).apply(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(origin.url("/one")).build()).execute().use { assertEquals("system", it.body!!.string()) }
            assertNull(proxy.takeRequest().getHeader("Proxy-Authorization"))
            config = config.copy(bypass = "<local>")
            client.newCall(Request.Builder().url(origin.url("/two")).build()).execute().use { assertEquals("bypassed", it.body!!.string()) }
            assertNull(origin.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals(1, proxy.requestCount)
        } }
    }

    @Test fun socksBypassUsesNormalDnsAndDoesNotConnectToTheProxy() {
        MockWebServer().use { origin ->
            origin.enqueue(MockResponse().setBody("direct"))
            val config = ProxyConfig(true, "SOCKS5", "unreachable.invalid", 1080, bypass = "<local>")
            val client = NetworkRouting({ config }).apply(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(origin.url("/file")).build()).execute().use { assertEquals("direct", it.body!!.string()) }
        }
    }

    @Test fun authenticatedRedirectToBypassDoesNotLeakProxyCredentialsToOrigin() {
        MockWebServer().use { proxy -> MockWebServer().use { origin ->
            origin.enqueue(MockResponse().setBody("direct"))
            proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy"))
            proxy.enqueue(MockResponse().setResponseCode(302).addHeader("Location", origin.url("/redirect")))
            val config = ProxyConfig(true, "HTTP", proxy.hostName, proxy.port, "user", "secret", bypass = "<local>")
            val client = NetworkRouting({ config }).apply(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url("http://remote.invalid/start").build()).execute().use { assertEquals("direct", it.body!!.string()) }
            assertNull(proxy.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals(Credentials.basic("user", "secret"), proxy.takeRequest().getHeader("Proxy-Authorization"))
            assertNull(origin.takeRequest().getHeader("Proxy-Authorization"))
        } }
    }

    @Test fun automaticSelectedProxyFailureDoesNotFallBackToOrigin() {
        MockWebServer().use { origin ->
            val closedPort = ServerSocket(0).use { it.localPort }
            val endpoint = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", closedPort))
            val config = ProxyConfig().withMode(ProxyMode.AUTO)
            val client = NetworkRouting({ config }, systemProxy = { null }, autoProxy = { endpoint }).apply(OkHttpClient.Builder().retryOnConnectionFailure(false)).build()
            try { client.newCall(Request.Builder().url(origin.url("/file")).build()).execute().close(); fail("Unexpected direct fallback") }
            catch (_: java.io.IOException) { assertEquals(0, origin.requestCount) }
        }
    }

    @Test fun systemProxyDoesNotReceiveStoredManualCredentials() {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy"))
            val endpoint = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxy.hostName, proxy.port))
            val config = ProxyConfig(true, "HTTP", proxy.hostName, proxy.port, "manual-user", "secret").withMode(ProxyMode.SYSTEM)
            val client = NetworkRouting({ config }, systemProxy = { endpoint }).apply(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url("http://remote.invalid/file").build()).execute().use { assertEquals(407, it.code) }
            assertNull(proxy.takeRequest().getHeader("Proxy-Authorization")); assertEquals(1, proxy.requestCount)
        }
    }

    @Test fun httpAuthenticationAndLiveDisableDoNotLeakOriginHeaders() {
        MockWebServer().use { proxy -> MockWebServer().use { origin ->
            proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy"))
            proxy.enqueue(MockResponse().setBody("proxied"))
            origin.enqueue(MockResponse().setBody("direct"))
            var config = ProxyConfig(true, "HTTP", proxy.hostName, proxy.port, "proxy-user", "secret")
            val client = NetworkRouting({ config }).apply(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url(origin.url("/file")).build()).execute().use { assertEquals("proxied", it.body!!.string()) }
            val first = proxy.takeRequest(2, TimeUnit.SECONDS)!!
            val authenticated = proxy.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(first.getHeader("Proxy-Authorization"))
            assertEquals(Credentials.basic("proxy-user", "secret"), authenticated.getHeader("Proxy-Authorization"))
            assertNull(authenticated.getHeader("authorization")); assertNull(authenticated.getHeader("loginuuid"))
            config = config.copy(enabled = false)
            client.newCall(Request.Builder().url(origin.url("/file")).build()).execute().use { assertEquals("direct", it.body!!.string()) }
            assertNull(origin.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
            assertEquals(2, proxy.requestCount)
        } }
    }

    @Test fun failedHttpAuthenticationIsBounded() {
        MockWebServer().use { proxy ->
            repeat(2) { proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=proxy")) }
            val config = ProxyConfig(true, "HTTP", proxy.hostName, proxy.port, "user", "wrong")
            val client = NetworkRouting({ config }).apply(OkHttpClient.Builder()).build()
            client.newCall(Request.Builder().url("http://unresolvable.invalid/file").build()).execute().use { assertEquals(407, it.code) }
            assertEquals(2, proxy.requestCount)
        }
    }

    @Test fun socksAnonymousAndAuthenticatedUseRemoteDns() {
        for (auth in listOf(false, true)) socksExchange(auth, rejectAuth = false)
    }

    @Test fun socksBadPasswordFailsWithoutDirectFallback() { socksExchange(auth = true, rejectAuth = true) }

    private fun socksExchange(auth: Boolean, rejectAuth: Boolean) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val host = java.util.concurrent.atomic.AtomicReference<String>()
            val user = java.util.concurrent.atomic.AtomicReference<String>()
            val password = java.util.concurrent.atomic.AtomicReference<String>()
            val handled = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream(); val output = socket.getOutputStream()
                    fun byte(): Int = input.read().also { check(it >= 0) }
                    fun bytes(size: Int) = ByteArray(size) { byte().toByte() }
                    assertEquals(5, byte()); val methods = bytes(byte())
                    assertTrue(methods.contains(if (auth) 2.toByte() else 0.toByte()))
                    output.write(byteArrayOf(5, if (auth) 2 else 0)); output.flush()
                    if (auth) {
                        assertEquals(1, byte()); user.set(bytes(byte()).toString(Charsets.UTF_8)); password.set(bytes(byte()).toString(Charsets.UTF_8))
                        output.write(byteArrayOf(1, if (rejectAuth) 1 else 0)); output.flush()
                        if (rejectAuth) return@submit
                    }
                    assertEquals(5, byte()); assertEquals(1, byte()); assertEquals(0, byte()); assertEquals(3, byte())
                    host.set(bytes(byte()).toString(Charsets.UTF_8)); assertEquals(80, (byte() shl 8) + byte())
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); output.flush()
                    var last = ""
                    while (!last.endsWith("\r\n\r\n")) { last += byte().toChar(); check(last.length < 4096) }
                    assertFalse(last.contains("Proxy-Authorization", true)); assertFalse(last.contains("loginuuid", true))
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()); output.flush()
                }
            }
            try {
                val config = ProxyConfig(true, "SOCKS5", "127.0.0.1", server.localPort, if (auth) "用户名" else "", if (auth) "password" else "")
                val client = NetworkRouting({ config }).apply(OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false)).build()
                try {
                    client.newCall(Request.Builder().url("http://remote-dns-only.invalid/data").build()).execute().use { assertEquals("ok", it.body!!.string()) }
                    assertFalse(rejectAuth)
                } catch (error: java.io.IOException) { if (!rejectAuth) throw error; assertFalse(error.message.orEmpty().contains("password")) }
                handled.get(5, TimeUnit.SECONDS)
                if (!rejectAuth) assertEquals("remote-dns-only.invalid", host.get())
                if (auth) { assertEquals("用户名", user.get()); assertEquals("password", password.get()) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun corruptedOrUnreadProxyConfigurationFailsClosed() {
        val client = NetworkRouting({ ProxyConfig() }, ready = { false }).apply(OkHttpClient.Builder()).build()
        try { client.newCall(Request.Builder().url("http://127.0.0.1:9").build()).execute(); fail() }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("代理设置")) }
    }

    @Test fun validationRejectsBadEndpointsAndToStringHidesCredentials() {
        assertNotNull(ProxyConfig(true, host = "http://127.0.0.1", port = 80).validationError())
        assertNotNull(ProxyConfig(true, host = "localhost", port = 65536).validationError())
        assertNotNull(ProxyConfig(true, host = "localhost", port = 8080, username = "bad\r\nheader").validationError())
        val config = ProxyConfig(true, "SOCKS5", "::1", 1080, "private-user", "private-password")
        assertNull(config.validationError()); assertFalse(config.toString().contains("private"))
    }

    @Test fun clientSimulationSwitchUsesOnlyVerifiedWebHeaders() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("ok")) }
            var simulation = true
            val device = DeviceProfile.generate()
            val client = PanHttpClientFactory.defaultClient({ device }, AuthorizationProvider { "Bearer test" }, { simulation })
            client.newCall(Request.Builder().url(server.url("/api")).build()).execute().close()
            assertEquals("android", server.takeRequest().getHeader("platform"))
            simulation = false
            client.newCall(Request.Builder().url(server.url("/api")).build()).execute().close()
            val web = server.takeRequest()
            assertEquals("web", web.getHeader("platform")); assertEquals("3", web.getHeader("app-version"))
            for (header in listOf("devicename", "x-app-version", "osversion", "devicetype")) assertNull(web.getHeader(header))
            assertEquals(device.loginUuid, web.getHeader("loginuuid")); assertEquals("Bearer test", web.getHeader("authorization"))
            assertTrue(web.getHeader("user-agent")!!.contains("Chrome/140.0.0.0"))
        }
    }
}
