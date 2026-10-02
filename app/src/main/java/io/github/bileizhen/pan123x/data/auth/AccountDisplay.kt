package io.github.bileizhen.pan123x.data.auth

/** Store only masked account identifiers in public Room metadata. QR identities are not passports. */
internal fun maskPassport(passport: String): String {
    if (passport.startsWith("qr:")) return ""
    val value = passport.trim()
    if (value.isEmpty()) return ""
    if (value.length == 11 && value.all { it in '0'..'9' }) return value.take(3) + "****" + value.takeLast(4)
    if ('@' in value) return value.substringBefore('@').take(1) + "***@" + value.substringAfter('@')
    return if (value.length > 2) value.take(1) + "***" + value.takeLast(1) else value.take(1) + "***"
}
