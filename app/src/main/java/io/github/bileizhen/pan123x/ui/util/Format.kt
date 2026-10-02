package io.github.bileizhen.pan123x.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 真实账户的容量 / 流量展示格式：1024 进制（沿用 M0 演示列表的口径），
 * 额外覆盖 TB 档（云盘总容量普遍在 TB 级）。
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_099_511_627_776L -> String.format(Locale.ROOT, "%.1f TB", bytes / 1_099_511_627_776.0)
    bytes >= 1_073_741_824L -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

private val localDateTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/**
 * epoch 毫秒转本地时区的"年-月-日 时:分"；时间戳缺失（<=0）返回空串，
 * 由调用方决定是否省略该段展示。
 */
fun formatDateTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    return localDateTimeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}
