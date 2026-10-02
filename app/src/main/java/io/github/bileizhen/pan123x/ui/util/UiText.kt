package io.github.bileizhen.pan123x.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import io.github.bileizhen.pan123x.data.settings.AppLanguage
import java.util.Locale

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.CHINESE }

/** A captured pure translator can also be used by menu factories and remembered UI models. */
@Composable
fun rememberUiTranslator(): (String) -> String {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val selected = LocalAppLanguage.current
    val english = selected == AppLanguage.ENGLISH || (selected == AppLanguage.SYSTEM && configuration.locales[0]?.language != "zh")
    return remember(context, english, configuration) {
        if (!english) { value: String -> value }
        else {
            val translated = context.createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.ENGLISH) }).resources
            { value: String -> UiTranslationIds[value]?.let(translated::getString) ?: dynamicEnglish(value, translated) }
        }
    }
}

private val selectionPattern = Regex("^已选 (\\d+) 项$")
private val countPattern = Regex("^(\\d+) (个连接|个分片|个任务|个文件|个项目)$")
private val summaryPattern = Regex("^(\\d+) 项 · (\\d+) 进行中$")
private fun dynamicEnglish(value: String, resources: android.content.res.Resources): String {
    selectionPattern.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()?.let {
        return resources.getQuantityString(io.github.bileizhen.pan123x.R.plurals.ui_selected_files, it, it)
    }
    countPattern.matchEntire(value)?.let { match ->
        val resource = when (match.groupValues[2]) {
            "个连接" -> io.github.bileizhen.pan123x.R.plurals.ui_connections
            "个分片" -> io.github.bileizhen.pan123x.R.plurals.ui_parts
            "个任务" -> io.github.bileizhen.pan123x.R.plurals.ui_tasks
            else -> io.github.bileizhen.pan123x.R.plurals.ui_items
        }
        val quantity = match.groupValues[1].toIntOrNull() ?: return value
        return resources.getQuantityString(resource, quantity, quantity)
    }
    summaryPattern.matchEntire(value)?.let { return resources.getString(io.github.bileizhen.pan123x.R.string.ui_transfer_summary, it.groupValues[1], it.groupValues[2]) }
    return value
}
