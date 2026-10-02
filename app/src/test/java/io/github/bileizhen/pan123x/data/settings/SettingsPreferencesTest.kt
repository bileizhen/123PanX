package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPreferencesTest {
    @Test
    fun missingPreferencesUseAppearanceDefaults() {
        assertEquals(AppSettings(), SettingsPreferences.read(emptyPreferences()))
    }

    @Test
    fun everyAppearanceSettingRoundTrips() {
        val requested = AppSettings(
            themeMode = ThemeMode.DARK, monet = false, uiScale = 1.15f,
            blur = false, floatingBar = false, liquidGlass = false, predictiveBack = false,
            recognizeShareClipboard = false, autoCheckUpdates = false,
            askDownloadLocation = true, multiThreadDownload = false, uploadThreads = 3,
            maxConcurrentDownloads = 7, maxConcurrentUploads = 5,
            downloadSpeedLimit = 256 * 1024, uploadSpeedLimit = 128 * 1024,
            clientSimulation = false, errorBackoffRetry = false, logLevel = "DEBUG", language = AppLanguage.ENGLISH,
        )
        val preferences = mutablePreferencesOf()
        SettingsPreferences.write(preferences, requested)
        assertEquals(requested, SettingsPreferences.read(preferences))
    }

    @Test
    fun invalidStoredThemeAndScaleAreSafe() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey("theme_mode") to "UNRECOGNIZED",
            floatPreferencesKey("ui_scale") to Float.NaN,
        )
        assertEquals(ThemeMode.SYSTEM, SettingsPreferences.read(preferences).themeMode)
        assertEquals(1f, SettingsPreferences.read(preferences).uiScale, 0f)
        assertEquals(AppSettings.MIN_SCALE, AppSettings(uiScale = -10f).normalized().uiScale, 0f)
        assertEquals(AppSettings.MAX_SCALE, AppSettings(uiScale = 20f).normalized().uiScale, 0f)
    }

    @Test fun downloadChoicesPersistAndInvalidConnectionsFallBack() {
        val preferences = mutablePreferencesOf()
        val requested = AppSettings(downloadConnections = 16, downloadTree = "content://test/tree/downloads")
        SettingsPreferences.write(preferences, requested)
        assertEquals(requested, SettingsPreferences.read(preferences))
        preferences[intPreferencesKey("download_connections")] = 3
        assertEquals(4, SettingsPreferences.read(preferences).downloadConnections)
        SettingsPreferences.write(preferences, requested.copy(downloadTree = ""))
        assertEquals("", SettingsPreferences.read(preferences).downloadTree)
    }

    @Test fun invalidTransferValuesAreBoundedWithoutChangingExistingDestinations() {
        val input = AppSettings(uploadThreads = 20, maxConcurrentDownloads = 0, maxConcurrentUploads = 100,
            downloadSpeedLimit = -1, uploadSpeedLimit = Long.MAX_VALUE, logLevel = "TRACE", downloadTree = "content://keep")
        val normalized = input.normalized()
        assertEquals(4, normalized.uploadThreads); assertEquals(1, normalized.maxConcurrentDownloads); assertEquals(32, normalized.maxConcurrentUploads)
        assertEquals(0L, normalized.downloadSpeedLimit); assertEquals(AppSettings.MAX_SPEED_LIMIT, normalized.uploadSpeedLimit)
        assertEquals("INFO", normalized.logLevel); assertEquals("content://keep", normalized.downloadTree)
    }
}
