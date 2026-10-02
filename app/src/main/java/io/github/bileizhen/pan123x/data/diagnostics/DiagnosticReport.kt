// Adapted from XBlocker data/DiagnosticReport.kt (MIT); no Hook or module state.
package io.github.bileizhen.pan123x.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.bileizhen.pan123x.BuildConfig
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogReportWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class DiagnosticReport(context: Context, private val logger: AppLogger, private val diagnostics: DiagnosticsRepository) {
    private val context = context.applicationContext

    suspend fun create(): File = withContext(Dispatchers.IO) {
        val snapshot = diagnostics.snapshot()
        val summary = JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("createdAt", System.currentTimeMillis())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("taskCounts", JSONObject(snapshot.taskCounts))
        }
        val entries = logger.entries.value
        val logcat = captureLogcat()
        coroutineContext.ensureActive()
        LogReportWriter.create(File(context.cacheDir, "bugreports"), summary.toString(2), entries, logcat)
    }

    private fun captureLogcat(): String {
        val output = File.createTempFile("panx-logcat-", ".txt", context.cacheDir)
        var process: java.lang.Process? = null
        return try {
            process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "500")
                .redirectErrorStream(true).redirectOutput(output).start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
            val text = output.bufferedReader().use { reader ->
                val chars = CharArray(2_000_000)
                var count = 0
                while (count < chars.size) {
                    val read = reader.read(chars, count, chars.size - count)
                    if (read < 0) break
                    count += read
                }
                String(chars, 0, count)
            }
            (if (!finished) "日志收集超时，以下为已读取部分。\n" else "") + text.ifBlank { "当前进程暂无系统日志。\n" }
        } catch (error: Exception) {
            "系统日志不可用：${error.javaClass.simpleName}\n"
        } finally { process?.destroy(); output.delete() }
    }
}
