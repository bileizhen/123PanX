package io.github.bileizhen.pan123x.core.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.flow.first

/** Everything needed to restore a signed-in session; exists in plaintext only in memory. */
data class PlainCredential(
    val accountId: String,
    val passport: String,
    val password: String,
    val authorization: String,
    val identity: DeviceIdentity,
)

/**
 * Multi-account credential storage on top of Preferences DataStore (file "credentials").
 *
 * Only AES-GCM sealed blobs (base64 of `iv||ciphertext`, AAD = accountId) ever reach disk for
 * password and authorization; passport and the device fingerprint stay plaintext, matching the
 * reference implementation's save_file layout. Entries of all accounts coexist and an
 * `active_account_id` pointer marks the current one, so multi-account is wired from day one.
 * An entry that fails to decrypt is treated as corrupted: it is deleted and reported as absent
 * instead of crashing the app or surfacing a stacktrace.
 */
class SecureCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val crypto: CredentialCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Seals password/token with AAD = accountId and makes this account active. */
    suspend fun save(credential: PlainCredential) {
        val aad = credential.accountId
        // Crypto runs outside the edit transaction so keystore work never holds the file lock.
        val sealedPassword = crypto.seal(credential.password.toByteArray(Charsets.UTF_8), aad)
        val sealedAuthorization = crypto.seal(credential.authorization.toByteArray(Charsets.UTF_8), aad)
        dataStore.edit { preferences ->
            write(preferences, credential, sealedPassword, sealedAuthorization)
        }
    }

    /** A late token renewal must never reactivate an account the user has switched away from. */
    suspend fun renewIfActive(credential: PlainCredential, authorization: String): Boolean {
        val sealed = crypto.seal(authorization.toByteArray(Charsets.UTF_8), credential.accountId)
        var renewed = false
        dataStore.edit { preferences ->
            val current = decrypt(credential.accountId, preferences)
            if (preferences[Keys.activeAccountId] == credential.accountId && current?.authorization == credential.authorization) {
                preferences[Keys.tokenEnc(credential.accountId)] = encoder.encodeToString(sealed)
                preferences[Keys.updatedAt(credential.accountId)] = clock()
                renewed = true
            }
        }
        return renewed
    }

    /** Decrypts the active account, or null when none is active or its entry is corrupted. */
    suspend fun active(): PlainCredential? {
        val preferences = dataStore.data.first()
        val accountId = preferences[Keys.activeAccountId] ?: return null
        val credential = decrypt(accountId, preferences)
        if (credential == null) removeAccount(accountId)
        return credential
    }

    /** Points the active pointer at an existing account and returns its credential. */
    suspend fun activate(accountId: String): PlainCredential? {
        if (dataStore.data.first()[Keys.passport(accountId)] == null) return null
        dataStore.edit { preferences -> preferences[Keys.activeAccountId] = accountId }
        return active()
    }

    suspend fun clear(accountId: String) = removeAccount(accountId)

    suspend fun clearActive() {
        val accountId = dataStore.data.first()[Keys.activeAccountId] ?: return
        removeAccount(accountId)
    }

    /**
     * 已保存凭据的全部账户 ID（多账户切换的可切换集合）。
     *
     * 实现依据 [Keys.passport] 的键名规则 `account_<accountId>_passport`：凡存在 passport
     * 键的账户即视为已保存，无需解密（损坏条目由读取路径按缺失处理，不影响枚举）。
     */
    suspend fun accountIds(): Set<String> = dataStore.data.first().asMap().keys.mapNotNull { key ->
        val name = key.name
        if (name.startsWith(Keys.PASSPORT_PREFIX) && name.endsWith(Keys.PASSPORT_SUFFIX)) {
            name.removePrefix(Keys.PASSPORT_PREFIX).removeSuffix(Keys.PASSPORT_SUFFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }.toSet()

    private suspend fun removeAccount(accountId: String) {
        dataStore.edit { preferences ->
            Keys.all(accountId).forEach { key -> preferences.remove(key) }
            if (preferences[Keys.activeAccountId] == accountId) preferences.remove(Keys.activeAccountId)
        }
    }

    /** Returns null when any field is missing or the sealed blobs fail to decrypt. */
    private fun decrypt(accountId: String, preferences: Preferences): PlainCredential? {
        val passport = preferences[Keys.passport(accountId)] ?: return null
        val encodedPassword = preferences[Keys.passwordEnc(accountId)] ?: return null
        val encodedAuthorization = preferences[Keys.tokenEnc(accountId)] ?: return null
        val deviceType = preferences[Keys.deviceType(accountId)] ?: return null
        val osVersion = preferences[Keys.osVersion(accountId)] ?: return null
        val loginUuid = preferences[Keys.loginUuid(accountId)] ?: return null
        return try {
            PlainCredential(
                accountId = accountId,
                passport = passport,
                password = String(crypto.open(decoder.decode(encodedPassword), accountId), Charsets.UTF_8),
                authorization = String(crypto.open(decoder.decode(encodedAuthorization), accountId), Charsets.UTF_8),
                identity = DeviceIdentity(deviceType, osVersion, loginUuid),
            )
        } catch (failure: GeneralSecurityException) {
            // Wrong AAD, tampered ciphertext, or a lost keystore key: treat as corruption.
            null
        } catch (failure: IllegalArgumentException) {
            // Malformed base64 never comes from our own writer: treat as corruption.
            null
        }
    }

    private fun write(
        preferences: MutablePreferences,
        credential: PlainCredential,
        sealedPassword: ByteArray,
        sealedAuthorization: ByteArray,
    ) {
        val accountId = credential.accountId
        preferences[Keys.passport(accountId)] = credential.passport
        preferences[Keys.passwordEnc(accountId)] = encoder.encodeToString(sealedPassword)
        preferences[Keys.tokenEnc(accountId)] = encoder.encodeToString(sealedAuthorization)
        preferences[Keys.deviceType(accountId)] = credential.identity.deviceType
        preferences[Keys.osVersion(accountId)] = credential.identity.osVersion
        preferences[Keys.loginUuid(accountId)] = credential.identity.loginUuid
        preferences[Keys.updatedAt(accountId)] = clock()
        preferences[Keys.activeAccountId] = accountId
    }

    companion object {
        /**
         * Stable account id derived from the login passport (SHA-256 hex, first 32 chars), so
         * pointing `active_account_id` at another account can never mix two passports' entries.
         */
        fun accountIdFor(passport: String): String {
            val normalized = passport.trim().lowercase(Locale.ROOT)
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(32)
        }

        // java.util.Base64 emits RFC 4648 without line breaks: equivalent to Base64.NO_WRAP.
        private val encoder = Base64.getEncoder()
        private val decoder = Base64.getDecoder()
    }

    private object Keys {
        val activeAccountId = stringPreferencesKey("active_account_id")

        // accountIds 按这两个常量扫描 passport 键；字面量与上方 passport 的键名规则保持一致。
        const val PASSPORT_PREFIX = "account_"
        const val PASSPORT_SUFFIX = "_passport"

        fun passport(accountId: String) = stringPreferencesKey("account_${accountId}_passport")
        fun passwordEnc(accountId: String) = stringPreferencesKey("account_${accountId}_password_enc")
        fun tokenEnc(accountId: String) = stringPreferencesKey("account_${accountId}_token_enc")
        fun deviceType(accountId: String) = stringPreferencesKey("account_${accountId}_devicetype")
        fun osVersion(accountId: String) = stringPreferencesKey("account_${accountId}_osversion")
        fun loginUuid(accountId: String) = stringPreferencesKey("account_${accountId}_loginuuid")
        fun updatedAt(accountId: String) = longPreferencesKey("account_${accountId}_updated_at")

        fun all(accountId: String): List<Preferences.Key<*>> = listOf(
            passport(accountId),
            passwordEnc(accountId),
            tokenEnc(accountId),
            deviceType(accountId),
            osVersion(accountId),
            loginUuid(accountId),
            updatedAt(accountId),
        )
    }
}
