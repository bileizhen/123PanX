package io.github.bileizhen.pan123x.feature.account

import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.data.auth.maskPassport
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CloudInfoFormatTest {
    @Test fun passportIsMaskedAndQrDoesNotBecomeAnAccountNumber() {
        assertEquals("138****8000", maskPassport("13800138000"))
        assertEquals("b***@example.com", maskPassport("bileizhen@example.com"))
        assertEquals("", maskPassport("qr:1000000001"))
        assertEquals("", maskPassport(""))
    }
    @Test fun storagePercentageIsBoundedByAvailabilityNotClampedToProgress() {
        assertTrue(spaceUsage(256, 1024).endsWith("(25.0%)"))
        assertFalse(spaceUsage(0, 0).contains("NaN"))
        assertFalse(spaceUsage(0, 0).contains("%"))
        assertTrue(spaceUsage(2048, 1024).endsWith("(200.0%)"))
    }
    @Test fun membershipUsesServerStatusAndDoesNotInventAnExpiry() {
        val account = AccountEntity("a", "n")
        assertEquals("非会员", membership(account))
        assertEquals("VIP2 · 2026-12-01 到期", membership(account.copy(vip = true, vipLevel = 2, vipExpire = "2026-12-01")))
        assertEquals("VIP", membership(account.copy(vip = true)))
    }
    @Test fun deviceTimeUsesEpochSecondsAndPreservesDateStrings() {
        assertEquals("2026-10-01 23:15:36", deviceLoginTime("2026-10-01 23:15:36"))
        assertEquals("1970-01-01 08:00", deviceLoginTime("0", ZoneId.of("Asia/Shanghai")))
        assertEquals("未提供", deviceLoginTime(""))
        assertEquals("9223372036854775807", deviceLoginTime("9223372036854775807"))
    }
}
