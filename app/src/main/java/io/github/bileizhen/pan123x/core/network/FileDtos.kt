package io.github.bileizhen.pan123x.core.network

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 单个云端文件 / 文件夹（`/api/file/list/new` 的 InfoList 元素），键名兼容规则逐字段
 * 照抄参考源 `model.py FileItemModel.from_dict`：主键大驼峰，回退小驼峰
 * （如 fileId / parentFileId / s3keyFlag / pinYin / starredStatus）。
 *
 * Type: 1=文件夹（isFolder=true），0=文件。
 * 数值字段容错 string / int，布尔字段容错 0 / 1。
 * 时间字段统一折算为 epochMillis，容错规则对应参考源 `_parse_timestamp`：
 * unix 秒（int / float / 数字字符串）* 1000；ISO8601 字符串（含 T 或 -）按 Instant
 * 解析，失败为 0；空值 / null / 0 为 0。
 */
data class FileItemDto(
    val fileId: Long,
    val parentFileId: Long,
    val fileName: String,
    val isFolder: Boolean,
    val size: Long,
    val etag: String,
    val s3KeyFlag: String,
    val contentType: String,
    val createAt: Long,
    val updateAt: Long,
    val hidden: Boolean,
    val starred: Boolean,
    val pinyin: String,
) {
    companion object {
        private const val FILE_TYPE_FOLDER = 1

        internal fun fromJsonObject(data: JsonObject): FileItemDto = FileItemDto(
            fileId = data.longOf("FileId", "fileId"),
            parentFileId = data.longOf("ParentFileId", "parentFileId"),
            fileName = data.stringOf("FileName", "fileName"),
            isFolder = data.longOf("Type", "type").toInt() == FILE_TYPE_FOLDER,
            size = data.longOf("Size", "size"),
            etag = data.stringOf("Etag", "etag"),
            s3KeyFlag = data.stringOf("S3KeyFlag", "s3keyFlag"),
            contentType = data.stringOf("ContentType", "contentType"),
            createAt = data.timestampOf("CreateAt", "createAt"),
            updateAt = data.timestampOf("UpdateAt", "updateAt"),
            hidden = data.boolOf("Hidden", "hidden"),
            starred = data.boolOf("StarredStatus", "starredStatus"),
            pinyin = data.stringOf("PinYin", "pinYin"),
        )
    }
}

/**
 * `/api/file/list/new` 成功时的 data，键名兼容规则照抄参考源
 * `model.py FileListData.from_dict`：InfoList / Total / Next / Len / IsFirst 主键大驼峰，
 * 回退小驼峰。next 为服务端分页游标，"-1" 表示无更多（两键均缺失时同样取 "-1"）。
 */
data class FileListDto(
    val infoList: List<FileItemDto>,
    val total: Int,
    val next: String,
    val len: Int,
    val isFirst: Boolean,
) {
    companion object {
        private const val NEXT_NO_MORE = "-1"

        /**
         * 从响应根元素解析；沿用 UserInfoDto.fromJsonElement 语义：存在 "data" 键则取
         * data，否则把根元素本身当 data。结构不符（根非 object、data 非 object、
         * InfoList 元素非 object）返回 null，由调用方转 ParseError。
         * InfoList 两键均缺失时按空列表处理，与参考源默认值一致。
         */
        fun fromJsonElement(element: JsonElement): FileListDto? {
            val root = element as? JsonObject ?: return null
            val data = if (root.containsKey("data")) {
                root["data"] as? JsonObject ?: return null
            } else {
                root
            }
            val infoArray = (data["InfoList"] ?: data["infoList"]) as? JsonArray
            val items = mutableListOf<FileItemDto>()
            if (infoArray != null) {
                for (item in infoArray) {
                    val obj = item as? JsonObject ?: return null
                    items += FileItemDto.fromJsonObject(obj)
                }
            }
            return FileListDto(
                infoList = items,
                total = data.longOf("Total", "total").toInt(),
                next = data.stringOf("Next", "next", default = NEXT_NO_MORE),
                len = data.longOf("Len", "len").toInt(),
                isFirst = data.boolOf("IsFirst", "isFirst"),
            )
        }
    }
}

/** 依次尝试多个键，返回第一个可解析的整数（容错 string / int），否则 0。 */
private fun JsonObject.longOf(vararg keys: String): Long {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.longOrNull?.let { return it }
    }
    return 0L
}

/** 依次尝试多个键，返回第一个字符串值（跳过 JSON null），否则 default。 */
private fun JsonObject.stringOf(vararg keys: String, default: String = ""): String {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        val primitive = value as? JsonPrimitive ?: continue
        return primitive.content
    }
    return default
}

/** 依次尝试多个键，容错 true/false 与 0/1，否则 false。 */
private fun JsonObject.boolOf(vararg keys: String): Boolean {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.booleanOrNull?.let { return it }
        value.longOrNull?.let { return it != 0L }
    }
    return false
}

/** 取第一个存在的原始值键（参考源语义：主键存在即采用，即便值为 null 也不回退次键）。 */
private fun JsonObject.firstPrimitiveOf(vararg keys: String): JsonPrimitive? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        return value
    }
    return null
}

/**
 * 时间戳容错（对应参考源 `_parse_timestamp`），统一折算 epochMillis：
 * - unix 秒（int / float / 数字字符串）-> * 1000；
 * - ISO8601 字符串（含 T 或 -）-> Instant 解析，失败为 0；
 * - 空串 / null / 0 / 无法识别 -> 0。
 */
private fun JsonObject.timestampOf(vararg keys: String): Long {
    val value = firstPrimitiveOf(*keys) ?: return 0L
    value.longOrNull?.let { return it * 1000 }
    if (!value.isString) {
        // JSON 数字字面量的小数秒（如 1700001234.5）
        value.doubleOrNull?.let { return (it * 1000.0).toLong() }
    }
    val text = value.content.trim()
    if (text.isEmpty()) return 0L
    if ('T' in text || '-' in text) {
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }
    return text.toDoubleOrNull()?.let { (it * 1000.0).toLong() } ?: 0L
}
