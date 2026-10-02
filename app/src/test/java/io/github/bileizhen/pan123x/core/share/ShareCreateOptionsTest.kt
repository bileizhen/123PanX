package io.github.bileizhen.pan123x.core.share

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ShareCreateOptionsTest {
    @Test fun calculatesExpiryInProtocolTimezone() {
        val now = Instant.parse("2026-10-01T13:00:05Z")
        assertEquals("2026-10-02T21:00:05+08:00", ShareCreateOptions(validDays = 1).expirationAt(now))
        assertEquals("2026-10-08T21:00:05+08:00", ShareCreateOptions(validDays = 7).expirationAt(now))
        assertEquals("2026-10-31T21:00:05+08:00", ShareCreateOptions(validDays = 30).expirationAt(now))
        assertEquals("2099-12-12T08:00:00+08:00", ShareCreateOptions().expirationAt(now))
    }
    @Test fun rejectsInvalidFormValues() {
        assertNotNull(ShareCreateOptions(name = " ").validationError())
        assertNotNull(ShareCreateOptions(password = "12").validationError())
        assertNotNull(ShareCreateOptions(password = "中文汉字").validationError())
        assertNotNull(ShareCreateOptions(validDays = 4).validationError())
        assertNull(ShareCreateOptions(password = "aB12").validationError())
    }
    @Test fun generatesValidRandomCodes() { repeat(100) { assertTrue(ShareCreateOptions.randomPassword().matches(Regex("[A-Za-z0-9]{4}"))) } }
}
