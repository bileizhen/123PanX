package io.github.bileizhen.pan123x.core.transfer.download

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DownloadUrlCodec 纯函数测试（对照参考源 `download_url.py`）。
 * 覆盖：重写两种分支、b64 往返（含 URL-safe）、6 类不安全 URL 拒绝、href 提取、
 * download-v2 解码、以及重写对垃圾输入绝不抛异常。
 */
class DownloadUrlCodecTest {

    @Test
    fun rewriteWrapsNonWebProHostIntoWebPro2Proxy() {
        val original = "https://cdn.example.com/file.zip?sign=abc&t=1"

        val rewritten = DownloadUrlCodec.rewriteDownloadUrl(original)

        assertTrue(rewritten.startsWith("https://web-pro2.123952.com/download-v2/?params="))
        assertTrue(rewritten.endsWith("&is_s3=0"))
        val params = rewritten.substringAfter("params=").substringBefore("&is_s3=0")
        assertEquals(
            "https://cdn.example.com/file.zip?sign=abc&t=1&auto_redirect=0",
            DownloadUrlCodec.decodeParams(params),
        )
    }

    @Test
    fun rewriteRewritesWebProHostInPlace() {
        val inner = "https://cdn.example.com/f.zip?sign=x"
        val input = "https://web-pro.123952.com/download-v2/?params=${DownloadUrlCodec.encodeParams(inner)}&is_s3=0"

        val rewritten = DownloadUrlCodec.rewriteDownloadUrl(input)

        assertTrue(rewritten.startsWith("https://web-pro.123952.com/download-v2/?"))
        assertTrue(rewritten.endsWith("&is_s3=0"))
        val params = rewritten.substringAfter("params=").substringBefore("&is_s3=0")
        assertEquals("https://cdn.example.com/f.zip?sign=x&auto_redirect=0", DownloadUrlCodec.decodeParams(params))
    }

    @Test
    fun webProHostWithoutParamsReturnsOriginal() {
        val input = "https://web-pro.123952.com/download-v2/?is_s3=0"

        assertEquals(input, DownloadUrlCodec.rewriteDownloadUrl(input))
    }

    @Test
    fun base64RoundTripToleratesStandardAndUrlSafe() {
        val text = "https://cdn.example.com/a?b=1&c=2"
        assertEquals(text, DownloadUrlCodec.decodeParams(DownloadUrlCodec.encodeParams(text)))

        // 0x3E x4 → 标准 "Pj4+Pg=="，URL-safe（保留填充）"Pj4-Pg=="
        val raw = byteArrayOf(0x3E, 0x3E, 0x3E, 0x3E)
        val standard = Base64.getEncoder().encodeToString(raw)
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        // 编码保留填充（与参考源 base64.urlsafe_b64encode 一致）；解码兼容去填充输入
        assertEquals("Pj4-Pg==", DownloadUrlCodec.encodeParams(">>>>"))
        assertEquals(">>>>", DownloadUrlCodec.decodeParams(standard))
        assertEquals(">>>>", DownloadUrlCodec.decodeParams(urlSafe))
    }

    @Test
    fun decodeParamsReturnsInputWhenUndecodable() {
        // 参考源 b64_decode 两种解码都失败时返回原串
        assertEquals("!!!not-base64!!!", DownloadUrlCodec.decodeParams("!!!not-base64!!!"))
    }

    @Test
    fun rejectsSixUnsafeUrlShapes() {
        // 1 非 https
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("http://cdn.example.com/f.zip"))
        // 2 无 hostname
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https:///f.zip"))
        // 3 含 userinfo
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https://user@cdn.example.com/f.zip"))
        // 4 localhost
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https://localhost/f.zip"))
        // 5 私有 IP
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https://10.0.0.1/f.zip"))
        // 6 回环 IP
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https://127.0.0.1/f.zip"))
    }

    @Test
    fun acceptsPlainHttpsPublicHost() {
        assertTrue(DownloadUrlCodec.isSafeDownloadUrl("https://cdn.example.com/f.zip"))
    }

    @Test
    fun rejectsLinkLocalAndUnspecifiedAddresses() {
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https://169.254.1.1/f.zip"))
        assertFalse(DownloadUrlCodec.isSafeDownloadUrl("https://0.0.0.0/f.zip"))
    }

    @Test
    fun extractHrefOnlyScansFirst500Chars() {
        val body = "<html><body><a href='https://cdn.example.com/real.zip'>go</a></body></html>"
        assertEquals("https://cdn.example.com/real.zip", DownloadUrlCodec.extractHref(body))

        val beyondWindow = "x".repeat(600) + "href='https://cdn.example.com/late.zip'"
        assertNull(DownloadUrlCodec.extractHref(beyondWindow))
    }

    @Test
    fun decodeDownloadV2ParamsOnlyForDownloadV2Path() {
        val inner = "https://cdn.example.com/f.zip"
        val url = "https://web-pro2.123952.com/download-v2/?params=${DownloadUrlCodec.encodeParams(inner)}&is_s3=0"
        assertEquals(inner, DownloadUrlCodec.decodeDownloadV2Params(url))

        assertEquals("", DownloadUrlCodec.decodeDownloadV2Params("https://cdn.example.com/f.zip?params=abc"))

        val nonHttp = "https://web-pro2.123952.com/download-v2/?params=${DownloadUrlCodec.encodeParams("ftp://x")}"
        assertEquals("", DownloadUrlCodec.decodeDownloadV2Params(nonHttp))
    }

    @Test
    fun rewriteNeverThrowsOnGarbageInput() {
        assertNotNull(DownloadUrlCodec.rewriteDownloadUrl(""))
        assertNotNull(DownloadUrlCodec.rewriteDownloadUrl("::::not a url::::"))
        assertNotNull(DownloadUrlCodec.rewriteDownloadUrl("https://web-pro.example.com/?params=%%%invalid%%%"))
    }
}
