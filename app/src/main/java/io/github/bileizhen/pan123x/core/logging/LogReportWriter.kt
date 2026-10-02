// ZIP report layout / one-day retention adapted from XBlocker DiagnosticReport (MIT).
package io.github.bileizhen.pan123x.core.logging

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogReportWriter {
    fun create(directory: File, summary: String, entries: List<LogEntry>, logcat: String, now: Long = System.currentTimeMillis()): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建日志目录" }
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm_ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now))
        val report = File(directory, "123PanX_logs_${stamp}_${UUID.randomUUID().toString().take(8)}.zip")
        try {
            ZipOutputStream(report.outputStream().buffered()).use { zip ->
                fun entry(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("README.txt", "123PanX 日志包\nsummary.json：应用、设备和传输状态计数。\napp.log：最近最多 600 条应用日志。\nlogcat.txt：当前应用进程可访问的近期系统日志。\n所有日志经统一 LogRedactor 脱敏。不会导出账户、凭据、文件列表或数据库。\n")
                // Summary is an explicit allowlist built by DiagnosticReport, never a database dump.
                entry("summary.json", summary)
                entry("app.log", entries.takeLast(AppLogger.CAPACITY).joinToString("\n") {
                    "${Instant.ofEpochMilli(it.time)} ${it.level.name} [${it.source.name}] ${LogRedactor.redact(it.message)}"
                })
                entry("logcat.txt", LogRedactor.redact(logcat.take(2_000_000)))
            }
            directory.listFiles()?.filter {
                it.isFile && it.name.startsWith("123PanX_logs_") && it.extension == "zip" && it.lastModified() < now - 86_400_000L
            }?.forEach { it.delete() }
            return report
        } catch (error: Exception) {
            report.delete()
            throw error
        }
    }
}
