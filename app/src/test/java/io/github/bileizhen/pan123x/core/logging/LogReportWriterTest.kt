package io.github.bileizhen.pan123x.core.logging

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class LogReportWriterTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun reportIsReadableAndRedactsSensitiveAppAndSystemLogs() {
        val logs = listOf(LogEntry(1, 0, LogLevel.ERROR, LogSource.AUTH, "Authorization: Bearer top-secret"))
        val file = LogReportWriter.create(temporary.root, "{}", logs, "Cookie: session=secret-cookie\nurl https://cdn.example/file?sig=signed-secret")
        ZipFile(file).use { zip ->
            assertEquals(setOf("README.txt", "summary.json", "app.log", "logcat.txt"), zip.entries().asSequence().map { it.name }.toSet())
            val app = zip.getInputStream(zip.getEntry("app.log")).bufferedReader().readText()
            val system = zip.getInputStream(zip.getEntry("logcat.txt")).bufferedReader().readText()
            assertFalse(app.contains("top-secret")); assertFalse(system.contains("secret-cookie")); assertFalse(system.contains("signed-secret"))
            assertTrue(system.contains("[路径已隐藏]"))
        }
    }
    @Test fun removesOnlyExpiredReportZipsAndPreservesFreshShares() {
        val old = temporary.newFile("123PanX_logs_old.zip").apply { setLastModified(System.currentTimeMillis() - 90_000_000) }
        val fresh = temporary.newFile("123PanX_logs_fresh.zip")
        val other = temporary.newFile("unrelated.zip").apply { setLastModified(1) }
        LogReportWriter.create(temporary.root, "{}", emptyList(), "")
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(other.exists())
    }
}
