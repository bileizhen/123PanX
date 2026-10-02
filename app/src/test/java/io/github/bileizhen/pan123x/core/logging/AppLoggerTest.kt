package io.github.bileizhen.pan123x.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
    @Test fun changingLevelFiltersNewEntriesAndDebugRemainsRedacted() {
        val logger = AppLogger()
        logger.d(LogSource.API, "hidden by default")
        assertTrue(logger.entries.value.isEmpty())
        logger.minimumLevel = LogLevel.DEBUG
        logger.d(LogSource.API, "Authorization: Bearer debug-secret")
        assertEquals(LogLevel.DEBUG, logger.entries.value.single().level)
        assertFalse(logger.entries.value.single().message.contains("debug-secret"))
        logger.minimumLevel = LogLevel.ERROR
        logger.i(LogSource.APP, "suppressed"); logger.w(LogSource.APP, "suppressed")
        logger.e(LogSource.APP, "retained")
        assertEquals(2, logger.entries.value.size)
    }
    @Test fun sharePasswordsAndChineseExtractionCodesAreAlsoRedacted() {
        val safe = LogRedactor.redact("{\"sharePwd\":\"Ab12\"}\n提取码：XY34\n提取密码=UV56")
        for (code in listOf("Ab12", "XY34", "UV56")) assertFalse(safe.contains(code))
    }
    @Test
    fun retainsNewest600EntriesAndClearPublishesEmptySnapshot() {
        val logger = AppLogger(clock = { 123L })
        repeat(650) { logger.i(LogSource.APP, "event $it") }
        assertEquals(600, logger.entries.value.size)
        assertEquals("event 50", logger.entries.value.first().message)
        assertEquals("event 649", logger.entries.value.last().message)
        assertEquals(123L, logger.entries.value.first().time)
        val oldSnapshot = logger.entries.value
        logger.clear()
        assertTrue(logger.entries.value.isEmpty())
        assertEquals(600, oldSnapshot.size)
    }

    @Test
    fun filtersByLevelSourceAndText() {
        val logger = AppLogger()
        logger.i(LogSource.APP, "startup")
        logger.w(LogSource.DATABASE, "Cache failed")
        logger.e(LogSource.DATABASE, "migration failed")
        assertEquals(1, filterLogs(logger.entries.value, LogLevel.WARNING, LogSource.DATABASE, "CACHE").size)
    }

    @Test
    fun secretsAreRedactedBeforeTheyReachTheBuffer() {
        val logger = AppLogger()
        logger.e(LogSource.AUTH, "Authorization: Bearer secret-token\nCookie: session=cookie-secret; csrf=another-secret")
        logger.i(LogSource.API, "{\"password\":\"escaped\\\"password\",\"loginuuid\":\"uuid-secret\"}")
        logger.i(LogSource.DOWNLOAD, "https://cdn.example.org/private-file?X-Amz-Signature=s3-secret&token=query-secret")
        val text = logger.entries.value.joinToString { it.message }
        listOf("secret-token", "cookie-secret", "another-secret", "escaped", "uuid-secret", "s3-secret", "query-secret", "private-file").forEach {
            assertFalse("Must hide $it", text.contains(it))
        }
        assertTrue(text.contains("cdn.example.org"))
    }

    @Test
    fun concurrentWritersDoNotLoseEvents() {
        val logger = AppLogger(capacity = 1000)
        val writers = List(4) { worker -> Thread { repeat(100) { logger.i(LogSource.APP, "worker $worker event $it") } } }
        writers.forEach(Thread::start)
        writers.forEach(Thread::join)
        assertEquals(400, logger.entries.value.size)
        assertEquals(400, logger.entries.value.map { it.id }.distinct().size)
    }

    @Test
    fun authorizationSchemesAndCookieHeadersAreCompletelyRemoved() {
        val safe = LogRedactor.redact("Authorization: Basic encoded-secret another-secret\nProxy-Authorization: opaque multiple secret words\nCookie: a=first; b=second")
        listOf("encoded-secret", "another-secret", "opaque", "multiple", "secret", "first", "second").forEach {
            assertFalse("Must hide $it", safe.contains(it))
        }
    }

    @Test
    fun standaloneCredentialFieldsAndUrlQueriesAreRemoved() {
        val safe = LogRedactor.redact("password=secret-pass token=secret-key {\"cookie\":\"json-secret\"} https://user:secret-login@example.com/private/secret-path?a=secret-query")
        listOf("secret-pass", "secret-key", "json-secret", "secret-login", "secret-path", "secret-query").forEach {
            assertFalse("Must hide $it", safe.contains(it))
        }
    }

    @Test
    fun passwordsContainingSpacesAndCamelCaseTokensAreRemoved() {
        val safe = LogRedactor.redact("password = spaced secret value; {\"accessToken\":\"access-secret\",\"refreshToken\":\"refresh-secret\",\"client_secret\":\"client-value\"}")
        listOf("spaced", "secret value", "access-secret", "refresh-secret", "client-value").forEach {
            assertFalse("Must hide $it", safe.contains(it))
        }
    }

    @Test
    fun malformedJsonAndNumericCredentialValuesDoNotLeak() {
        val safe = LogRedactor.redact("{\"password\":\"unterminated-secret\n{\"token\":123456789}\n{\"cookie\":[\"array-secret\"]}")
        listOf("unterminated-secret", "123456789", "array-secret").forEach {
            assertFalse("Must hide $it", safe.contains(it))
        }
    }
}
