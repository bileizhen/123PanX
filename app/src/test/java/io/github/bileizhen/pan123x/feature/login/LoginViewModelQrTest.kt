@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.login

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.DeviceIdentity
import io.github.bileizhen.pan123x.core.account.DeviceIdentityStore
import io.github.bileizhen.pan123x.core.account.FakeCrypto
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.DeviceProfile
import io.github.bileizhen.pan123x.core.network.PanAuthApi
import io.github.bileizhen.pan123x.core.network.PanQrApi
import io.github.bileizhen.pan123x.core.network.QrGenerateDto
import io.github.bileizhen.pan123x.core.network.QrPollDto
import io.github.bileizhen.pan123x.core.network.UserInfoDto
import io.github.bileizhen.pan123x.data.auth.AccountMetadataStore
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoginViewModel 扫码登录行为测试（M7，， 纯 JVM）。
 *
 * 走真实 [AuthRepository]（内存 DataStore + FakeCrypto + 替身 API），因此同时回归仓库的
 * qrStart / qrPoll / qrVerify 落库链；轮询节拍用虚拟时间 advanceTimeBy 驱动（2s 一次），
 * 不触真实网络与真实时钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelQrTest {

    private val owner = ViewModelStore()
    private val identity = DeviceIdentity("M2011K2C", "Android_13", "0123456789abcdef0123456789abcdef")

    @After
    fun cleanup() {
        owner.clear()
        Dispatchers.resetMain()
    }

    /** 把 viewModelScope 所在的 Main 绑到 runTest 的调度器上，使 delay(2s) 受虚拟时间驱动。 */
    private fun withVirtualClock(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            owner.clear()
        }
    }

    // ---- 替身 ----

    /** PanQrApi 手工假实现：脚本化 generate / poll 结果并记录调用。 */
    private class FakeQrApi : PanQrApi {
        var generateResult: ApiResult<QrGenerateDto> = ApiResult.NetworkError("not configured")
        var pollDefault: ApiResult<QrPollDto> = ApiResult.Success(QrPollDto(0, 0, ""))
        private val pollResults = ArrayDeque<ApiResult<QrPollDto>>()

        var generateCalls = 0
        var generateGate: CompletableDeferred<ApiResult<QrGenerateDto>>? = null
        val pollCalls = mutableListOf<String>()

        fun enqueuePoll(result: ApiResult<QrPollDto>) {
            pollResults.addLast(result)
        }

        override suspend fun qrGenerate(): ApiResult<QrGenerateDto> {
            generateCalls++
            return generateGate?.await() ?: generateResult
        }

        override suspend fun qrPoll(uniId: String): ApiResult<QrPollDto> {
            pollCalls.addLast(uniId)
            return pollResults.removeFirstOrNull() ?: pollDefault
        }
    }

    /** PanAuthApi 手工假实现：脚本化 login / getUserInfo（形态对齐 AuthRepositoryTest）。 */
    private class FakeAuthApi : PanAuthApi {
        var loginResult: ApiResult<String> = ApiResult.NetworkError("not configured")
        private val userInfoProviders = ArrayDeque<suspend () -> ApiResult<UserInfoDto>>()

        val loginCalls = mutableListOf<Pair<String, String>>()
        var userInfoCalls = 0

        fun enqueueUserInfo(result: ApiResult<UserInfoDto>) {
            userInfoProviders.addLast { result }
        }

        /** 门闩版：getUserInfo 挂起直到 [CompletableDeferred] 完成，模拟验证请求在途。 */
        fun enqueueUserInfoDeferred(gate: CompletableDeferred<ApiResult<UserInfoDto>>) {
            userInfoProviders.addLast { gate.await() }
        }

        override suspend fun login(passport: String, password: String): ApiResult<String> {
            loginCalls.addLast(passport to password)
            return loginResult
        }

        override suspend fun getUserInfo(): ApiResult<UserInfoDto> {
            userInfoCalls++
            return userInfoProviders.removeFirstOrNull()?.invoke() ?: ApiResult.NetworkError("not configured")
        }
    }

    /** 内存 DataStore：避免临时文件与真实 IO，read-modify-write 语义与文件实现一致。 */
    private class MemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val next = transform(state.value.toMutablePreferences())
                state.value = next
                next
            }
    }

    private class InMemoryMetadata : AccountMetadataStore {
        val accounts = linkedMapOf<String, AccountEntity>()

        override suspend fun upsert(account: AccountEntity) {
            accounts[account.accountId] = account
        }

        override suspend fun get(accountId: String): AccountEntity? = accounts[accountId]
    }

    private class Fixture(
        val api: FakeAuthApi,
        val qrApi: FakeQrApi,
        val credentials: SecureCredentialStore,
        val metadata: InMemoryMetadata,
        val manager: AccountManager,
        val repository: AuthRepository,
    )

    private fun createFixture(): Fixture {
        val credentials = SecureCredentialStore(MemoryPreferencesDataStore(), FakeCrypto())
        val identityStore = DeviceIdentityStore(MemoryPreferencesDataStore())
        val metadata = InMemoryMetadata()
        val manager = AccountManager(DeviceProfile(loginUuid = identity.loginUuid))
        val api = FakeAuthApi()
        val qrApi = FakeQrApi()
        val repository = AuthRepository(api, credentials, identityStore, metadata, manager, AppLogger(), qrApi = qrApi)
        return Fixture(api, qrApi, credentials, metadata, manager, repository)
    }

    private fun userInfo(uid: Long = 42L, nickname: String = "阿碧") = UserInfoDto(uid = uid, nickname = nickname)

    private fun TestScope.newViewModel(fixture: Fixture): LoginViewModel {
        val model = LoginViewModel(fixture.repository)
        owner.put("login", model)
        return model
    }

    private suspend fun Fixture.restore() = repository.restoreSession()

    // ---- 用例 ----

    @Test fun generatedQrContainsTheCompleteReferenceLoginPayloadAndDecodes() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("session-123", "https://yun.123pan.cn/wx-app-login.html"))
        val model = newViewModel(fixture)
        model.selectTab(LOGIN_TAB_QR)
        runCurrent()
        val expected = "https://yun.123pan.cn/wx-app-login.html?env=production&uniID=session-123&source=123pan&type=login"
        assertEquals(expected, model.uiState.value.qrUrl)
        val matrix = QrCodeBitmap.encode(model.uiState.value.qrUrl!!)
        val scale = 8
        val width = matrix.width * scale
        val pixels = IntArray(width * width) { pixel ->
            if (matrix.get((pixel % width) / scale, (pixel / width) / scale)) -0x1000000 else -1
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))).text
        assertEquals(expected, decoded)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("session-123"), fixture.qrApi.pollCalls)
    }

    @Test fun qrPayloadEscapesSessionIdAndReplacesConflictingQueryFields() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        val sessionId = "session+/&?=123"
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto(sessionId,
            "https://yun.123pan.cn/wx-app-login.html?keep=1&env=test&uniID=old&type=download&source=other&uniID=duplicate"))
        val model = newViewModel(fixture)
        model.selectTab(LOGIN_TAB_QR); runCurrent()
        val content = model.uiState.value.qrUrl!!.toHttpUrl()
        assertEquals(listOf(sessionId), content.queryParameterValues("uniID"))
        assertEquals("production", content.queryParameter("env"))
        assertEquals("123pan", content.queryParameter("source"))
        assertEquals("login", content.queryParameter("type"))
        assertEquals("1", content.queryParameter("keep"))
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(sessionId), fixture.qrApi.pollCalls)
    }

    @Test fun invalidQrPagesDoNotShowACodeOrStartPolling() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        val model = newViewModel(fixture)
        for (page in listOf("not-a-url", "http://yun.123pan.cn/wx-app-login.html", "javascript:alert(1)")) {
            fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("session-123", page))
            model.refreshQr(); runCurrent()
            assertEquals(QrPhase.FAILED, model.uiState.value.qrPhase)
            assertNull(model.uiState.value.qrUrl)
            advanceTimeBy(2_000); runCurrent()
            assertTrue(fixture.qrApi.pollCalls.isEmpty())
        }
    }

    @Test fun leavingQrTabWhileGenerationIsPendingCannotRestartPolling() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        val gate = CompletableDeferred<ApiResult<QrGenerateDto>>()
        fixture.qrApi.generateGate = gate
        val model = newViewModel(fixture)
        model.selectTab(LOGIN_TAB_QR)
        runCurrent()
        assertEquals(QrPhase.GENERATING, model.uiState.value.qrPhase)
        model.selectTab(LOGIN_TAB_PASSWORD)
        gate.complete(ApiResult.Success(QrGenerateDto("late", "https://login.example/late")))
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(QrPhase.IDLE, model.uiState.value.qrPhase)
        assertNull(model.uiState.value.qrUrl)
        assertTrue(fixture.qrApi.pollCalls.isEmpty())
    }

    @Test
    fun qrTabGeneratesThenPollsEveryTwoSecondsUntilConfirmed() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        runCurrent()

        // Ready：generate 立即完成，首拍要等 2s 才发
        assertEquals(QrPhase.READY, model.uiState.value.qrPhase)
        assertEquals("https://login.example/qr?env=production&uniID=uni-1&source=123pan&type=login", model.uiState.value.qrUrl)
        assertEquals(1, fixture.qrApi.generateCalls)
        assertEquals(0, fixture.qrApi.pollCalls.size)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("uni-1"), fixture.qrApi.pollCalls)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, fixture.qrApi.pollCalls.size)

        // 等待中状态保持 READY
        assertEquals(QrPhase.READY, model.uiState.value.qrPhase)
        assertFalse(model.uiState.value.qrScanned)
    }

    @Test
    fun scannedStatusFlagsQrStateButKeepsPolling() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 1, scanPlatform = 7, token = "")))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(QrPhase.READY, model.uiState.value.qrPhase)
        assertTrue(model.uiState.value.qrScanned)

        // 已扫码仍继续轮询等待确认
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, fixture.qrApi.pollCalls.size)
    }

    @Test
    fun rejectedStopsPollingAndRefreshRegenerates() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 2, scanPlatform = 0, token = "")))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(QrPhase.REJECTED, model.uiState.value.qrPhase)
        assertEquals(1, fixture.qrApi.pollCalls.size)

        // 拒绝后轮询停止：虚拟时间推进也不再发请求
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, fixture.qrApi.pollCalls.size)

        // 「刷新二维码」重新走 generate → Ready，并开启新一轮轮询
        model.refreshQr()
        runCurrent()
        assertEquals(QrPhase.READY, model.uiState.value.qrPhase)
        assertEquals(2, fixture.qrApi.generateCalls)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, fixture.qrApi.pollCalls.size)
    }

    @Test
    fun expiredStopsPolling() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 4, scanPlatform = 0, token = "")))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(QrPhase.EXPIRED, model.uiState.value.qrPhase)
        assertEquals(1, fixture.qrApi.pollCalls.size)
    }

    @Test
    fun confirmedTokenGoesThroughVerifyAndPersistsAccount() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 3, scanPlatform = 7, token = "jwt-1")))
        fixture.api.enqueueUserInfo(ApiResult.Success(userInfo()))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()

        // 轮询确认后进入验证并成功：单次 getUserInfo，会话 Ready
        assertEquals(1, fixture.api.userInfoCalls)
        assertEquals(QrPhase.SUCCESS, model.uiState.value.qrPhase)
        assertTrue(model.uiState.value.loggedIn)
        val accountId = SecureCredentialStore.accountIdFor("qr:42")
        assertEquals(SessionState.Ready(accountId, "阿碧", "42"), fixture.manager.state.value)
        // 落库链与密码登录同构：凭据（密码为空）、authorization 带 Bearer 前缀、元数据昵称
        val credential = fixture.credentials.active()
        assertEquals("qr:42", credential?.passport)
        assertEquals("", credential?.password)
        assertEquals("Bearer jwt-1", credential?.authorization)
        assertEquals("阿碧", fixture.metadata.accounts[accountId]?.displayName)
        assertEquals("42", fixture.metadata.accounts[accountId]?.uid)
    }

    @Test
    fun confirmedWechatScanIsRejectedWithFrozenMessageWithoutVerify() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        // platform==4 且无 token（session.py:548-559 归一化后的微信确认形态）
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 3, scanPlatform = 4, token = "")))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()

        // 不调 wx_code、不走验证（qr_login_tasks.py:91-102 的取舍）
        assertEquals(QrPhase.FAILED, model.uiState.value.qrPhase)
        assertEquals("微信登录暂不支持，请使用 123云盘 App 扫码", model.uiState.value.qrMessage)
        assertEquals(0, fixture.api.userInfoCalls)
        assertFalse(model.uiState.value.loggedIn)
        assertEquals(SessionState.LoggedOut, fixture.manager.state.value)
        assertNull(fixture.credentials.active())
    }

    @Test
    fun confirmedWithoutTokenAndNonWechatReportsMissingCredential() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 3, scanPlatform = 7, token = "")))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(QrPhase.FAILED, model.uiState.value.qrPhase)
        assertEquals("登录失败：未获取到凭证", model.uiState.value.qrMessage)
        assertEquals(0, fixture.api.userInfoCalls)
    }

    @Test
    fun verifyFailureRestoresLoggedOutAndShowsReadableMessage() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 3, scanPlatform = 7, token = "bad-token")))
        fixture.api.enqueueUserInfo(ApiResult.ApiError(401, "用户名或密码错误"))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(QrPhase.FAILED, model.uiState.value.qrPhase)
        assertEquals("用户名或密码错误", model.uiState.value.qrMessage)
        assertFalse(model.uiState.value.loggedIn)
        // 验证失败回到验证前的登出态，凭据不落盘
        assertEquals(SessionState.LoggedOut, fixture.manager.state.value)
        assertNull(fixture.credentials.active())
    }

    @Test
    fun generateFailureShowsRetryableMessage() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.NetworkError("connect refused")
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        runCurrent()

        assertEquals(QrPhase.FAILED, model.uiState.value.qrPhase)
        assertEquals("网络连接失败，请检查网络后重试", model.uiState.value.qrMessage)
        assertEquals(0, fixture.qrApi.pollCalls.size)
    }

    @Test
    fun switchingBackToPasswordTabCancelsPolling() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, fixture.qrApi.pollCalls.size)

        // 切回密码 Tab：轮询取消，虚拟时间再推进也不发请求，状态复位
        model.selectTab(LOGIN_TAB_PASSWORD)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, fixture.qrApi.pollCalls.size)
        assertEquals(QrPhase.IDLE, model.uiState.value.qrPhase)
        assertNull(model.uiState.value.qrUrl)
    }

    @Test
    fun slowVerifyKeepsVerifyingPhaseUntilDeferredCompletes() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.qrApi.generateResult = ApiResult.Success(QrGenerateDto("uni-1", "https://login.example/qr"))
        fixture.qrApi.enqueuePoll(ApiResult.Success(QrPollDto(loginStatus = 3, scanPlatform = 7, token = "jwt-2")))
        val gate = CompletableDeferred<ApiResult<UserInfoDto>>()
        fixture.api.enqueueUserInfoDeferred(gate)
        val model = newViewModel(fixture)

        model.selectTab(LOGIN_TAB_QR)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(QrPhase.VERIFYING, model.uiState.value.qrPhase)
        assertFalse(model.uiState.value.loggedIn)

        // 轮询已停止（确认即离开轮询循环），验证完成才翻 SUCCESS
        assertEquals(1, fixture.qrApi.pollCalls.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(QrPhase.VERIFYING, model.uiState.value.qrPhase)
        gate.complete(ApiResult.Success(userInfo()))
        runCurrent()
        assertEquals(QrPhase.SUCCESS, model.uiState.value.qrPhase)
        assertTrue(model.uiState.value.loggedIn)
    }

    @Test
    fun passwordLoginStillWorksAlongsideQrTab() = withVirtualClock {
        val fixture = createFixture().apply { restore() }
        fixture.api.loginResult = ApiResult.Success("Bearer pwd-token")
        fixture.api.enqueueUserInfo(ApiResult.Success(userInfo()))
        val model = newViewModel(fixture)

        model.onPassportChange("user@example.com")
        model.onPasswordChange("p@ss")
        model.submit()
        runCurrent()

        // 密码 Tab 行为与 M1 一致：成功置 loggedIn、凭据落库；扫码状态保持初始 IDLE
        assertTrue(model.uiState.value.loggedIn)
        assertEquals("Bearer pwd-token", fixture.credentials.active()?.authorization)
        assertEquals(QrPhase.IDLE, model.uiState.value.qrPhase)
        assertEquals(listOf("user@example.com" to "p@ss"), fixture.api.loginCalls)
    }
}
