package io.github.bileizhen.pan123x.core.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ProxyPolicyTest {
    private val url = URI("https://files.example.com/data")
    private val http = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7890))
    private val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 1080))
    @Test fun oldEnabledConfigurationsKeepTheirPreviousRouting() {
        assertEquals(ProxyMode.NONE, ProxyConfig().effectiveMode)
        assertEquals(ProxyMode.MANUAL, ProxyConfig(enabled = true).effectiveMode)
        assertNotNull(ProxyConfig(enabled = true).validationError())
        assertNotNull(ProxyConfig(mode = "unknown").validationError())
    }
    @Test fun noneNeverConsultsSystemOrDetection() {
        assertNull(ProxyPolicy.resolve(ProxyConfig().withMode(ProxyMode.NONE), url, { error("system consulted") }, { error("detector consulted") }))
    }
    @Test fun manualIgnoresSystemAndPreservesProtocol() {
        val config = ProxyConfig(true, "SOCKS5", "proxy.example", 1080)
        val route = ProxyPolicy.resolve(config, url, { error("system consulted") }, { error("detector consulted") })!!
        assertEquals(Proxy.Type.SOCKS, route.type())
        assertEquals("proxy.example", (route.address() as InetSocketAddress).hostString)
    }
    @Test fun systemUsesOnlyPlatformChoiceAndAllowsDirectWhenUnset() {
        val config = ProxyConfig().withMode(ProxyMode.SYSTEM)
        assertEquals(http, ProxyPolicy.resolve(config, url, { http }, { error("detector consulted") }))
        assertNull(ProxyPolicy.resolve(config, url, { null }, { error("detector consulted") }))
    }
    @Test fun automaticPrefersSystemThenDetectionThenDirect() {
        val config = ProxyConfig().withMode(ProxyMode.AUTO)
        assertEquals(http, ProxyPolicy.resolve(config, url, { http }, { error("detector consulted") }))
        assertEquals(socks, ProxyPolicy.resolve(config, url, { null }, { socks }))
        assertEquals(socks, ProxyPolicy.resolve(config, url, { Proxy.NO_PROXY }, { socks }))
        assertNull(ProxyPolicy.resolve(config, url, { null }, { null }))
    }
    @Test fun bypassPrecedesEveryProxyModeAndMatchesDomainBoundaries() {
        for (mode in ProxyMode.entries) {
            val config = ProxyConfig(true, host = "proxy.example", port = 8080, bypass = ".EXAMPLE.com; localhost").withMode(mode)
            assertNull(ProxyPolicy.resolve(config, url, { error("system consulted") }, { error("detector consulted") }))
        }
        assertTrue(ProxyBypass.matches("*.example.com", "EXAMPLE.COM."))
        assertTrue(ProxyBypass.matches("example.com", "sub.example.com"))
        assertFalse(ProxyBypass.matches("example.com", "evil-example.com"))
        assertFalse(ProxyBypass.matches("example.com", "example.com.evil.org"))
        assertTrue(ProxyBypass.matches("*", "anywhere.invalid"))
    }
    @Test fun localRulesDoNotResolvePublicDomainsOrMatchLookalikeAddresses() {
        for (host in listOf("localhost", "printer", "127.0.0.1", "10.2.3.4", "192.168.1.1", "172.16.1.2", "172.31.2.3", "169.254.1.2", "::1", "[fd00::1]", "fe80::1"))
            assertTrue(host, ProxyBypass.matches("<local>", host))
        for (host in listOf("192.168.evil.org", "172.32.1.2", "169.255.1.2", "8.8.8.8", "public.invalid", "2001:db8::1"))
            assertFalse(host, ProxyBypass.matches("<local>", host))
    }
    @Test fun bypassValidationRejectsUrlsAndOverlongRules() {
        assertNotNull(ProxyConfig(bypass = "https://example.com/path").validationError())
        assertNotNull(ProxyConfig(bypass = "a".repeat(4097)).validationError())
        assertNull(ProxyConfig(bypass = "example.com, *.example.org <local> ::1 *").validationError())
    }
}
