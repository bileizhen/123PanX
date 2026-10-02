package io.github.bileizhen.pan123x.feature.login

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.PanXApplication
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.database.AppDatabase
import io.github.bileizhen.pan123x.core.database.DirectoryStateEntity
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanAuthApi
import io.github.bileizhen.pan123x.core.network.UserInfoDto
import io.github.bileizhen.pan123x.data.auth.AccountMetadataStore
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.auth.QrVerifyOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room foreign keys with isolated credentials; HTTP headers are covered by PanApiTest. */
@RunWith(AndroidJUnit4::class)
class QrLoginAccountInstrumentedTest {
    @Test fun confirmedQrAccountExistsBeforeDirectoryCacheCanBeWritten() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PanXApplication).container
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val manager = AccountManager().apply { onLogout() }
            val metadata = object : AccountMetadataStore {
                override suspend fun get(accountId: String) = database.accountDao().get(accountId)
                override suspend fun upsert(account: AccountEntity) {
                    // Force a cache write in the pre-insert window if Ready was published early.
                    (manager.state.value as? SessionState.Ready)?.let {
                        database.directoryStateDao().upsert(DirectoryStateEntity(it.accountId, 0, 0, true, 1))
                    }
                    database.accountDao().upsert(account)
                }
            }
            val api = object : PanAuthApi {
                override suspend fun login(passport: String, password: String): ApiResult<String> = error("Not used")
                override suspend fun getUserInfo(authorization: String?): ApiResult<UserInfoDto> {
                    assertEquals("Bearer instrumentation-candidate", authorization)
                    assertEquals(SessionState.LoggedOut, manager.state.value)
                    return ApiResult.Success(UserInfoDto(uid = 42, nickname = "QR Fixture"))
                }
            }
            val repository = AuthRepository(api, container.credentialStore, container.deviceIdentityStore, metadata, manager, container.logger)
            assertTrue(repository.qrVerify("instrumentation-candidate") is QrVerifyOutcome.Accepted)
            val ready = manager.state.value as SessionState.Ready
            assertNotNull(database.accountDao().get(ready.accountId))
            database.cloudFileDao().replaceDirectoryWithState(ready.accountId, 0, emptyList(), 0, true, 2)
            assertNotNull(database.directoryStateDao().get(ready.accountId, 0))
        } finally {
            database.close()
        }
    }
}
