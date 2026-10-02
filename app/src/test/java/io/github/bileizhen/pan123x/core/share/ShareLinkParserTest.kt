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

    @Test fun recognizesReportedMobileShareAndPreservesItsRouteAndPassword() {
        val link = ShareLinkParser.parse(reportedMobileShare)!!
        assertEquals("https://1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh?pwd=kkaE", link.url)
        assertEquals("kkaE", link.password)
        assertFalse(link.url.contains("pendingAction"))
    }

    @Test fun mobileShareWorksInCopiedMessagesAndMarkdownLinks() {
        for (text in listOf("分享文件：$reportedMobileShare", "[分享]($reportedMobileShare)", reportedMobileShare.replace("&", "\\&"))) {
            assertEquals("kkaE", ShareLinkParser.parse(text)?.password)
        }
    }

    @Test fun acceptsMobileShareWithoutSchemeAndSeparateExtractionCode() {
        val link = ShareLinkParser.parse("1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh 提取码：aB12")!!
        assertEquals("https://1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh?pwd=aB12", link.url)
        assertEquals("aB12", link.password)
        assertEquals("https://mshare.123pan.cn/123pan/abc-def", ShareLinkParser.parse("http://mshare.123pan.cn/123pan/abc-def")?.url)
    }

    @Test fun rejectsSpoofedMobileHostsAndUnrelatedPaths() {
        for (text in listOf(
            "https://1838272570.mshare.123pan.cn.evil.com/123pan/abc-def",
            "https://evil.com/1838272570.mshare.123pan.cn/123pan/abc-def",
            "https://evil.com@1838272570.mshare.123pan.cn/123pan/abc-def",
            "https://evil.1838272570.mshare.123pan.cn/123pan/abc-def",
            "https://evil.mshare.123pan.cn/123pan/abc-def",
            "https://1838272570.mshare.123pan.cn:8080/123pan/abc-def",
            "https://1838272570.mshare.123pan.cn/wx-app-login.html",
            "https://1838272570.mshare.123pan.cn/s/abc-def",
            "https://1838272570.mshare.123pan.cn/123pan/abc-def/other",
            "https://www.123pan.cn/123pan/abc-def"
        )) assertNull(text, ShareLinkParser.parse(text))
    }

    @Test fun mobileQueryPasswordIsValidatedAndPreferredOverMessageCode() {
        assertEquals("kkaE", ShareLinkParser.parse("$reportedMobileShare 提取码：1234")?.password)
        for (invalid in listOf("123456", "a%2Bb1", "%3Cbad%3E")) {
            assertEquals("", ShareLinkParser.parse("https://123.mshare.123pan.cn/123pan/abc-def?pwd=$invalid")?.password)
        }
        assertNull(ShareLinkParser.parse("https://123.mshare.123pan.cn/123pan/abc-def?pwd=%ZZ"))
        assertEquals("aB12", ShareLinkParser.parse("https://123.mshare.123pan.cn/123pan/abc-def?pwd=%61B12")?.password)
    }

    @Test fun mobileShareDeduplicatesTrackingAndPasswordFormatting() {
        val gate = ShareClipboardGate()
        assertNotNull(gate.take(reportedMobileShare))
        assertNull(gate.take("https://1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh 提取码：kkaE"))
        assertNull(gate.take(reportedMobileShare.replace("TRANSFER_SAVE_ALL", "VIEW")))
        assertNotNull(gate.take(reportedMobileShare.replace("kkaE", "1234")))
    }

    private val reportedMobileShare = "https://1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh?notoken=1&pwd=kkaE&pendingAction=TRANSFER_SAVE_ALL"
}
