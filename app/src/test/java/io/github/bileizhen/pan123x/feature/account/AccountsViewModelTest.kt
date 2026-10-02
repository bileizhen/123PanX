@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.account

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.DeviceIdentityStore
import io.github.bileizhen.pan123x.core.account.FakeCrypto
import io.github.bileizhen.pan123x.core.account.PlainCredential
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.database.AccountDao
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.DeviceProfile
import io.github.bileizhen.pan123x.core.network.PanAuthApi
import io.github.bileizhen.pan123x.core.network.UserInfoDto
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.auth.RoomAccountMetadataStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * AccountsViewModel 行为测试（纯 JVM）。装配与 AuthRepositoryTest 同源——真实
 * DataStore + FakeCrypto + 手写 API 替身 + 内存 AccountDao；Main 调度器与 DataStore 作用域
 * 共用 testScheduler，保证 VM 的订阅流在断言前确定性收敛。
 *
 * 覆盖：列表合并（有凭据排前、"可切换"标记、无行凭据以占位名补进）、切换后活跃徽标移动、
 * 移除后列表收缩、错误一次性提示、busy 防重。
 */
class AccountsViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val initialProfile = DeviceProfile(
        osVersion = "Android_13",
        deviceType = "M2011K2C",
        loginUuid = "0123456789abcdef0123456789abcdef",
    )

    /** 手写替身：getUserInfo 可挂门闩以观察 busy 行为。 */
    private class FakeAuthApi : PanAuthApi {
        var loginResult: ApiResult<String> = ApiResult.NetworkError("not configured")
        var userInfoGate: CompletableDeferred<Unit>? = null
        val userInfoCalls = mutableListOf<Int>()
        private val userInfoQueue = ArrayDeque<ApiResult<UserInfoDto>>()

        fun enqueueUserInfo(result: ApiResult<UserInfoDto>) {
            userInfoQueue.addLast(result)
        }

        override suspend fun login(passport: String, password: String): ApiResult<String> = loginResult

        override suspend fun getUserInfo(authorization: String?): ApiResult<UserInfoDto> {
            userInfoCalls.addLast(userInfoCalls.size + 1)
            userInfoGate?.await()
            return userInfoQueue.removeFirstOrNull() ?: ApiResult.NetworkError("not configured")
        }
    }

    /** 内存 AccountDao 替身：observeAccounts 用 StateFlow 模拟 Room 观测。 */
    private class FakeAccountDao : AccountDao {
        private val rows = MutableStateFlow<List<AccountEntity>>(emptyList())

        override fun observeAccounts(): Flow<List<AccountEntity>> = rows

        override suspend fun get(accountId: String): AccountEntity? =
            rows.value.firstOrNull { it.accountId == accountId }

        override suspend fun upsert(account: AccountEntity) {
            rows.value = rows.value.filterNot { it.accountId == account.accountId } + account
        }

        override suspend fun delete(accountId: String) {
            rows.value = rows.value.filterNot { it.accountId == accountId }
        }
    }

    private class Deps(
        val api: FakeAuthApi,
        val credentials: SecureCredentialStore,
        val identityStore: DeviceIdentityStore,
        val dao: FakeAccountDao,
        val manager: AccountManager,
        val repository: AuthRepository,
    )

    private fun userInfo(
        uid: Long = 42L,
        nickname: String = "Alice",
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

    /** Main 调度器与 DataStore 作用域共用 testScheduler：动作完成后 runCurrent 即收敛。 */
    private fun withViewModel(
        seed: suspend Deps.() -> Unit = {},
        block: suspend TestScope.(deps: Deps, viewModel: AccountsViewModel) -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + dispatcher)
        val owner = ViewModelStore()
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
            val manager = AccountManager(initialProfile)
            val repository = AuthRepository(api, credentials, identityStore, RoomAccountMetadataStore(dao), manager, AppLogger(), dao)
            val deps = Deps(api, credentials, identityStore, dao, manager, repository)
            deps.seed()
            val viewModel = AccountsViewModel(repository, credentials, dao.observeAccounts(), manager)
            owner.put("accounts", viewModel)
            testScheduler.runCurrent()
            block(deps, viewModel)
        } finally {
            owner.clear()
            job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private suspend fun Deps.seedAccount(
        passport: String,
        token: String,
        displayName: String,
        uid: String,
    ): String {
        val accountId = SecureCredentialStore.accountIdFor(passport)
        credentials.save(
            PlainCredential(
                accountId = accountId,
                passport = passport,
                password = "p@ss",
                authorization = token,
                identity = identityStore.loadOrCreate(),
            ),
        )
        dao.upsert(AccountEntity(accountId = accountId, displayName = displayName, uid = uid))
        return accountId
    }

    @Test
    fun listMergesCredentialIdsAndRowsWithSwitchableFirst() = withViewModel(
        seed = {
            val idA = seedAccount("a@example.com", "Bearer token-a", "Alice", "42")
            // 有凭据但无 accounts 行：登录时用户信息失败的中间态，应以占位名补进列表。
            credentials.save(
                PlainCredential(
                    accountId = SecureCredentialStore.accountIdFor("c@example.com"),
                    passport = "c@example.com",
                    password = "p@ss",
                    authorization = "Bearer token-c",
                    identity = identityStore.loadOrCreate(),
                ),
            )
            // 只有元数据行没有凭据：仅可移除。
            dao.upsert(
                AccountEntity(
                    accountId = SecureCredentialStore.accountIdFor("b@example.com"),
                    displayName = "Bob",
                    uid = "43",
                ),
            )
            manager.onLoginSuccess(idA, "Alice", "42", "Bearer token-a")
        },
    ) { _, viewModel ->
        val idA = SecureCredentialStore.accountIdFor("a@example.com")
        val idB = SecureCredentialStore.accountIdFor("b@example.com")
        val idC = SecureCredentialStore.accountIdFor("c@example.com")

        val rows = viewModel.uiState.value.rows

        // 有凭据的排前（Alice、占位名"未命名账户"），无凭据的 Bob 在后。
        assertEquals(listOf(idA, idC, idB), rows.map { it.accountId })
        assertEquals(listOf("Alice", "未命名账户", "Bob"), rows.map { it.displayName })
        assertEquals(listOf(true, true, false), rows.map { it.hasCredential })
        assertEquals(idA, viewModel.uiState.value.activeAccountId)
    }

    @Test
    fun switchMovesActiveBadgeToTargetAccount() = withViewModel(
        seed = {
            seedAccount("a@example.com", "Bearer token-a", "Alice", "42")
            seedAccount("b@example.com", "Bearer token-b", "Bob", "43")
            // 后保存的 B 为活跃指针，恢复后当前账户是 B。
            repository.restoreSession()
        },
    ) { deps, viewModel ->
        val idA = SecureCredentialStore.accountIdFor("a@example.com")
        val idB = SecureCredentialStore.accountIdFor("b@example.com")
        assertEquals(idB, viewModel.uiState.value.activeAccountId)
        deps.api.enqueueUserInfo(ApiResult.Success(userInfo(nickname = "Alice", uid = 42L)))

        viewModel.switchTo(idA)
        testScheduler.runCurrent()

        assertEquals(idA, viewModel.uiState.value.activeAccountId)
        assertFalse(viewModel.uiState.value.busy)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun removeShrinksListAndKeepsActiveAccount() = withViewModel(
        seed = {
            seedAccount("a@example.com", "Bearer token-a", "Alice", "42")
            seedAccount("b@example.com", "Bearer token-b", "Bob", "43")
            repository.restoreSession()
        },
    ) { deps, viewModel ->
        val idA = SecureCredentialStore.accountIdFor("a@example.com")
        val idB = SecureCredentialStore.accountIdFor("b@example.com")
        assertEquals(idB, viewModel.uiState.value.activeAccountId)

        viewModel.removeAccount(idA)
        testScheduler.runCurrent()

        assertEquals(listOf(idB), viewModel.uiState.value.rows.map { it.accountId })
        assertTrue(viewModel.uiState.value.rows.single().hasCredential)
        // 活跃账户不受非活跃移除影响。
        assertEquals(idB, viewModel.uiState.value.activeAccountId)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun errorsSurfaceOnceAndClearOnConsume() = withViewModel(
        seed = {
            seedAccount("a@example.com", "Bearer token-a", "Alice", "42")
            repository.restoreSession()
        },
    ) { _, viewModel ->
        viewModel.switchTo("deadbeef000000000000000000000000")
        testScheduler.runCurrent()

        assertEquals("该账户没有保存的登录凭据", viewModel.uiState.value.message)
        viewModel.consumeMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun busyStateGuardsReentryDuringSwitch() = withViewModel(
        seed = {
            seedAccount("a@example.com", "Bearer token-a", "Alice", "42")
            seedAccount("b@example.com", "Bearer token-b", "Bob", "43")
            repository.restoreSession()
        },
    ) { deps, viewModel ->
        val idA = SecureCredentialStore.accountIdFor("a@example.com")
        val idB = SecureCredentialStore.accountIdFor("b@example.com")
        val gate = CompletableDeferred<Unit>()
        val requestsBeforeSwitch = deps.api.userInfoCalls.size
        deps.api.userInfoGate = gate
        deps.api.enqueueUserInfo(ApiResult.Success(userInfo(nickname = "Alice", uid = 42L)))

        viewModel.switchTo(idA)
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.busy)

        // busy 期间再次切换被忽略：getUserInfo 只发出一次。
        viewModel.switchTo(idB)
        testScheduler.runCurrent()
        assertEquals(requestsBeforeSwitch + 1, deps.api.userInfoCalls.size)

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(idA, viewModel.uiState.value.activeAccountId)
    }
}
