package io.github.bileizhen.pan123x.core.share

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.security.SecureRandom

/** Uses the existing shareName / expiration / sharePwd fields from file_service.py. */
data class ShareCreateOptions(
    val name: String = "123云盘分享",
    val password: String = "",
    val validDays: Int = 0,
) {
    fun validationError(): String? = when {
        name.isBlank() -> "请输入分享主题"
        name.length > 100 -> "分享主题最多 100 个字符"
        validDays !in listOf(0, 1, 7, 30) -> "请选择有效期"
        password.isNotEmpty() && !password.matches(Regex("[A-Za-z0-9]{4}")) -> "提取码需为 4 位字母或数字"
        else -> null
    }

    fun expirationAt(now: Instant): String = if (validDays == 0) "2099-12-12T08:00:00+08:00"
        else now.atOffset(ZoneOffset.ofHours(8)).plusDays(validDays.toLong())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

    companion object {
        fun randomPassword(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return CharArray(4) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
        }
    }
}
