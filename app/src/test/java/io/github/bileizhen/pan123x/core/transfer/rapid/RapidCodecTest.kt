package io.github.bileizhen.pan123x.core.transfer.rapid

import java.math.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RapidCodec 编解码回归（秒传事实 / ，：纯 JVM）。
 *
 * 逐条对齐协议真源 `.reference/123pan` `offline_service.py`：
 * - parse：JSON 三态（正常 / base62 / 坏条目过滤）、123FLCPV2 带 / 不带 commonPath、
 *   旧版多行、非法前缀 / 缺 `%` / 空输入 / 全无效 → IAE 中文文案（对齐 ValueError）；
 * - base62ToHex 已知向量与非法字符；
 * - export → parse 往返等价；`_common_path` 共同一级 / 多级 / 无公共。
 */
class RapidCodecTest {

    private companion object {
        const val ETAG_A = "abcdef0123456789abcdef0123456789"
        const val ETAG_B = "0123456789abcdef0123456789abcdef"
        val BASE62_ALL_ZEROS = "0".repeat(32)
    }

    // ------------------------------------------------------------------
    // parse —— JSON 形态（offline_service.py:121-151）
    // ------------------------------------------------------------------

    @Test
    fun parseJsonNormalLowercasesEtagsAndJoinsCommonPath() {
        val text = """
            {"scriptVersion":"3.0.3","exportVersion":"1.0","usesBase62EtagsInExport":false,
             "commonPath":"old/",
             "files":[{"path":"a.txt","etag":"ABCDEF0123456789ABCDEF0123456789","size":5},
                      {"path":"sub/b.txt","etag":"$ETAG_B","size":6}]}
        """.trimIndent()

        assertEquals(
            listOf(
                RapidFile("old/a.txt", ETAG_A, 5),
                RapidFile("old/sub/b.txt", ETAG_B, 6),
            ),
            RapidCodec.parse(text),
        )
    }

    @Test
    fun parseJsonBase62ConvertsEtags() {
        val text = """{"usesBase62EtagsInExport":true,"files":[{"path":"x.bin","etag":"0","size":9}]}"""

        assertEquals(listOf(RapidFile("x.bin", BASE62_ALL_ZEROS, 9)), RapidCodec.parse(text))
    }

