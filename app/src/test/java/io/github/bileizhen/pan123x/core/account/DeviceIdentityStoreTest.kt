package io.github.bileizhen.pan123x.core.account

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceIdentityStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun loadOrCreateReturnsNullCurrentOnlyBeforeFirstCreation() {
        val file = File(folder.root, "device_identity.preferences_pb")
        withIdentityStore(file) { store, _ ->
            assertNull(store.current())
            assertNotNull(store.loadOrCreate(Random(1)))
            assertNotNull(store.current())
        }
    }

    @Test
    fun loadOrCreateReusesTheSameIdentityAcrossCallsAndStoreReopen() {
        val file = File(folder.root, "device_identity.preferences_pb")
        val created = withIdentityStore(file) { store, _ -> store.loadOrCreate(Random(1)) }
        withIdentityStore(file) { store, _ ->
            assertEquals(created, store.loadOrCreate(Random(2)))
            assertEquals(created, store.current())
        }
    }

    @Test
    fun generatedIdentityDrawsFromTheCatalogPools() {
        val file = File(folder.root, "device_identity.preferences_pb")
        withIdentityStore(file) { store, _ ->
            val identity = store.loadOrCreate(Random(7))
            assertTrue("osVersion must come from the catalog pool: ${identity.osVersion}", PanDeviceCatalog.osVersions.contains(identity.osVersion))
            assertTrue("deviceType must come from the catalog pool: ${identity.deviceType}", PanDeviceCatalog.deviceTypes.contains(identity.deviceType))
            assertTrue("loginUuid must be uuid4 hex: ${identity.loginUuid}", identity.loginUuid.matches(Regex("^[0-9a-f]{32}$")))
        }
    }

    @Test
    fun partiallyStoredIdentityIsRegeneratedAsAWhole() {
        val file = File(folder.root, "device_identity.preferences_pb")
        withIdentityStore(file) { store, raw ->
            raw.edit { it[stringPreferencesKey("devicetype")] = "LEFTOVER_PARTIAL" }
            val identity = store.loadOrCreate(Random(11))
            assertTrue(PanDeviceCatalog.osVersions.contains(identity.osVersion))
            assertTrue(PanDeviceCatalog.deviceTypes.contains(identity.deviceType))
            assertTrue(identity.loginUuid.matches(Regex("^[0-9a-f]{32}$")))
            assertEquals(identity, store.current())
        }
    }

    /** Real file-backed DataStore per SettingsPersistenceTest; store scope is joined before returning. */
    private fun <T> withIdentityStore(
        file: File,
        block: suspend (DeviceIdentityStore, DataStore<Preferences>) -> T,
    ): T = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
            scope = scope,
        )
        try {
            block(DeviceIdentityStore(store), store)
        } finally {
            job.cancelAndJoin()
        }
    }
}
