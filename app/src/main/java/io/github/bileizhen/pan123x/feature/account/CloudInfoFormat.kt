package io.github.bileizhen.pan123x.feature.account

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.ui.util.formatBytes

internal fun spaceUsage(used: Long, total: Long): String {
    val bytes = "${formatBytes(used)} / ${formatBytes(total)}"
    return if (total > 0) "$bytes (${String.format(Locale.ROOT, "%.1f", used.toDouble() / total * 100)}%)" else bytes
}

internal fun membership(account: AccountEntity): String = if (!account.vip) "非会员" else buildString {
    append("VIP")
    if (account.vipLevel > 0) append(account.vipLevel)
    if (account.vipExpire.isNotBlank()) append(" · ${account.vipExpire} 到期")
}

/** Reference format_device_time uses epoch seconds and leaves non-numeric dates intact. */
internal fun deviceLoginTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val text = raw.trim()
    if (text.isBlank()) return "未提供"
    val seconds = text.toLongOrNull() ?: return text
    return try {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochSecond(seconds))
    } catch (_: java.time.DateTimeException) { text }
}
