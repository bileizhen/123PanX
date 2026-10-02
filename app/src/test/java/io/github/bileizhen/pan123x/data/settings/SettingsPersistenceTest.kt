package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import io.github.bileizhen.pan123x.core.logging.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.FileSystem
import okio.Path.Companion.toPath

class SettingsPersistenceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun repositoryPersistsSettingsAndRestoresAfterStoreReopens() = runBlocking {
        val file = File(folder.root, "appearance.preferences_pb")
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob + Dispatchers.IO)
        // Android's FileStorage branches on SDK_INT, which is zero in local JVM Android stubs.
        // Use the official cross-platform storage for host tests; instrumentation covers the app's FileStorage.
        val firstStore = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
            scope = firstScope,
        )
        val expected = AppSettings(themeMode = ThemeMode.DARK, monet = false, blur = false, uiScale = 1.1f,
            askDownloadLocation = true, uploadThreads = 4, maxConcurrentDownloads = 8, maxConcurrentUploads = 2,
            downloadSpeedLimit = 512 * 1024, uploadSpeedLimit = 128 * 1024, clientSimulation = false,
            errorBackoffRetry = false, logLevel = "WARNING", language = AppLanguage.ENGLISH)
        try {
            val repository = SettingsRepository(firstStore, firstScope, AppLogger())
            repository.edit { expected }
            assertEquals(expected, SettingsPreferences.read(firstStore.data.first()))
        } finally {
            firstJob.cancelAndJoin()
        }

        val secondJob = SupervisorJob()
        val secondScope = CoroutineScope(secondJob + Dispatchers.IO)
        val secondStore = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
            scope = secondScope,
        )
        try {
            val repository = SettingsRepository(secondStore, secondScope, AppLogger())
            assertEquals(expected, withTimeout(5000) { repository.state.first { it.themeMode == ThemeMode.DARK } })
            repository.edit { it.copy(floatingBar = false) }
            assertEquals(expected.copy(floatingBar = false), SettingsPreferences.read(secondStore.data.first()))
        } finally {
            secondJob.cancelAndJoin()
        }
    }
}
