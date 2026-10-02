package io.github.bileizhen.pan123x.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel(val label: String) { DEBUG("调试"), INFO("信息"), WARNING("警告"), ERROR("错误") }

enum class LogSource(val label: String) {
    APP("应用"), API("接口"), AUTH("认证"), FILE("文件"), DOWNLOAD("下载"),
    UPLOAD("上传"), PLAYER("播放"), DATABASE("数据库"),
}

data class LogEntry(val id: Long, val time: Long, val level: LogLevel, val source: LogSource, val message: String)

fun filterLogs(
    entries: List<LogEntry>,
    level: LogLevel? = null,
    source: LogSource? = null,
    query: String = "",
): List<LogEntry> {
    val keyword = query.trim()
    return entries.filter { entry ->
        (level == null || entry.level == level) &&
            (source == null || entry.source == source) &&
            (keyword.isEmpty() || entry.message.contains(keyword, true) || entry.source.label.contains(keyword, true))
    }
}

/** Process-local bounded history, inspired by LeiFetch's Logs.kt; no Hook or export coupling. */
class AppLogger(private val capacity: Int = CAPACITY, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    private var nextId = 0L
    val entries: StateFlow<List<LogEntry>> = mutableEntries.asStateFlow()
    @Volatile var minimumLevel: LogLevel = LogLevel.INFO

    init { require(capacity > 0) { "Log capacity must be positive" } }

    fun i(source: LogSource, message: String) = append(LogLevel.INFO, source, message)
    fun d(source: LogSource, message: String) = append(LogLevel.DEBUG, source, message)
    fun w(source: LogSource, message: String) = append(LogLevel.WARNING, source, message)
    fun e(source: LogSource, message: String) = append(LogLevel.ERROR, source, message)

    @Synchronized
    fun append(level: LogLevel, source: LogSource, message: String) {
        if (level.ordinal < minimumLevel.ordinal) return
        val safe = LogRedactor.redact(message).replace('\n', ' ').replace('\r', ' ').trim().take(MAX_MESSAGE)
        if (safe.isBlank()) return
        val entry = LogEntry(nextId++, clock(), level, source, safe)
        mutableEntries.value = (mutableEntries.value + entry).takeLast(capacity)
    }

    @Synchronized
    fun clear() { mutableEntries.value = emptyList() }

    companion object {
        const val CAPACITY = 600
        private const val MAX_MESSAGE = 1000
    }
}
