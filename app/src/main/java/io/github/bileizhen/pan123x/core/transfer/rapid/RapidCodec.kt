package io.github.bileizhen.pan123x.core.transfer.rapid

import java.math.BigInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 秒传条目：[path] 为完整路径（导入时可含目录结构），[etag] 为 32 位小写 hex MD5。 */
data class RapidFile(val path: String, val etag: String, val size: Long)

/** 秒传导出结果：[jsonText] 标准 123pan 秒传 JSON；[linkText] 123FLCPV2 文本链接。 */
data class RapidExport(val jsonText: String, val linkText: String)

/**
 * 秒传数据编解码（纯 JVM，无 Android 依赖）。
 *
 * 行为逐条对齐协议真源 `.reference/123pan` `offline_service.py`：
 * - 解析 `parse_rapid_data` :100-197——输入为空 / 全无效 / JSON 结构无效抛
 *   [IllegalArgumentException]（用户可读中文文案，对齐参考源 ValueError）；
 * - JSON 形态 `_parse_rapid_json` :121-151——`files[]` + `commonPath` +
 *   `usesBase62EtagsInExport`，无效项跳过；
 * - 文本形态 `_parse_rapid_link` :153-197——`123FLCPV2$<公共路径>%<etag>#<size>#<path>$...`
 *   前缀校验，兼容旧版无前缀多行 `etag#size#path`；22 位 etag 走 Base62 转换；
 * - etag 校验 / Base62 转换 `_is_valid_etag` / `_base62_to_hex` :199-212；
 * - 导出 `build_rapid_payload` :302-358——`scriptVersion` "3.0.3"、`exportVersion` "1.0"、
 *   `usesBase62EtagsInExport` false、path 剔除 `%#$`；
 * - 公共前缀 `_common_path` :360-381——按 `/` 分段的位置对齐过滤，含尾部斜杠。
 */
object RapidCodec {

    /** 秒传链接前缀（参考源 offline_service.py:25，逐字）。 */
    const val LINK_PREFIX = "123FLCPV2$"

    /** Base62 字母表（参考源 offline_service.py:27，逐字）。 */
    const val BASE62_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private val strictJson = Json
    private val exportJson = Json { prettyPrint = true }

