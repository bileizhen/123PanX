package io.github.bileizhen.pan123x.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bileizhen.pan123x.core.logging.AppLogger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the same Android FileStorage factory as AppContainer, including overwrite and reopen. */
@RunWith(AndroidJUnit4::class)
class SettingsPersistenceAndroidTest {
    @Test
    fun appearanceWritesOverwriteExistingFileAndSurviveTwoReopens() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb")
        val original = AppSettings(
            themeMode = ThemeMode.DARK, monet = false, uiScale = 1.2f,
            blur = false, floatingBar = false, liquidGlass = false, predictiveBack = false,
        )
        val secondWrite = original.copy(uiScale = 0.9f, predictiveBack = true)
        val finalWrite = secondWrite.copy(themeMode = ThemeMode.LIGHT, floatingBar = true)
        try {
            withRepository(file) { repository ->
                repository.edit { original }
                awaitSettings(repository, original)
                repository.edit { it.copy(uiScale = 0.9f, predictiveBack = true) }
                awaitSettings(repository, secondWrite)
            }
            withRepository(file) { repository ->
                awaitSettings(repository, secondWrite)
                repository.edit { it.copy(themeMode = ThemeMode.LIGHT, floatingBar = true) }
                awaitSettings(repository, finalWrite)
            }
            withRepository(file) { repository -> awaitSettings(repository, finalWrite) }
        } finally {
            assertTrue("Test preferences must be cleaned after stores close", !file.exists() || file.delete())
            val scratch = File(file.absolutePath + ".tmp")
            assertTrue("Test scratch file must be cleaned", !scratch.exists() || scratch.delete())
        }
    }

    private suspend fun awaitSettings(repository: SettingsRepository, expected: AppSettings) {
        assertEquals(expected, withTimeout(5000) { repository.state.first { it == expected } })
    }

    private suspend fun withRepository(file: File, block: suspend (SettingsRepository) -> Unit) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            block(SettingsRepository(store, scope, AppLogger()))
        } finally {
            job.cancelAndJoin()
        }
    }
}
