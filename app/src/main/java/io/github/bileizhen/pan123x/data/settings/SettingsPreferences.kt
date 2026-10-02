package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Single mapping shared by readers, atomic writers, and JVM tests. */
object SettingsPreferences {
    private val themeMode = stringPreferencesKey("theme_mode")
    private val monet = booleanPreferencesKey("monet")
    private val uiScale = floatPreferencesKey("ui_scale")
    private val blur = booleanPreferencesKey("blur")
    private val floatingBar = booleanPreferencesKey("floating_bar")
    private val liquidGlass = booleanPreferencesKey("liquid_glass")
    private val predictiveBack = booleanPreferencesKey("predictive_back")
    private val maxTextPreviewBytes = longPreferencesKey("max_text_preview_bytes")
    private val downloadConnections = intPreferencesKey("download_connections")
    private val downloadTree = stringPreferencesKey("download_tree")
    private val shareClipboard = booleanPreferencesKey("recognize_share_clipboard")
    private val autoUpdates = booleanPreferencesKey("auto_check_updates")
    private val askLocation = booleanPreferencesKey("ask_download_location")
    private val multiThread = booleanPreferencesKey("multi_thread_download")
    private val uploadThreads = intPreferencesKey("upload_threads")
    private val concurrentDownloads = intPreferencesKey("max_concurrent_downloads")
    private val concurrentUploads = intPreferencesKey("max_concurrent_uploads")
    private val downloadSpeed = longPreferencesKey("download_speed_limit")
    private val uploadSpeed = longPreferencesKey("upload_speed_limit")
    private val simulation = booleanPreferencesKey("client_simulation")
    private val retry = booleanPreferencesKey("error_backoff_retry")
    private val logLevel = stringPreferencesKey("log_level")
    private val language = stringPreferencesKey("language")

    fun read(preferences: Preferences): AppSettings = AppSettings(
        themeMode = ThemeMode.fromStored(preferences[themeMode]),
        monet = preferences[monet] ?: true,
        uiScale = preferences[uiScale] ?: 1f,
        blur = preferences[blur] ?: true,
        floatingBar = preferences[floatingBar] ?: true,
        liquidGlass = preferences[liquidGlass] ?: true,
        predictiveBack = preferences[predictiveBack] ?: true,
        downloadConnections = preferences[downloadConnections] ?: 4,
        downloadTree = preferences[downloadTree].orEmpty(),
        recognizeShareClipboard = preferences[shareClipboard] ?: true,
        autoCheckUpdates = preferences[autoUpdates] ?: true,
        askDownloadLocation = preferences[askLocation] ?: false,
        multiThreadDownload = preferences[multiThread] ?: true,
        uploadThreads = preferences[uploadThreads] ?: 1,
        maxConcurrentDownloads = preferences[concurrentDownloads] ?: 3,
        maxConcurrentUploads = preferences[concurrentUploads] ?: 3,
        downloadSpeedLimit = preferences[downloadSpeed] ?: 0,
        uploadSpeedLimit = preferences[uploadSpeed] ?: 0,
        clientSimulation = preferences[simulation] ?: true,
        errorBackoffRetry = preferences[retry] ?: true,
        logLevel = preferences[logLevel] ?: "INFO",
        language = AppLanguage.entries.firstOrNull { it.name == preferences[language] } ?: AppLanguage.CHINESE,
        maxTextPreviewBytes = preferences[maxTextPreviewBytes] ?: AppSettings.DEFAULT_MAX_TEXT_PREVIEW_BYTES,
    ).normalized()

    fun write(preferences: MutablePreferences, value: AppSettings) {
        val settings = value.normalized()
        preferences[themeMode] = settings.themeMode.name
        preferences[monet] = settings.monet
        preferences[uiScale] = settings.uiScale
        preferences[blur] = settings.blur
        preferences[floatingBar] = settings.floatingBar
        preferences[liquidGlass] = settings.liquidGlass
        preferences[predictiveBack] = settings.predictiveBack
        preferences[downloadConnections] = settings.downloadConnections
        preferences[downloadTree] = settings.downloadTree
        preferences[shareClipboard] = settings.recognizeShareClipboard
        preferences[autoUpdates] = settings.autoCheckUpdates
        preferences[maxTextPreviewBytes] = settings.maxTextPreviewBytes
        preferences[askLocation] = settings.askDownloadLocation
        preferences[multiThread] = settings.multiThreadDownload
        preferences[uploadThreads] = settings.uploadThreads
        preferences[concurrentDownloads] = settings.maxConcurrentDownloads
        preferences[concurrentUploads] = settings.maxConcurrentUploads
        preferences[downloadSpeed] = settings.downloadSpeedLimit
        preferences[uploadSpeed] = settings.uploadSpeedLimit
        preferences[simulation] = settings.clientSimulation
        preferences[retry] = settings.errorBackoffRetry
        preferences[logLevel] = settings.logLevel
        preferences[language] = settings.language.name
    }
}
