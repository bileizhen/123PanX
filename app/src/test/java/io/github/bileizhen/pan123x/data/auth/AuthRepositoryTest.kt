package io.github.bileizhen.pan123x.data.auth

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.DeviceIdentity
import io.github.bileizhen.pan123x.core.account.DeviceIdentityStore
import io.github.bileizhen.pan123x.core.account.FakeCrypto
import io.github.bileizhen.pan123x.core.account.PlainCredential
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.DeviceProfile
import io.github.bileizhen.pan123x.core.network.PanAuthApi
import io.github.bileizhen.pan123x.core.network.UserInfoDto
import io.github.bileizhen.pan123x.core.network.PanDeviceApi
import io.github.bileizhen.pan123x.core.network.LoginDeviceDto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AuthRepository 行为测试：真实 DataStore + FakeCrypto（AES-GCM）+ 真实 DeviceIdentityStore
 * + 手写 API 替身与内存元数据仓（不触网、不依赖 Room / AndroidKeyStore）。
 */
class AuthRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val initialProfile = DeviceProfile(
        osVersion = "Android_13",
        deviceType = "M2011K2C",
        loginUuid = "0123456789abcdef0123456789abcdef",
    )

    /** 手写替身：脚本化 login / getUserInfo 结果并记录调用。 */
    private class FakeAuthApi(
        var loginResult: ApiResult<String> = ApiResult.NetworkError("not configured"),
    ) : PanAuthApi, PanDeviceApi {
        val loginCalls = mutableListOf<Pair<String, String>>()
        var userInfoCalls = 0
        var beforeUserInfo: (suspend () -> Unit)? = null
        var beforeLogin: (suspend () -> Unit)? = null
        var beforeDevices: (suspend () -> Unit)? = null
        var deviceCalls = 0
        val deviceResults = ArrayDeque<ApiResult<List<LoginDeviceDto>>>()
        override suspend fun getLoginDevices(): ApiResult<List<LoginDeviceDto>> {
            deviceCalls++
            beforeDevices?.invoke()
            return deviceResults.removeFirstOrNull() ?: ApiResult.NetworkError("not configured")
        }
        private val userInfoQueue = ArrayDeque<ApiResult<UserInfoDto>>()

        fun enqueueUserInfo(result: ApiResult<UserInfoDto>) {
            userInfoQueue.addLast(result)
        }

        override suspend fun login(passport: String, password: String): ApiResult<String> {
            loginCalls.addLast(passport to password)
            beforeLogin?.invoke()
            return loginResult
        }

        override suspend fun getUserInfo(authorization: String?): ApiResult<UserInfoDto> {
            userInfoCalls++
            beforeUserInfo?.invoke()
            return userInfoQueue.removeFirstOrNull() ?: ApiResult.NetworkError("not configured")
        }
    }

    private class InMemoryMetadataStore : AccountMetadataStore {
        val accounts = linkedMapOf<String, AccountEntity>()

        override suspend fun upsert(account: AccountEntity) {
            accounts[account.accountId] = account
        }

        override suspend fun get(accountId: String): AccountEntity? = accounts[accountId]
    }

    private class Fixture(
        val api: FakeAuthApi,
        val credentials: SecureCredentialStore,
        val identityStore: DeviceIdentityStore,
        val metadata: InMemoryMetadataStore,
        val manager: AccountManager,
        val repository: AuthRepository,
        val clock: TestClock,
    )
    private class TestClock(var now: Long = 0)

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
            val metadata = InMemoryMetadataStore()
            val manager = AccountManager(initialProfile)
            val clock = TestClock()
            val repository = AuthRepository(api, credentials, identityStore, metadata, manager, AppLogger(), clock = { clock.now })
            block(Fixture(api, credentials, identityStore, metadata, manager, repository, clock))
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun loginSuccessPersistsCredentialsAndMetadataWithCapacityConvention() = withRepository {
        api.loginResult = ApiResult.Success("Bearer token-1")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.restoreSession()

        val outcome = repository.login("  user@example.com  ", "p@ss")

        assertEquals(LoginOutcome.Success, outcome)
        assertEquals("user@example.com" to "p@ss", api.loginCalls.single())
        val credential = credentials.active()
        assertEquals(SecureCredentialStore.accountIdFor("user@example.com"), credential?.accountId)
        assertEquals("user@example.com", credential?.passport)
        assertEquals("p@ss", credential?.password)
        assertEquals("Bearer token-1", credential?.authorization)
        // 落盘身份即持久化指纹：后续请求头与凭据恢复共用同一身份
        assertEquals(identityStore.current(), credential?.identity)

        val accountId = SecureCredentialStore.accountIdFor("user@example.com")
        val account = metadata.accounts[accountId]
        assertEquals("阿碧", account?.displayName)
        assertEquals("42", account?.uid)
        assertEquals(100L, account?.usedBytes)
        // 容量口径：spaceTotal(900) + spaceTemp(100)
        assertEquals(1000L, account?.totalBytes)
        assertNull(account?.avatarUri)

        assertEquals(SessionState.Ready(accountId, "阿碧", "42"), manager.state.value)
        assertEquals("Bearer token-1", manager.current())
    }

    @Test fun cloudDetailsPersistPerAccountWithMaskedPassportAndServerTotals() = withRepository {
        manager.onLogout()
        api.loginResult = ApiResult.Success("Bearer cloud-token")
        api.enqueueUserInfo(ApiResult.Success(UserInfoDto(
            uid = 1000000001, nickname = "fixture-user", passport = 13800138000,
            spaceUsed = 55, spaceTotal = 2000, spaceTemp = 10, fileCount = 22,
            vip = true, vipLevel = 2, vipExpire = "2026-12-01",
            directTraffic = 4096, professionalSpacePermanent = 50,
            professionalSpaceUsed = 1, standardSpacePermanent = 1950, standardSpaceUsed = 54,
        )))
        repository.login("13800138000", "test-password")
        val account = metadata.accounts.values.single()
        assertEquals("138****8000", account.maskedPassport)
        assertTrue(account.hasCloudInfo)
        assertEquals(22L, account.fileCount)
        assertEquals(4096L, account.directTrafficBytes)
        assertEquals(2000L, account.permanentBytes)
        assertEquals(2010L, account.totalBytes)
        assertEquals(50L, account.professionalTotalBytes)
        assertEquals(54L, account.standardUsedBytes)
        assertEquals("2026-12-01", account.vipExpire)
        api.enqueueUserInfo(ApiResult.NetworkError("offline"))
        repository.refreshUserInfo()
        assertEquals(account, metadata.accounts.values.single())
    }

    @Test fun devicesReauthenticateOnlyOnceAndDoNotPersistDeviceCredentials() = withRepository {
        manager.onLogout()
        api.loginResult = ApiResult.Success("Bearer first")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.login("user@example.com", "password")
        val metadataBefore = metadata.accounts.toMap()
        api.loginResult = ApiResult.Success("Bearer renewed")
        api.deviceResults.addLast(ApiResult.SessionExpired)
        val device = LoginDeviceDto(name = "Xiaomi", current = true)
        api.deviceResults.addLast(ApiResult.Success(listOf(device)))
        assertEquals(ApiResult.Success(listOf(device)), repository.getLoginDevices())
        assertEquals(2, api.deviceCalls)
        assertEquals(2, api.loginCalls.size)
        assertEquals("Bearer renewed", manager.current())
        assertEquals(metadataBefore, metadata.accounts)
    }

    @Test fun devicesIgnoreResultsAndExpiredSessionsFromAnOldAccount() = withRepository {
        manager.onLoginSuccess("a", "A", "1", "Bearer a")
        api.deviceResults.addLast(ApiResult.SessionExpired)
        api.beforeDevices = { manager.onLoginSuccess("b", "B", "2", "Bearer b") }
        assertTrue(repository.getLoginDevices() is ApiResult.NetworkError)
        assertEquals("b", (manager.state.value as SessionState.Ready).accountId)
        assertEquals("Bearer b", manager.current())
        assertTrue(api.loginCalls.isEmpty())
        assertTrue(metadata.accounts.isEmpty())
    }

    @Test fun devicesIgnoreLateResponseAfterLogoutAndLoginOfTheSameAccount() = withRepository {
        manager.onLoginSuccess("a", "A", "1", "Bearer old")
        api.deviceResults.addLast(ApiResult.Success(listOf(LoginDeviceDto(name = "Old session"))))
        api.beforeDevices = { manager.onLogout(); manager.onLoginSuccess("a", "A", "1", "Bearer new") }
        assertTrue(repository.getLoginDevices() is ApiResult.NetworkError)
        assertEquals("Bearer new", manager.current())
    }

    @Test fun repeatedDeviceSessionExpiryLogsOutAfterOneRenewal() = withRepository {
        manager.onLogout()
        api.loginResult = ApiResult.Success("Bearer first")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.login("user@example.com", "password")
        api.deviceResults.addLast(ApiResult.SessionExpired)
        api.deviceResults.addLast(ApiResult.SessionExpired)
        assertTrue(repository.getLoginDevices() is ApiResult.SessionExpired)
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertEquals(2, api.deviceCalls)
        assertEquals(2, api.loginCalls.size)
    }

    @Test
    fun loginApiFailurePassesServerMessageThroughAndPersistsNothing() = withRepository {
        api.loginResult = ApiResult.ApiError(401, "用户名或密码错误")
        repository.restoreSession()

        val outcome = repository.login("user@example.com", "wrong")

        assertEquals(LoginOutcome.Failure("用户名或密码错误"), outcome)
        assertNull(credentials.active())
        assertTrue(metadata.accounts.isEmpty())
        assertEquals(SessionState.LoggedOut, manager.state.value)
    }

    @Test
    fun loginApiFailureWithBlankMessageFallsBackToErrorCode() = withRepository {
        api.loginResult = ApiResult.ApiError(401, " ")
        repository.restoreSession()

        assertEquals(LoginOutcome.Failure("登录失败（错误码 401）"), repository.login("user@example.com", "x"))
    }

    @Test
    fun loginNetworkFailureMapsToReadableMessage() = withRepository {
        api.loginResult = ApiResult.NetworkError("connect timeout")
        repository.restoreSession()

        val outcome = repository.login("user@example.com", "p@ss")

        assertEquals(LoginOutcome.Failure("网络连接失败，请检查网络后重试"), outcome)
        assertNull(credentials.active())
    }

    @Test
    fun userInfoFailureDoesNotBlockLoginSuccess() = withRepository {
        api.loginResult = ApiResult.Success("Bearer token-1")
        api.enqueueUserInfo(ApiResult.NetworkError("timeout"))
        repository.restoreSession()

        val outcome = repository.login("user@example.com", "p@ss")

        assertEquals(LoginOutcome.Success, outcome)
        assertEquals("Bearer token-1", credentials.active()?.authorization)
        assertTrue(metadata.accounts.isEmpty())
        val state = manager.state.value
        assertTrue(state is SessionState.Ready)
        assertEquals("user@example.com", (state as SessionState.Ready).displayName)
        assertEquals("", state.uid)
    }

    @Test
    fun blankPassportFailsWithoutCallingApi() = withRepository {
        assertEquals(LoginOutcome.Failure("请输入账号"), repository.login("   ", "p@ss"))
        assertTrue(api.loginCalls.isEmpty())
        assertNull(credentials.active())
    }

    @Test
    fun logoutClearsCredentialsButKeepsMetadata() = withRepository {
        api.loginResult = ApiResult.Success("Bearer token-1")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.restoreSession()
        repository.login("user@example.com", "p@ss")
        val accountId = SecureCredentialStore.accountIdFor("user@example.com")

        repository.logout()

        assertNull(credentials.active())
        assertNull(manager.current())
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertEquals("阿碧", metadata.get(accountId)?.displayName)
    }

    @Test
    fun restoreSessionWithSavedCredentialBecomesReadyWithAccountFingerprint() = withRepository {
        val identity = DeviceIdentity("M2102K1AC", "Android_12", "fedcba9876543210fedcba9876543210")
        val accountId = SecureCredentialStore.accountIdFor("user@example.com")
        credentials.save(
            PlainCredential(accountId, "user@example.com", "p@ss", "Bearer stored-token", identity),
        )
        metadata.upsert(AccountEntity(accountId = accountId, displayName = "阿碧", uid = "42"))

        repository.restoreSession()

        assertEquals(SessionState.Ready(accountId, "阿碧", "42"), manager.state.value)
        assertEquals("Bearer stored-token", manager.current())
        val profile = manager.deviceProfile()
        assertEquals("M2102K1AC", profile.deviceType)
        assertEquals("Android_12", profile.osVersion)
        assertEquals("fedcba9876543210fedcba9876543210", profile.loginUuid)
    }

    @Test
    fun restoreSessionWithoutCredentialsEndsLoggedOut() = withRepository {
        repository.restoreSession()

        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertNull(manager.current())
    }

    @Test
    fun refreshUserInfoReloginsOnceWhenSessionExpired() = withRepository {
        api.loginResult = ApiResult.Success("Bearer old-token")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.restoreSession()
        repository.login("user@example.com", "p@ss")

        val updated = userInfo(uid = 43L, nickname = "新名字", spaceUsed = 200L)
        api.loginResult = ApiResult.Success("Bearer new-token")
        api.enqueueUserInfo(ApiResult.SessionExpired)
        api.enqueueUserInfo(ApiResult.Success(updated))

        val result = repository.refreshUserInfo()

        assertEquals(ApiResult.Success(updated), result)
        // 原登录一次 + 会话过期重登一次
        assertEquals(2, api.loginCalls.size)
        assertEquals("user@example.com" to "p@ss", api.loginCalls.last())
        assertEquals("Bearer new-token", credentials.active()?.authorization)
        assertEquals("Bearer new-token", manager.current())
        val accountId = SecureCredentialStore.accountIdFor("user@example.com")
        assertEquals(SessionState.Ready(accountId, "新名字", "43"), manager.state.value)
        assertEquals(200L, metadata.get(accountId)?.usedBytes)
    }

    @Test
    fun refreshUserInfoLogsOutWhenReloginFails() = withRepository {
        api.loginResult = ApiResult.Success("Bearer old-token")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.restoreSession()
        repository.login("user@example.com", "p@ss")

        api.loginResult = ApiResult.ApiError(401, "用户名或密码错误")
        api.enqueueUserInfo(ApiResult.SessionExpired)

        val result = repository.refreshUserInfo()

        assertEquals(ApiResult.ApiError(401, "用户名或密码错误"), result)
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertNull(manager.current())
        assertNull(credentials.active())
    }

    @Test
    fun refreshUserInfoLogsOutWhenRetryStillExpired() = withRepository {
        api.loginResult = ApiResult.Success("Bearer old-token")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.restoreSession()
        repository.login("user@example.com", "p@ss")

        api.loginResult = ApiResult.Success("Bearer new-token")
        api.enqueueUserInfo(ApiResult.SessionExpired)
        api.enqueueUserInfo(ApiResult.SessionExpired)

        val result = repository.refreshUserInfo()

        assertEquals(ApiResult.SessionExpired, result)
        assertEquals(SessionState.LoggedOut, manager.state.value)
        assertNull(manager.current())
        assertNull(credentials.active())
    }

    @Test
    fun refreshUserInfoUpdatesMetadataWithoutReloginWhenHealthy() = withRepository {
        api.loginResult = ApiResult.Success("Bearer token-1")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.restoreSession()
        repository.login("user@example.com", "p@ss")

        val updated = userInfo(uid = 99L, nickname = "改名", spaceUsed = 555L)
        api.enqueueUserInfo(ApiResult.Success(updated))

        val result = repository.refreshUserInfo()

        assertEquals(ApiResult.Success(updated), result)
        assertEquals(1, api.loginCalls.size)
        val accountId = SecureCredentialStore.accountIdFor("user@example.com")
        assertEquals(SessionState.Ready(accountId, "改名", "99"), manager.state.value)
        assertEquals(555L, metadata.get(accountId)?.usedBytes)
    }

    @Test fun restoreAutomaticallySyncsCachedAccountWithoutManualAction() = withRepository {
        val id = SecureCredentialStore.accountIdFor("saved@example.com")
        credentials.save(PlainCredential(id, "saved@example.com", "p", "Bearer saved", identityStore.loadOrCreate()))
        metadata.upsert(AccountEntity(id, displayName = "旧昵称", usedBytes = 1))
        api.enqueueUserInfo(ApiResult.Success(userInfo(nickname = "新昵称", spaceUsed = 500)))
        repository.restoreSession()
        assertEquals(1, api.userInfoCalls)
        assertEquals("新昵称", metadata.get(id)?.displayName)
        assertEquals(500L, metadata.get(id)?.usedBytes)
        assertEquals("新昵称", (manager.state.value as SessionState.Ready).displayName)
    }

    @Test fun foregroundSyncCoalescesFreshLoginAndRefreshesAfterExpiry() = withRepository {
        repository.restoreSession()
        api.loginResult = ApiResult.Success("Bearer login")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.login("saved@example.com", "p")
        repeat(3) { repository.syncUserInfoIfStale() }
        assertEquals(1, api.userInfoCalls)
        clock.now = 30_001
        api.enqueueUserInfo(ApiResult.Success(userInfo(spaceUsed = 600)))
        repository.syncUserInfoIfStale()
        assertEquals(2, api.userInfoCalls)
        assertEquals(600L, metadata.get(SecureCredentialStore.accountIdFor("saved@example.com"))?.usedBytes)
    }

    @Test fun failedAutomaticSyncPreservesCacheAndRetriesOnNextVisitAfterCooldown() = withRepository {
        val id = SecureCredentialStore.accountIdFor("saved@example.com")
        credentials.save(PlainCredential(id, "saved@example.com", "p", "Bearer saved", identityStore.loadOrCreate()))
        val cached = AccountEntity(id, displayName = "缓存昵称", usedBytes = 200)
        metadata.upsert(cached)
        repository.restoreSession() // Fake API returns NetworkError.
        assertEquals(cached, metadata.get(id))
        assertTrue(manager.state.value is SessionState.Ready)
        repository.syncUserInfoIfStale()
        assertEquals(1, api.userInfoCalls)
        clock.now = 10_001
        api.enqueueUserInfo(ApiResult.Success(userInfo(spaceUsed = 700)))
        repository.syncUserInfoIfStale()
        assertEquals(700L, metadata.get(id)?.usedBytes)
    }

    @Test fun cloudMutationInvalidatesFreshMetadata() = withRepository {
        repository.restoreSession()
        api.loginResult = ApiResult.Success("Bearer login")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.login("saved@example.com", "p")
        val id = (manager.state.value as SessionState.Ready).accountId
        api.enqueueUserInfo(ApiResult.Success(userInfo(spaceUsed = 800)))
        repository.invalidateUserInfo(id)
        repository.syncUserInfoIfStale()
        assertEquals(2, api.userInfoCalls)
        assertEquals(800L, metadata.get(id)?.usedBytes)
    }

    @Test fun concurrentForegroundAndAccountVisitsShareOneRequest() = withRepository {
        repository.restoreSession()
        manager.onLoginSuccess("a", "A", "1", "Bearer a")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.beforeUserInfo = { entered.complete(Unit); release.await() }
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        coroutineScope {
            val first = async { repository.syncUserInfoIfStale() }
            entered.await()
            val second = async { repository.syncUserInfoIfStale() }
            release.complete(Unit)
            first.await(); second.await()
        }
        assertEquals(1, api.userInfoCalls)
    }

    @Test fun oldAccountResponseCannotOverwriteNewAccount() = withRepository {
        repository.restoreSession()
        manager.onLoginSuccess("a", "A", "1", "Bearer a")
        metadata.upsert(AccountEntity("b", displayName = "B", usedBytes = 300))
        api.beforeUserInfo = { manager.onLoginSuccess("b", "B", "2", "Bearer b") }
        api.enqueueUserInfo(ApiResult.Success(userInfo(nickname = "A 更新", spaceUsed = 900)))
        val result = repository.refreshUserInfo()
        assertTrue(result is ApiResult.NetworkError)
        assertEquals("B", (manager.state.value as SessionState.Ready).displayName)
        assertEquals(300L, metadata.get("b")?.usedBytes)
        assertNull(metadata.get("a"))
    }

    @Test fun staleSessionExpiredResponseCannotLogOutNewAccount() = withRepository {
        repository.restoreSession()
        manager.onLoginSuccess("a", "A", "1", "Bearer a")
        api.beforeUserInfo = { manager.onLoginSuccess("b", "B", "2", "Bearer b") }
        api.enqueueUserInfo(ApiResult.SessionExpired)
        repository.syncUserInfoIfStale()
        assertEquals("b", (manager.state.value as SessionState.Ready).accountId)
        assertTrue(api.loginCalls.isEmpty())
    }

    @Test fun lateReloginCannotReactivateSwitchedAccount() = withRepository {
        repository.restoreSession()
        api.loginResult = ApiResult.Success("Bearer a")
        api.enqueueUserInfo(ApiResult.Success(userInfo()))
        repository.login("a@example.com", "p")
        val idB = SecureCredentialStore.accountIdFor("b@example.com")
        api.beforeLogin = {
            credentials.save(PlainCredential(idB, "b@example.com", "p", "Bearer b", identityStore.loadOrCreate()))
            manager.onLoginSuccess(idB, "B", "2", "Bearer b")
        }
        api.loginResult = ApiResult.Success("Bearer a-renewed")
        api.enqueueUserInfo(ApiResult.SessionExpired)
        assertTrue(repository.refreshUserInfo() is ApiResult.NetworkError)
        assertEquals(idB, credentials.active()?.accountId)
        assertEquals("Bearer b", manager.current())
    }

    @Test fun mutationDuringSyncCannotMarkOldResponseAsFresh() = withRepository {
        repository.restoreSession()
        manager.onLoginSuccess("a", "A", "1", "Bearer a")
        api.beforeUserInfo = { if (api.userInfoCalls == 1) repository.invalidateUserInfo("a") }
        api.enqueueUserInfo(ApiResult.Success(userInfo(spaceUsed = 100)))
        api.enqueueUserInfo(ApiResult.Success(userInfo(spaceUsed = 900)))
        repository.syncUserInfoIfStale()
        repository.syncUserInfoIfStale()
        assertEquals(2, api.userInfoCalls)
        assertEquals(900L, metadata.get("a")?.usedBytes)
    }
}
