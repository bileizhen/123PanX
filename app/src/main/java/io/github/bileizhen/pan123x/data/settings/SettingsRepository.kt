package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

/** Explicitly injected DataStore: only one owner opens the preferences file. */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val logger: AppLogger,
) {
    private val initialSettings = CompletableDeferred<AppSettings>()
    suspend fun awaitLoaded(): AppSettings = initialSettings.await()
    val state: StateFlow<AppSettings> = dataStore.data
        .retryWhen { error, attempt ->
            if (error is IOException) {
                if (attempt == 0L) {
                    logger.w(LogSource.APP, "外观设置读取失败，暂用默认外观并重试")
                    emit(emptyPreferences())
                }
                // Keep observing after transient disk errors, without spinning or swallowing cancellation.
                delay(250L * (1L shl attempt.coerceAtMost(3L).toInt()))
                true
            } else {
                false
            }
        }
        .map { SettingsPreferences.read(it).also { value -> initialSettings.complete(value) } }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The transformation reads the latest disk snapshot inside DataStore's transaction. */
    suspend fun edit(change: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            SettingsPreferences.write(preferences, change(SettingsPreferences.read(preferences)))
        }
    }
}
