package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.bileizhen.pan123x.core.logging.AppLogger
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryFailureTest {
    @Test
    fun transientReadFailureRecoversWithoutAppRestart() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val logger = AppLogger()
        val restored = AppSettings(themeMode = ThemeMode.DARK)
        val persisted = mutablePreferencesOf().also { SettingsPreferences.write(it, restored) }
        var attempts = 0
        val store = object : DataStore<Preferences> {
            override val data = flow {
                attempts++
                if (attempts == 1) throw IOException("transient read failure")
                emit(persisted)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(persisted)
        }
        try {
            val repository = SettingsRepository(store, scope, logger)
            assertEquals(restored, withTimeout(5000) { repository.state.first { it.themeMode == ThemeMode.DARK } })
            assertEquals(2, attempts)
            assertEquals(1, logger.entries.value.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test(expected = IOException::class)
    fun failedWritePropagatesToViewModel() = runBlocking {
        verifyWriteFailure(IOException("disk failure"))
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNeverConvertedToSuccessfulSettingsWrite() = runBlocking {
        verifyWriteFailure(CancellationException("cancelled"))
    }

    private suspend fun verifyWriteFailure(failure: Exception) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = object : DataStore<Preferences> {
            override val data = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                throw failure
            }
        }
        try {
            SettingsRepository(store, scope, AppLogger()).edit { it.copy(blur = false) }
        } finally {
            job.cancelAndJoin()
        }
    }
}
