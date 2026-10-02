package io.github.bileizhen.pan123x.core.share

import org.junit.Assert.*
import org.junit.Test

class ShareLinkParserTest {
    @Test fun findsLinkAndCodeInFullShareMessage() {
        val link = ShareLinkParser.parse("分享文档：https://www.123pan.cn/s/abc-def 提取码：aB12")!!
        assertEquals("https://www.123pan.cn/s/abc-def", link.url)
        assertEquals("aB12", link.password)
    }
    @Test fun supportsOfficialDomainsAndUpgradesToHttps() {
        for (host in listOf("123pan.cn", "123pan.com", "123684.com", "123865.com", "123912.com")) {
            assertEquals("https://www.$host/s/abc-def", ShareLinkParser.parse("http://www.$host/s/abc-def")?.url)
        }
        assertEquals("https://123pan.com/s/abc-def", ShareLinkParser.parse("123pan.com/s/abc-def.html")?.url)
    }
    @Test fun acceptsQueryCodeAndRejectsInvalidCodes() {
        assertEquals("Ab12", ShareLinkParser.parse("https://www.123pan.com/s/abc-def?pwd=Ab12&tracking=123")?.password)
        assertEquals("", ShareLinkParser.parse("https://www.123pan.com/s/abc-def 提取码：123456")?.password)
    }
    @Test fun ignoresOtherLinksAndLookalikeDomains() {
        for (text in listOf("https://evil123pan.com/s/abc-def", "https://evil.com/www.123pan.com/s/abc-def", "https://evil.com@www.123pan.com/s/abc-def", "https://www.123pan.com.evil.com/s/abc-def", "https://www.123pan.com:8080/s/abc-def", "https://www.123pan.com/file/abc-def")) assertNull(text, ShareLinkParser.parse(text))
    }
    @Test fun sameClipIsOnlyOfferedOnceAndOwnLinksAreSkipped() {
        val gate = ShareClipboardGate()
        val text = "https://www.123pan.com/s/abc-def 提取码：1234"
        assertNull(gate.take(text, true))
        assertNotNull(gate.take(text))
        assertNull(gate.take(text))
        assertNotNull(gate.take(text.replace("1234", "4321")))
    }
    @Test fun boundsClipboardInput() { assertNull(ShareLinkParser.parse("x".repeat(33_000))) }
}
