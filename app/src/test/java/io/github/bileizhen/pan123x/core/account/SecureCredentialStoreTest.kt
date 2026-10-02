package io.github.bileizhen.pan123x.core.account

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureCredentialStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun savePersistsSealedSecretsAndActiveRoundTrips() {
        val file = File(folder.root, "credentials.preferences_pb")
        withCredentialStore(file, clock = { 1234L }) { store, raw ->
            val credential = credentialFor("user@example.com")
            store.save(credential)
            assertEquals(credential, store.active())

            // No plaintext password or token may ever reach the persisted file.
            val persisted = raw.data.first().asMap().values.joinToString(" ") { it.toString() }
            assertFalse(persisted.contains("password-user@example.com"))
            assertFalse(persisted.contains("Bearer token-user@example.com"))
            assertEquals(1234L, raw.data.first()[updatedAtKey(credential.accountId)])
        }
    }

    @Test
    fun accountIdIsStableAndNormalizesCaseAndWhitespace() {
        val expected = SecureCredentialStore.accountIdFor("user@example.com")
        assertEquals(expected, SecureCredentialStore.accountIdFor("  USER@Example.COM\t"))
        assertEquals(32, expected.length)
        assertTrue("accountId must be 32 lowercase hex chars: $expected", expected.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals(expected, SecureCredentialStore.accountIdFor("other@example.com"))
    }

    @Test
    fun ciphertextSealedForOneAccountCannotServeAnother() {
        val file = File(folder.root, "credentials.preferences_pb")
        withCredentialStore(file) { store, raw ->
            val first = credentialFor("first@example.com")
            val second = credentialFor("second@example.com")
            store.save(first)
            store.save(second)

            // Move the blobs sealed with AAD=first under second's entry keys.
            val snapshot = raw.data.first()
            raw.edit {
                it[passwordEncKey(second.accountId)] = snapshot[passwordEncKey(first.accountId)]!!
                it[tokenEncKey(second.accountId)] = snapshot[tokenEncKey(first.accountId)]!!
            }

            // AAD mismatch: the corrupted entry is removed and reported as absent...
            assertNull(store.active())
            assertNull(raw.data.first()[passportKey(second.accountId)])
            assertNull(store.activate(second.accountId))
            // ...while the account the blobs truly belong to is untouched.
            assertEquals(first, store.activate(first.accountId))
        }
    }

    @Test
    fun tamperedCiphertextIsDiscardedInsteadOfCrashing() {
        val file = File(folder.root, "credentials.preferences_pb")
        withCredentialStore(file) { store, raw ->
            val credential = credentialFor("user@example.com")
            store.save(credential)

            val sealed = Base64.getDecoder().decode(raw.data.first()[passwordEncKey(credential.accountId)])
            sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 0x55).toByte()
            raw.edit { it[passwordEncKey(credential.accountId)] = Base64.getEncoder().encodeToString(sealed) }

            assertNull(store.active())
            assertNull(raw.data.first()[passportKey(credential.accountId)])
            assertNull(store.activate(credential.accountId))
        }
    }

    @Test
    fun clearActiveOnlyRemovesTheActiveAccount() {
        val file = File(folder.root, "credentials.preferences_pb")
        withCredentialStore(file) { store, raw ->
            val first = credentialFor("first@example.com")
            val second = credentialFor("second@example.com")
            store.save(first)
            store.save(second)

            store.clearActive()
            assertNull(store.active())
            // The inactive account's entry group survives the pointer cleanup.
            assertNotNull(raw.data.first()[passportKey(first.accountId)])
            assertEquals(first, store.activate(first.accountId))
        }
    }

    @Test
    fun clearingAnInactiveAccountKeepsTheActiveSession() {
        val file = File(folder.root, "credentials.preferences_pb")
        withCredentialStore(file) { store, _ ->
            val first = credentialFor("first@example.com")
            val second = credentialFor("second@example.com")
            store.save(first)
            store.save(second)

            store.clear(first.accountId)
            assertEquals(second, store.active())
            assertNull(store.activate(first.accountId))
        }
    }

    @Test
    fun activatingAnUnknownAccountChangesNothing() {
        val file = File(folder.root, "credentials.preferences_pb")
        withCredentialStore(file) { store, _ ->
            val credential = credentialFor("user@example.com")
            store.save(credential)

            assertNull(store.activate(SecureCredentialStore.accountIdFor("ghost@example.com")))
            assertEquals(credential, store.active())
        }
    }

    private fun credentialFor(passport: String) = PlainCredential(
        accountId = SecureCredentialStore.accountIdFor(passport),
        passport = passport,
        password = "password-$passport",
        authorization = "Bearer token-$passport",
        identity = DeviceIdentity("M2102K1AC", "Android_12", "abcdef0123456789abcdef0123456789"),
    )

    private fun passportKey(accountId: String) = stringPreferencesKey("account_${accountId}_passport")
    private fun passwordEncKey(accountId: String) = stringPreferencesKey("account_${accountId}_password_enc")
    private fun tokenEncKey(accountId: String) = stringPreferencesKey("account_${accountId}_token_enc")
    private fun updatedAtKey(accountId: String) = longPreferencesKey("account_${accountId}_updated_at")

    /** Real file-backed DataStore per SettingsPersistenceTest; store scope is joined before returning. */
    private fun <T> withCredentialStore(
        file: File,
        clock: () -> Long = { 0L },
        block: suspend (SecureCredentialStore, DataStore<Preferences>) -> T,
    ): T = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
            scope = scope,
        )
        try {
            block(SecureCredentialStore(store, FakeCrypto(), clock), store)
        } finally {
            job.cancelAndJoin()
        }
    }
}
