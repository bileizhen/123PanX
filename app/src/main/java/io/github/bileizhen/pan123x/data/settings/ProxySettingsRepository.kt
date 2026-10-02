package io.github.bileizhen.pan123x.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.bileizhen.pan123x.core.account.CredentialCrypto
import io.github.bileizhen.pan123x.core.network.ProxyConfig
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** All proxy data, including its password, is AES-GCM encrypted with Android Keystore. */
class ProxySettingsRepository(private val store: DataStore<Preferences>, private val crypto: CredentialCrypto, scope: CoroutineScope) {
    private val mutableConfig = MutableStateFlow(ProxyConfig())
    val state = mutableConfig.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    @Volatile var loaded = false
        private set
    private val initialRead = CompletableDeferred<Unit>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch {
            try {
                store.data.collect { prefs ->
                    try {
                        val sealed = prefs[KEY]
                        val proxy = if (sealed == null) ProxyConfig() else json.decodeFromString<ProxyConfig>(
                            crypto.open(Base64.getDecoder().decode(sealed), AAD).toString(Charsets.UTF_8))
                        require(proxy.validationError() == null)
                        mutableConfig.value = proxy
                        loaded = true
                        mutableError.value = null
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        loaded = false
                        mutableError.value = "无法读取代理设置，请重新配置；网络请求已暂停"
                    }
                    initialRead.complete(Unit)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { loaded = false; mutableError.value = "无法读取代理设置，请稍后重试"; initialRead.complete(Unit) }
        }
    }

    suspend fun awaitLoaded() = initialRead.await()

    suspend fun save(config: ProxyConfig) {
        require(config.validationError() == null) { config.validationError().orEmpty() }
        val sealed = crypto.seal(json.encodeToString(config).toByteArray(Charsets.UTF_8), AAD)
        store.edit { it[KEY] = Base64.getEncoder().encodeToString(sealed) }
        // Publish only after the durable write, before a caller starts its next request.
        mutableConfig.value = config; loaded = true; mutableError.value = null
    }

    private companion object {
        val KEY = stringPreferencesKey("encrypted_proxy")
        const val AAD = "123PanX:network-proxy:v1"
    }
}
