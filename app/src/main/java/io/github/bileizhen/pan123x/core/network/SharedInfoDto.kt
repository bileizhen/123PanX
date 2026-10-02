package io.github.bileizhen.pan123x.core.network

import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SharedInfoDto(
    val name: String,
    val owner: String,
    val avatar: String,
    val hasPassword: Boolean?,
    val expiration: Long,
    val createdAt: Long,
    val expired: Boolean,
    val vip: Boolean,
) {
    companion object {
        internal fun fromJson(root: JsonObject): SharedInfoDto? {
            val data = root["data"] as? JsonObject ?: return null
            if (data.isEmpty()) return null
            fun text(key: String) = (data[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
            fun flag(key: String) = (data[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.longOrNull == 1L) } ?: false
            val hasPassword = (data["HasPwd"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.longOrNull?.let { number -> number == 1L } }
            return SharedInfoDto(text("ShareName"), text("UserNickName"), text("HeadImage"), hasPassword,
                parsePanTimestamp(text("Expiration")), parsePanTimestamp(text("CreateAt")), flag("Expired"), flag("IsVip") || flag("IsSVip"))
        }
    }
}

/** The service also returns local Chinese times such as 2026/9/6 19:09:23. */
internal fun parsePanTimestamp(text: String): Long {
    val value = text.trim()
    value.toDoubleOrNull()?.let { return if (it <= 0 || !it.isFinite()) 0 else (if (it < 100_000_000_000) it * 1_000 else it).toLong() }
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    for (pattern in listOf("yyyy/M/d H:mm:ss", "yyyy/M/d H:mm", "yyyy-M-d H:mm:ss", "yyyy-M-d H:mm")) {
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ROOT))
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    }
    return 0
}