    @Test
    fun parseJsonSkipsInvalidEntriesAndKeepsValidOnes() {
        // 坏条目：path 缺失 / etag 非 32 位 hex / 非 object 项 / size 非法（对齐 :133-148 容错）
        val text = """
            {"files":[
                {"etag":"$ETAG_A","size":3},
                {"path":"","etag":"$ETAG_A","size":3},
                {"path":"bad.txt","etag":"zz-short","size":3},
                {"path":"ok.txt","etag":"$ETAG_A","size":"12"},
                "not-an-object",
                {"path":"zero.txt","etag":"$ETAG_A","size":null}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                RapidFile("ok.txt", ETAG_A, 12),
                RapidFile("zero.txt", ETAG_A, 0),
            ),
            RapidCodec.parse(text),
        )
    }

    @Test
    fun parseJsonRejectsNonObjectAndMissingFilesWithChineseMessages() {
        assertEquals("JSON 格式无效", parseErrorOf("[1,2,3]"))
        assertEquals("JSON 格式无效", parseErrorOf("123"))
        assertEquals("JSON 中缺少 files 列表", parseErrorOf("""{"commonPath":"x/"}"""))
        assertEquals("JSON 中缺少 files 列表", parseErrorOf("""{"files":[]}"""))
        assertEquals("未解析到有效文件", parseErrorOf("""{"files":[{"path":"a","etag":"zz","size":1}]}"""))
    }

    // ------------------------------------------------------------------
    // parse —— 文本链接形态（offline_service.py:153-197）
    // ------------------------------------------------------------------

    @Test
    fun parseLinkWithCommonPathJoinsAndLowercases() {
        val link = "123FLCPV2\$tv/%ABCDEF0123456789ABCDEF0123456789#123#ep1.mp4\$" +
            "$ETAG_B#5#season 1/ep2.mp4"

        assertEquals(
            listOf(
                RapidFile("tv/ep1.mp4", ETAG_A, 123),
                RapidFile("tv/season 1/ep2.mp4", ETAG_B, 5),
            ),
            RapidCodec.parse(link),
        )
    }

    @Test
    fun parseLinkWithoutCommonPathKeepsRawPaths() {
        val link = "123FLCPV2\$%$ETAG_B#7# lone.txt"

        assertEquals(listOf(RapidFile("lone.txt", ETAG_B, 7)), RapidCodec.parse(link))
    }

    @Test
    fun parseLinkConvertsBase62EtagOf22Chars() {
        val base62 = "7N42dgm5tFLK9N8MT7fHC7" // 2^128-1：22 位 base62 的合法最大值
        val link = "123FLCPV2\$dir%$base62#1#f.bin"

        val parsed = RapidCodec.parse(link)

        assertEquals(1, parsed.size)
        assertEquals(RapidCodec.base62ToHex(base62), parsed.single().etag)
        assertTrue(RapidCodec.isValidEtag(parsed.single().etag))
    }

    @Test
    fun parseLegacyMultilineSkipsBrokenLines() {
        // 旧版无前缀：\r\n / \n 均分行；短行、缺 path、坏 etag 的行跳过（:176-194 容错）
        val text = "garbage-line\r\n$ETAG_A#10#a.txt\n\n$ETAG_B#20#b.txt" +
            "\nZZZZ#30#c.txt\n$ETAG_A##d.txt"

        assertEquals(
            listOf(
                RapidFile("a.txt", ETAG_A, 10),
                RapidFile("b.txt", ETAG_B, 20),
            ),
            RapidCodec.parse(text),
        )
    }

    @Test
    fun parseLinkRejectsBadPrefixAndMissingPercent() {
        assertEquals("不支持的秒传链接前缀", parseErrorOf("123FLCPV1\$dir%etag#1#f"))
        // 无 $：prefix+"$" 恰等于合法前缀（参考源 :164-166 的切分语义），落到 % 校验报"格式无效"。
        assertEquals("秒传链接格式无效", parseErrorOf("123FLCPV2"))
        assertEquals("秒传链接格式无效", parseErrorOf("123FLCPV2\$dir-only"))
        assertEquals("未解析到有效文件", parseErrorOf("123FLCPV2\$dir%short#1#f"))
        assertEquals("未解析到有效文件", parseErrorOf("totally not a link"))
    }

    @Test
    fun parseRejectsEmptyInput() {
        assertEquals("输入为空", parseErrorOf(""))
        assertEquals("输入为空", parseErrorOf("   \n  "))
    }

    // ------------------------------------------------------------------
    // base62ToHex / isValidEtag（offline_service.py:199-212）
    // ------------------------------------------------------------------

    @Test
    fun base62ToHexKnownVectors() {
        assertEquals(BASE62_ALL_ZEROS, RapidCodec.base62ToHex("0"))
        // 参考源 _BASE62_CHARS 小写在前：'z' = 35 → "zz" = 35*62 + 35 = 2205 = 0x89d
        assertEquals("0000000000000000000000000000089d", RapidCodec.base62ToHex("zz"))
        // "1z" = 1*62 + 35 = 97 = 0x61
        assertEquals("00000000000000000000000000000061", RapidCodec.base62ToHex("1z"))
        // 数字字符按字面值：'9' → 0x09
        assertEquals("00000000000000000000000000000009", RapidCodec.base62ToHex("9"))
    }

    @Test
    fun base62ToHexHandlesMaxLinkFormEtag() {
        // 22 位 base62 的合法上限是 2^128-1（MD5 恰 128 位）；62^22-1 需要 33 个 hex，超出
        // etag 语义（isValidEtag=false），故用 2^128-1 的标准 base62 形态验证最大值往返。
        val maxBase62 = "7N42dgm5tFLK9N8MT7fHC7" // 2^128-1
        val hex = RapidCodec.base62ToHex(maxBase62)
        assertEquals("f".repeat(32), hex)
        assertTrue(RapidCodec.isValidEtag(hex))
    }

    @Test(expected = IllegalArgumentException::class)
    fun base62ToHexRejectsIllegalCharacter() {
        RapidCodec.base62ToHex("abc!def")
    }

    @Test
    fun isValidEtagAcceptsOnly32HexChars() {
        assertTrue(RapidCodec.isValidEtag(ETAG_A))
        assertTrue(RapidCodec.isValidEtag("ABCDEF0123456789ABCDEF0123456789"))
        assertFalse(RapidCodec.isValidEtag(""))
        assertFalse(RapidCodec.isValidEtag("abcdef")) // 长度不足
        assertFalse(RapidCodec.isValidEtag("g".repeat(32))) // 非 hex 字符
    }

    // ------------------------------------------------------------------
    // export（offline_service.py:302-358）与往返
    // ------------------------------------------------------------------

    @Test
    fun exportProducesReferenceFieldValuesAndSanitizedLink() {
        val export = RapidCodec.export(
            listOf(RapidFile("tv/ep%1#\$special.txt", ETAG_A, 42)),
        )

        // JSON 字段逐字（:339-350）；commonPath 为推导出的公共前缀，JSON 内 path 保留原字符
        val json = Json.parseToJsonElement(export.jsonText).jsonObject
        assertEquals("3.0.3", json["scriptVersion"]!!.jsonPrimitive.content)
        assertEquals("1.0", json["exportVersion"]!!.jsonPrimitive.content)
        assertEquals(false, json["usesBase62EtagsInExport"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("tv/", json["commonPath"]!!.jsonPrimitive.content)
        assertEquals(1, json["totalFilesCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(42, json["totalSize"]!!.jsonPrimitive.content.toLong())
        assertEquals("ep%1#\$special.txt", json["files"]!!.jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content)
        // 链接：前缀 + commonPath + "%" + etag#size#安全路径；path 已剔除 %#$（:355）
        assertEquals("123FLCPV2\$tv/%$ETAG_A#42#ep1special.txt", export.linkText)
    }

    @Test
    fun exportLinkRoundTripsToEquivalentFiles() {
        val original = listOf(
            RapidFile("a/b/x.txt", ETAG_A, 5),
            RapidFile("a/b/c/y.txt", ETAG_B, 6),
        )

        val export = RapidCodec.export(original)

        assertEquals(original, RapidCodec.parse(export.linkText))
        assertEquals(original, RapidCodec.parse(export.jsonText))
    }

    @Test
    fun exportComputesCommonPathAndTrimsItFromRelativePaths() {
        val export = RapidCodec.export(
            listOf(
                RapidFile("a/b/x.txt", ETAG_A, 5),
                RapidFile("a/b/c/y.txt", ETAG_B, 6),
            ),
        )

        val json = Json.parseToJsonElement(export.jsonText).jsonObject
        assertEquals("a/b/", json["commonPath"]!!.jsonPrimitive.content)
        val files = json["files"]!!.jsonArray
        assertEquals("x.txt", files[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("c/y.txt", files[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertTrue(export.linkText.startsWith("123FLCPV2\$a/b/%"))
    }

    @Test
    fun exportRejectsEmptyInputAndInvalidEtags() {
        assertEquals("没有可生成的文件", exportErrorOf(emptyList()))
        assertEquals(
            "所选文件缺少有效的 etag，无法生成秒传数据",
            exportErrorOf(listOf(RapidFile("a.txt", "not-an-etag", 1))),
        )
    }

    // ------------------------------------------------------------------
    // _common_path 场景（offline_service.py:360-381）
    // ------------------------------------------------------------------

    @Test
    fun commonPathScenarios() {
        // 共同一级
        val oneLevel = RapidCodec.export(
            listOf(RapidFile("folder/a.txt", ETAG_A, 1), RapidFile("folder/b.txt", ETAG_B, 2)),
        )
        assertEquals("folder/", commonPathOf(oneLevel))

        // 多级公共
        val multiLevel = RapidCodec.export(
            listOf(RapidFile("a/b/x.txt", ETAG_A, 1), RapidFile("a/b/c/y.txt", ETAG_B, 2)),
        )
        assertEquals("a/b/", commonPathOf(multiLevel))

        // 无公共目录
        val noCommon = RapidCodec.export(
            listOf(RapidFile("a/x.txt", ETAG_A, 1), RapidFile("b/y.txt", ETAG_B, 2)),
        )
        assertEquals("", commonPathOf(noCommon))

        // 有目录与根级文件混合：公共前缀被打破
        val mixed = RapidCodec.export(
            listOf(RapidFile("a/x.txt", ETAG_A, 1), RapidFile("y.txt", ETAG_B, 2)),
        )
        assertEquals("", commonPathOf(mixed))
    }

    private fun commonPathOf(export: RapidExport): String =
        Json.parseToJsonElement(export.jsonText).jsonObject
            .getValue("commonPath").jsonPrimitive.content

    /** 断言 [text] 解析抛 IAE 并返回其 message。 */
    private fun parseErrorOf(text: String): String =
        catchMessage { RapidCodec.parse(text) }

    private fun exportErrorOf(files: List<RapidFile>): String =
        catchMessage { RapidCodec.export(files) }

    private inline fun catchMessage(block: () -> Unit): String {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            return error.message.orEmpty()
        }
        throw AssertionError("期望抛出 IllegalArgumentException")
    }
}