    /**
     * 解析秒传数据（JSON 或文本链接）。
     *
     * 非法输入抛 [IllegalArgumentException]，文案对齐参考源 ValueError：
     * 空输入"输入为空"、JSON 非 object"JSON 格式无效"、缺 files"JSON 中缺少 files 列表"、
     * 链接前缀不符"不支持的秒传链接前缀"、缺 `%`"秒传链接格式无效"、
     * 全部条目无效"未解析到有效文件"。
     */
    fun parse(text: String): List<RapidFile> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("输入为空")
        // 参考源 :115-119：json.loads 是严格解析——任何能解析的 JSON 文档（含数组/数字/字符串）
        // 都走"非对象即 JSON 格式无效"；解析失败（纯文本链接）才转链接解析。kotlinx 的
        // parseToJsonElement 对普通文本会宽容产出"非引号字面量"JsonPrimitive，无法直接对齐，
        // 因此：对象/数组 → JSON 分支；裸字面量仅当整体匹配严格 JSON 数字/带引号字符串语法
        // 时才算 JSON 文档（"123" 是 JSON，"123FLCPV2$dir%..." 不是）；其余一律转链接解析。
        val element: JsonElement? = try {
            strictJson.parseToJsonElement(trimmed)
        } catch (error: IllegalArgumentException) {
            // kotlinx.serialization 的 SerializationException 是 IllegalArgumentException 子类
            null
        }
        return when {
            element is JsonObject || element is JsonArray -> parseRapidJson(element)
            element is JsonPrimitive && isStrictJsonPrimitive(trimmed, element) -> parseRapidJson(element)
            else -> parseRapidLink(text)
        }
    }

    // 严格 JSON 裸字面量：合法数字或带引号字符串（对应 json.loads 能成功解析的形态）。
    private val STRICT_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?\z""")

    private fun isStrictJsonPrimitive(source: String, element: JsonPrimitive): Boolean =
        if (element.isString) source.startsWith("\"") && source.endsWith("\"")
        else source.matches(STRICT_NUMBER)

    /**
     * 生成标准秒传数据（JSON + 文本链接）。[files] 为完整相对路径条目；
     * 空列表或全部缺少有效 etag 时抛 [IllegalArgumentException]（:317-324 文案）。
     */
    fun export(files: List<RapidFile>): RapidExport {
        if (files.isEmpty()) throw IllegalArgumentException("没有可生成的文件")
        val valid = files.filter { isValidEtag(it.etag) }
        if (valid.isEmpty()) throw IllegalArgumentException("所选文件缺少有效的 etag，无法生成秒传数据")

        val commonPath = commonPathOf(valid)
        val relFiles = valid.map { file ->
            val relative = if (commonPath.isNotEmpty() && file.path.startsWith(commonPath)) {
                file.path.removePrefix(commonPath)
            } else {
                file.path
            }
            RapidFile(relative, file.etag.lowercase(), file.size)
        }

        val payload = buildJsonObject {
            // 字段名与取值逐字对齐参考源 offline_service.py:339-350，顺序即序列化顺序
            put("scriptVersion", "3.0.3")
            put("exportVersion", "1.0")
            put("usesBase62EtagsInExport", false)
            put("commonPath", commonPath)
            put("files", buildJsonArray {
                relFiles.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("etag", file.etag)
                        put("size", file.size)
                    })
                }
            })
            put("totalFilesCount", relFiles.size)
            put("totalSize", relFiles.sumOf { it.size })
        }
        val jsonText = exportJson.encodeToString(payload)

        // path 剔除 %#$（参考源 offline_service.py:355），分隔符 $ 与条目内 # 均逐字
        val linkText = LINK_PREFIX + commonPath + "%" + relFiles.joinToString("$") { file ->
            val safePath = file.path.replace("%", "").replace("#", "").replace("$", "")
            "${file.etag}#${file.size}#$safePath"
        }
        return RapidExport(jsonText, linkText)
    }

    /** etag 必须是 32 位十六进制（参考源 :199-204，大小写均接受）。 */
    fun isValidEtag(etag: String): Boolean =
        etag.length == 32 && etag.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }

    /**
     * Base62 字符串转 32 位小写十六进制（参考源 :206-212，BigInteger 同余语义）。
     * 含字母表外字符时抛 [IllegalArgumentException]（对齐参考源 `list.index` 的 ValueError）。
     */
    fun base62ToHex(base62: String): String {
        var value = BigInteger.ZERO
        val sixtyTwo = BigInteger.valueOf(62)
        for (ch in base62) {
            val index = BASE62_CHARS.indexOf(ch)
            if (index < 0) throw IllegalArgumentException("秒传数据包含非法的 Base62 字符：$ch")
            value = value.multiply(sixtyTwo).add(BigInteger.valueOf(index.toLong()))
        }
        return value.toString(16).padStart(ETAG_HEX_LENGTH, '0')
    }

    /** JSON 形态（参考源 :121-151）：files 缺失 / 空即报错，单条无效跳过。 */
    private fun parseRapidJson(element: JsonElement): List<RapidFile> {
        val obj = element as? JsonObject ?: throw IllegalArgumentException("JSON 格式无效")
        val files = (obj["files"] as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("JSON 中缺少 files 列表")
        val commonPath = obj.stringOf("commonPath")
        val usesBase62 = obj.boolOf("usesBase62EtagsInExport")

        val result = ArrayList<RapidFile>()
        for (item in files) {
            val file = item as? JsonObject ?: continue
            val path = file.stringOf("path").trim()
            // 参考源 :141-144：base62 形态先转换再校验（空 etag 会转成全 0 的合法 hex，同参考源）
            val etag = if (usesBase62) base62ToHex(file.stringOf("etag").trim()) else file.stringOf("etag").trim().lowercase()
            val size = file.longOf("size")
            if (path.isEmpty() || !isValidEtag(etag)) continue
            result += RapidFile(joinPath(commonPath, path), etag, size)
        }
        if (result.isEmpty()) throw IllegalArgumentException("未解析到有效文件")
        return result
    }

    /** 文本链接形态（参考源 :153-197）：123FLCPV2 前缀 + 旧版无前缀多行兼容。 */
    private fun parseRapidLink(text: String): List<RapidFile> {
        var commonPath = ""
        var shareFileInfo = text
        if (text.startsWith("123F")) {
            val prefix = text.substringBefore("$")
            // 参考源 :164 text[len(prefix) + 1:]——越界安全版为 drop（不足时得空串）
            val rest = text.drop(prefix.length + 1)
            if (prefix + "$" != LINK_PREFIX) throw IllegalArgumentException("不支持的秒传链接前缀")
            if ("%" !in rest) throw IllegalArgumentException("秒传链接格式无效")
            commonPath = rest.substringBefore("%")
            shareFileInfo = rest.substringAfter("%")
        }

        val result = ArrayList<RapidFile>()
        // 参考源 :176：换行统一替换为 $ 后再分段
        for (item in shareFileInfo.replace("\r\n", "$").replace("\n", "$").split("$")) {
            val parts = item.split("#")
            if (parts.size < 3) continue
            val rawEtag = parts[0].trim()
            val sizeText = parts[1].trim()
            val path = parts[2].trim()
            if (path.isEmpty()) continue
            // 参考源 :183-188：22 位走 Base62，其余小写化后校验
            val etag = if (rawEtag.length == BASE62_ETAG_LENGTH) base62ToHex(rawEtag) else rawEtag.lowercase()
            if (!isValidEtag(etag)) continue
            val size = sizeText.toLongOrNull() ?: continue
            result += RapidFile(joinPath(commonPath, path), etag, size)
        }
        if (result.isEmpty()) throw IllegalArgumentException("未解析到有效文件")
        return result
    }

    /** 公共目录前缀（参考源 :360-381）：按 `/` 分段位置对齐取同段，带尾部斜杠。 */
    private fun commonPathOf(files: List<RapidFile>): String {
        val dirs = files.map { file ->
            if ('/' in file.path) file.path.substringBeforeLast("/") else ""
        }
        if (dirs.isEmpty() || dirs.all { it.isEmpty() }) return ""
        var common = dirs.first().split("/")
        for (dir in dirs.drop(1)) {
            // 参考源 :377：zip + 位置相等过滤（非 takeWhile——中段不等仍保留后段匹配）
            common = common.zip(dir.split("/")).filter { it.first == it.second }.map { it.first }
            if (common.isEmpty()) return ""
        }
        val joined = common.joinToString("/")
        return if (joined.isEmpty()) "" else "$joined/"
    }

    /** 参考源 :147/:193：commonPath 非空时直接拼接（path 已是相对路径）。 */
    private fun joinPath(commonPath: String, path: String): String =
        if (commonPath.isNotEmpty()) commonPath + path else path

    private fun JsonObject.stringOf(vararg keys: String): String {
        for (key in keys) {
            val value = this[key]
            if (value is JsonNull) continue
            if (value is JsonPrimitive) return value.content
        }
        return ""
    }

    private fun JsonObject.longOf(vararg keys: String): Long {
        for (key in keys) {
            val value = this[key]
            if (value is JsonNull) continue
            if (value is JsonPrimitive) {
                value.longOrNull?.let { return it }
            }
        }
        return 0L
    }

    private fun JsonObject.boolOf(key: String): Boolean {
        val value = this[key] ?: return false
        if (value is JsonNull) return false
        if (value is JsonPrimitive) {
            value.booleanOrNull?.let { return it }
            value.longOrNull?.let { return it != 0L }
        }
        return false
    }

    private const val ETAG_HEX_LENGTH = 32

    /** Base62 形态 etag 的固定长度（参考源 :183）。 */
    private const val BASE62_ETAG_LENGTH = 22
}
