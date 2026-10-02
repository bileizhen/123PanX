package io.github.bileizhen.pan123x.feature.login

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.PanXApplication
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.PlainCredential
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.*
import io.github.bileizhen.pan123x.core.network.*
import io.github.bileizhen.pan123x.data.auth.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Write real Room file caches immediately after Ready, before the profile response arrives. */
@RunWith(AndroidJUnit4::class)
class PasswordLoginAccountInstrumentedTest {
    private suspend fun scenario(kind: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PanXApplication).container
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val passport = "$kind@fixture.invalid"
        val accountId = SecureCredentialStore.accountIdFor(passport)
        val manager = AccountManager()
        if (kind != "restore") manager.onLogout()
        try {
            val identity = container.deviceIdentityStore.loadOrCreate()
            if (kind != "password") container.credentialStore.save(PlainCredential(accountId, passport, "fixture-password", "Bearer fixture", identity))
            val api = object : PanAuthApi {
                override suspend fun login(passport: String, password: String) = ApiResult.Success("Bearer fixture")
                override suspend fun getUserInfo(authorization: String?): ApiResult<UserInfoDto> {
                    val ready = manager.state.value as SessionState.Ready
                    // Exactly the write that caused the production SQLite foreign-key crash.
                    database.cloudFileDao().replaceDirectoryWithState(ready.accountId, 0,
                        listOf(CloudFileEntity(ready.accountId, 1, 0, "fixture.txt", false, 12)), 1, true, 1)
                    assertNotNull(database.accountDao().get(ready.accountId))
                    return ApiResult.NetworkError("profile unavailable")
                }
            }
            val metadata = object : AccountMetadataStore {
                override suspend fun get(accountId: String) = database.accountDao().get(accountId)
                override suspend fun upsert(account: AccountEntity) {
                    // Also exercise the exact window before the parent row is inserted.
                    (manager.state.value as? SessionState.Ready)?.takeIf { it.accountId == account.accountId }?.let {
                        database.directoryStateDao().upsert(DirectoryStateEntity(it.accountId, 0, 0, true, 1))
                    }
                    database.accountDao().upsert(account)
                }
            }
            val repository = AuthRepository(api, container.credentialStore, container.deviceIdentityStore,
                metadata, manager, container.logger)
            when (kind) {
                "password" -> assertEquals(LoginOutcome.Success, repository.login(passport, "fixture-password"))
                "switch" -> assertTrue(repository.switchTo(accountId) is SwitchOutcome.Switched)
                "restore" -> repository.restoreSession()
            }
            assertEquals(accountId, (manager.state.value as SessionState.Ready).accountId)
            assertNotNull(database.cloudFileDao().get(accountId, 1))
            assertNotNull(database.directoryStateDao().get(accountId, 0))
        } finally { container.credentialStore.clear(accountId); database.close() }
    }
    @Test fun passwordLoginSupportsImmediateFileWritesEvenWhenUserInfoFails() = runBlocking { scenario("password") }
    @Test fun switchingToSavedAccountPreparesMissingRowBeforeFileWrites() = runBlocking { scenario("switch") }
    @Test fun restoringSavedAccountPreparesMissingRowBeforeFileWrites() = runBlocking { scenario("restore") }

    @Test fun failedAccountWriteNeverPublishesRestoredSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PanXApplication).container
        val accountId = SecureCredentialStore.accountIdFor("broken@fixture.invalid")
        val manager = AccountManager()
        val metadata = object : AccountMetadataStore {
            override suspend fun get(accountId: String): AccountEntity? = null
            override suspend fun upsert(account: AccountEntity) { throw IOException("fixture disk failure") }
        }
        val api = object : PanAuthApi {
            override suspend fun login(passport: String, password: String): ApiResult<String> = error("Not used")
            override suspend fun getUserInfo(authorization: String?): ApiResult<UserInfoDto> = error("Unsafe Ready must not be published")
        }
        try {
            container.credentialStore.save(PlainCredential(accountId, "broken@fixture.invalid", "fixture", "Bearer fixture", container.deviceIdentityStore.loadOrCreate()))
            AuthRepository(api, container.credentialStore, container.deviceIdentityStore, metadata, manager, container.logger).restoreSession()
            assertEquals(SessionState.LoggedOut, manager.state.value)
            assertNull(manager.current())
            assertNotNull(container.credentialStore.active()) // Keep the credential for a later retry.
        } finally { container.credentialStore.clear(accountId) }
    }
}
