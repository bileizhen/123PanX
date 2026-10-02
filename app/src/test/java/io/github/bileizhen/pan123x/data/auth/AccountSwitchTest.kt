package io.github.bileizhen.pan123x.data.auth

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.DeviceIdentityStore
import io.github.bileizhen.pan123x.core.account.FakeCrypto
import io.github.bileizhen.pan123x.core.account.PlainCredential
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountDao
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.DeviceProfile
import io.github.bileizhen.pan123x.core.network.PanAuthApi
import io.github.bileizhen.pan123x.core.network.UserInfoDto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M7 多账户切换 / 移除的行为测试：装配方式与 [AuthRepositoryTest] 相同——
 * 真实 DataStore + FakeCrypto + 手写 API 替身；元数据走 [RoomAccountMetadataStore] 直通内存
 * [AccountDao] 替身，与生产"同一行来源"一致。不触网、不依赖 Room / AndroidKeyStore。
 */
class AccountSwitchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val initialProfile = DeviceProfile(
        osVersion = "Android_13",
        deviceType = "M2011K2C",
        loginUuid = "0123456789abcdef0123456789abcdef",
    )

    /** 手写替身：脚本化 login / getUserInfo 结果并记录调用次数。 */
    private class FakeAuthApi : PanAuthApi {
        var loginResult: ApiResult<String> = ApiResult.NetworkError("not configured")
        val loginCalls = mutableListOf<Pair<String, String>>()
        val userInfoCalls = mutableListOf<Int>()
        private val userInfoQueue = ArrayDeque<ApiResult<UserInfoDto>>()

        fun enqueueUserInfo(result: ApiResult<UserInfoDto>) {
            userInfoQueue.addLast(result)
        }

        override suspend fun login(passport: String, password: String): ApiResult<String> {
            loginCalls.addLast(passport to password)
            return loginResult
        }

        override suspend fun getUserInfo(): ApiResult<UserInfoDto> {
            userInfoCalls.addLast(userInfoCalls.size + 1)
            return userInfoQueue.removeFirstOrNull() ?: ApiResult.NetworkError("not configured")
        }
    }

    /** 内存 AccountDao 替身：observeAccounts 用 StateFlow 模拟 Room 观测，记录删除调用。 */
    private class FakeAccountDao : AccountDao {
        private val rows = MutableStateFlow<List<AccountEntity>>(emptyList())
        val deletedAccountIds = mutableListOf<String>()

        override fun observeAccounts(): Flow<List<AccountEntity>> = rows

        override suspend fun get(accountId: String): AccountEntity? =
            rows.value.firstOrNull { it.accountId == accountId }

        override suspend fun upsert(account: AccountEntity) {
            rows.value = rows.value.filterNot { it.accountId == account.accountId } + account
        }

        override suspend fun delete(accountId: String) {
            deletedAccountIds.addLast(accountId)
            rows.value = rows.value.filterNot { it.accountId == accountId }
        }
    }

    private data class Fixture(
        val api: FakeAuthApi,
        val credentials: SecureCredentialStore,
        val identityStore: DeviceIdentityStore,
        val dao: FakeAccountDao,
        val metadata: AccountMetadataStore,
        val manager: AccountManager,
        val repository: AuthRepository,
    )

    private fun userInfo(
        uid: Long = 42L,
        nickname: String = "阿碧",
        spaceUsed: Long = 100L,
        spaceTotal: Long = 900L,
        spaceTemp: Long = 100L,
        headImage: String = "",
    ) = UserInfoDto(
        uid = uid,
        nickname = nickname,
        spaceUsed = spaceUsed,
        spaceTotal = spaceTotal,
        spaceTemp = spaceTemp,
        headImage = headImage,
    )

    /** 每个用例独立的临时凭据 / 指纹 DataStore 文件，作用域用例结束即取消。 */
    private fun withRepository(block: suspend Fixture.() -> Unit) = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val credentialData = PreferenceDataStoreFactory.create(
                storage = OkioStorage(
                    FileSystem.SYSTEM,
                    PreferencesSerializer,
                    producePath = { File(folder.root, "credentials.preferences_pb").absolutePath.toPath() },
                ),
                scope = scope,
            )
            val identityData = PreferenceDataStoreFactory.create(
                storage = OkioStorage(
                    FileSystem.SYSTEM,
                    PreferencesSerializer,
                    producePath = { File(folder.root, "identity.preferences_pb").absolutePath.toPath() },
                ),
                scope = scope,
            )
            val api = FakeAuthApi()
            val credentials = SecureCredentialStore(credentialData, FakeCrypto())
            val identityStore = DeviceIdentityStore(identityData)
            val dao = FakeAccountDao()
            val metadata = RoomAccountMetadataStore(dao)
            val manager = AccountManager(initialProfile)
            val repository = AuthRepository(api, credentials, identityStore, metadata, manager, AppLogger(), dao)
            // 启动恢复完成后才允许 login / switchTo（它们会等待恢复结束）。
            repository.restoreSession()
            block(Fixture(api, credentials, identityStore, dao, metadata, manager, repository))
        } finally {
            job.cancelAndJoin()
        }
    }

    /** 走真实登录链落一个账户（凭据 + 元数据行），返回其 accountId。 */
    private suspend fun Fixture.loginAccount(
        passport: String,
        token: String,
        nickname: String,
        uid: Long,
    ): String {
        api.loginResult = ApiResult.Success(token)
        api.enqueueUserInfo(ApiResult.Success(userInfo(nickname = nickname, uid = uid)))
        repository.login(passport, "p@ss")
        return SecureCredentialStore.accountIdFor(passport)
    }

    @Test
    fun switchToFailsWithoutSavedCredential() = withRepository {
        val outcome = repository.switchTo("deadbeef000000000000000000000000")

        assertEquals(SwitchOutcome.Failed("该账户没有保存的登录凭据"), outcome)
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertNull(manager.current())
        // 无凭据时不应发起任何网络请求。
        assertTrue(api.userInfoCalls.isEmpty())
    }

    @Test
    fun switchToActivatesSavedAccountAndRestoresMetadata() = withRepository {
        val idA = loginAccount("a@example.com", "Bearer token-1", "阿碧", 42L)
        val idB = loginAccount("b@example.com", "Bearer token-2", "小碧", 43L)
        api.enqueueUserInfo(ApiResult.Success(userInfo(nickname = "阿碧", uid = 42L)))

        val outcome = repository.switchTo(idA)

        assertEquals(SwitchOutcome.Switched("阿碧"), outcome)
        assertEquals(SessionState.Ready(idA, "阿碧", "42"), manager.state.value)
        assertEquals("Bearer token-1", manager.current())
        assertEquals(idA, credentials.active()?.accountId)
        // accounts 行存在且昵称 / UID 与用户信息一致（外键级联的前提）。
        assertEquals("阿碧", metadata.get(idA)?.displayName)
        assertEquals("42", metadata.get(idA)?.uid)
        // 未移除任何凭据：另一账户仍可切换。
        assertEquals(setOf(idA, idB), credentials.accountIds())
    }

    @Test
    fun switchToToleratesUserInfoFailureWithPassportFallbackAndPlaceholderRow() = withRepository {
        loginAccount("a@example.com", "Bearer token-1", "阿碧", 42L)
        val idB = SecureCredentialStore.accountIdFor("b@example.com")
        credentials.save(
            PlainCredential(
                accountId = idB,
                passport = "b@example.com",
                password = "p@ss",
                authorization = "Bearer token-b",
                identity = identityStore.loadOrCreate(),
            ),
        )
        api.enqueueUserInfo(ApiResult.NetworkError("timeout"))

        val outcome = repository.switchTo(idB)

        assertEquals(SwitchOutcome.Switched("b@example.com"), outcome)
        assertEquals(SessionState.Ready(idB, "b@example.com", ""), manager.state.value)
        // 用户信息失败不阻塞切换，但补齐占位行避免后续文件写入触发 FOREIGN KEY 崩溃。
        assertEquals("b@example.com", metadata.get(idB)?.displayName)
    }

    @Test
    fun removeInactiveAccountKeepsSessionAndCleansLocal() = withRepository {
        val idA = loginAccount("a@example.com", "Bearer token-1", "阿碧", 42L)
        val idB = loginAccount("b@example.com", "Bearer token-2", "小碧", 43L)

        val removed = repository.removeAccount(idA)

        assertTrue(removed)
        assertTrue(dao.deletedAccountIds.contains(idA))
        assertNull(dao.get(idA))
        assertEquals(setOf(idB), credentials.accountIds())
        // 会话不因移除非活跃账户而受影响。
        assertEquals(SessionState.Ready(idB, "小碧", "43"), manager.state.value)
        assertEquals("Bearer token-2", manager.current())
    }

    @Test
    fun removeActiveAccountSignsOut() = withRepository {
        val idA = loginAccount("a@example.com", "Bearer token-1", "阿碧", 42L)
        val idB = loginAccount("b@example.com", "Bearer token-2", "小碧", 43L)

        val removed = repository.removeAccount(idB)

        assertTrue(removed)
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertNull(manager.current())
        assertNull(credentials.active())
        assertTrue(dao.deletedAccountIds.contains(idB))
        assertNull(dao.get(idB))
        // 其他账户数据保留，可重新登录或再次切换。
        assertEquals(setOf(idA), credentials.accountIds())
        assertEquals("阿碧", dao.get(idA)?.displayName)
    }

    @Test
    fun removeUnknownAccountReturnsFalse() = withRepository {
        val removed = repository.removeAccount("deadbeef000000000000000000000000")

        assertFalse(removed)
        assertTrue(dao.deletedAccountIds.isEmpty())
        assertTrue(credentials.accountIds().isEmpty())
        assertEquals(SessionState.LoggedOut, manager.state.value)
    }
}
