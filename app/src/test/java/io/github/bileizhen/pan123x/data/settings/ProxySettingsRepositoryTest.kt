package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.github.bileizhen.pan123x.core.account.FakeCrypto
import io.github.bileizhen.pan123x.core.network.ProxyConfig
import io.github.bileizhen.pan123x.core.network.ProxyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProxySettingsRepositoryTest {
    @Test fun proxySaveCompletionRetainsRevisionAndDismissalSignal() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val owner = androidx.lifecycle.ViewModelStore()
        try {
            val proxies = ProxySettingsRepository(MemoryStore(), FakeCrypto(), backgroundScope)
            val settings = SettingsRepository(MemoryStore(), backgroundScope, io.github.bileizhen.pan123x.core.logging.AppLogger())
            val vm = io.github.bileizhen.pan123x.feature.settings.SettingsViewModel(settings, io.github.bileizhen.pan123x.core.logging.AppLogger(), proxies)
            owner.put("settings", vm)
            runCurrent()
            vm.saveProxy(ProxyConfig(true, "HTTP", "127.0.0.1", 8080))
            runCurrent()
            assertEquals(1L, vm.uiState.value.proxySavedRevision)
            assertFalse(vm.uiState.value.busy); assertNotNull(vm.uiState.value.message); assertTrue(vm.uiState.value.proxy.enabled)
            vm.saveProxy(ProxyConfig(true, "HTTP", "127.0.0.1", 9090))
            runCurrent(); assertEquals(2L, vm.uiState.value.proxySavedRevision)
        } finally { owner.clear(); kotlinx.coroutines.Dispatchers.resetMain() }
    }
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    @Test fun proxySecretsAreEncryptedAndSurviveRepositoryRecreation() = runTest {
        val store = MemoryStore(); val crypto = FakeCrypto()
        val first = ProxySettingsRepository(store, crypto, backgroundScope)
        runCurrent(); first.awaitLoaded()
        val expected = ProxyConfig(true, "SOCKS5", "proxy.example", 1080, "private-user", "private-password")
        first.save(expected)
        val serialized = store.data.value.asMap().values.joinToString()
        assertFalse(serialized.contains("private-user")); assertFalse(serialized.contains("private-password")); assertFalse(serialized.contains("proxy.example"))
        val restored = ProxySettingsRepository(store, crypto, backgroundScope)
        runCurrent(); restored.awaitLoaded(); assertEquals(expected, restored.state.value); assertTrue(restored.loaded)
        val encryptedBefore = serialized
        first.save(expected); assertNotEquals(encryptedBefore, store.data.value.asMap().values.joinToString())
    }

    @Test fun corruptEncryptedBlobBlocksNetworkingUntilReconfigured() = runTest {
        val store = MemoryStore()
        store.data.value = mutablePreferencesOf(stringPreferencesKey("encrypted_proxy") to "not-ciphertext")
        val repository = ProxySettingsRepository(store, FakeCrypto(), backgroundScope)
        runCurrent(); repository.awaitLoaded(); assertFalse(repository.loaded); assertNotNull(repository.error.value)
        repository.save(ProxyConfig()); runCurrent(); assertTrue(repository.loaded); assertNull(repository.error.value)
    }

    @Test fun rejectedConfigurationDoesNotOverwriteSavedState() = runTest {
        val store = MemoryStore(); val repository = ProxySettingsRepository(store, FakeCrypto(), backgroundScope)
        runCurrent(); repository.awaitLoaded()
        try { repository.save(ProxyConfig(enabled = true)); fail() } catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().isNotBlank()) }
        assertTrue(store.data.value.asMap().isEmpty()); assertFalse(repository.state.value.enabled)
    }

    @Test fun eachModeAndBypassAreEncryptedAndRestoredTogether() = runTest {
        val store = MemoryStore(); val crypto = FakeCrypto()
        val repository = ProxySettingsRepository(store, crypto, backgroundScope)
        runCurrent(); repository.awaitLoaded()
        for (mode in ProxyMode.entries) {
            val expected = ProxyConfig(host = "proxy.example", port = 1080, username = "private-user",
                password = "private-password", bypass = "private.example, <local>").withMode(mode)
            repository.save(expected)
            assertFalse(store.data.value.asMap().values.joinToString().contains("private.example"))
            val restored = ProxySettingsRepository(store, crypto, backgroundScope)
            runCurrent(); restored.awaitLoaded()
            assertEquals(expected, restored.state.value)
            assertEquals(mode, restored.state.value.effectiveMode)
        }
    }

    @Test fun legacyEncryptedJsonWithoutModeOrBypassKeepsManualRoute() = runTest {
        val store = MemoryStore(); val crypto = FakeCrypto()
        val legacy = """{"enabled":true,"type":"HTTP","host":"proxy.example","port":8080,"username":"user","password":"secret"}"""
        store.data.value = mutablePreferencesOf(stringPreferencesKey("encrypted_proxy") to
            java.util.Base64.getEncoder().encodeToString(crypto.seal(legacy.toByteArray(), "123PanX:network-proxy:v1")))
        val repository = ProxySettingsRepository(store, crypto, backgroundScope)
        runCurrent(); repository.awaitLoaded()
        assertTrue(repository.loaded)
        assertEquals(ProxyMode.MANUAL, repository.state.value.effectiveMode)
        assertEquals("", repository.state.value.bypass)
        assertEquals("secret", repository.state.value.password)
        repository.save(repository.state.value.withMode(ProxyMode.SYSTEM))
        val restored = ProxySettingsRepository(store, crypto, backgroundScope)
        runCurrent(); restored.awaitLoaded()
        assertEquals(ProxyMode.SYSTEM, restored.state.value.effectiveMode)
        assertEquals("secret", restored.state.value.password)
    }
}
