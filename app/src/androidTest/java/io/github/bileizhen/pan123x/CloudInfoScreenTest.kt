package io.github.bileizhen.pan123x

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.network.*
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.settings.AppSettings
import io.github.bileizhen.pan123x.data.settings.ThemeMode
import io.github.bileizhen.pan123x.feature.account.AccountViewModel
import io.github.bileizhen.pan123x.feature.account.CloudInfoScreen
import io.github.bileizhen.pan123x.ui.component.ScreenFrame
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudInfoScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cloudInfoEntryNavigatesAndHandlesLoggedOutWithoutInventingCloudValues() {
        compose.onNodeWithTag("tab_3").performClick()
        compose.onNodeWithTag("account_screen").performScrollToIndex(3)
        compose.onNodeWithTag("account_cloud_info").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cloud_info_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("未登录").assertIsDisplayed()
        compose.onNodeWithTag("cloud_file_count").assertDoesNotExist()
        compose.onNodeWithTag("navigate_back").performClick()
        compose.onNodeWithTag("account_screen").assertIsDisplayed()
    }

    /** Isolated fixture, injected API/Room/store: never reads the user's DB or credentials. */
    @Test fun detailsLoadAutomaticallyRetryDevicesAndResetAfterAccountSwitch() {
        val container = (compose.activity.application as PanXApplication).container
        val gib = 1_073_741_824L
        val devicesCalls = AtomicInteger()
        var deviceFailure = false
        var info = UserInfoDto(
            uid = 1000000001, nickname = "fixture-user", passport = 13800138000,
            spaceUsed = 59_485_000_000, spaceTotal = 2_199_023_255_552,
            professionalSpacePermanent = 50 * gib, standardSpacePermanent = 2_199_023_255_552,
            standardSpaceUsed = 59_485_000_000, fileCount = 22,
        )
        val fake = object : PanAuthApi, PanDeviceApi {
            override suspend fun login(passport: String, password: String): ApiResult<String> = ApiResult.NetworkError("not used")
            override suspend fun getUserInfo(authorization: String?) = ApiResult.Success(info)
            override suspend fun getLoginDevices(): ApiResult<List<LoginDeviceDto>> {
                devicesCalls.incrementAndGet()
                return if (deviceFailure) ApiResult.NetworkError("fixture offline") else ApiResult.Success(listOf(
                    LoginDeviceDto("Xiaomi", "android", "192.0.2.5", "2026-10-01 23:15:36", "Android端", true, "账号登录"),
                    LoginDeviceDto("Desktop", "windows", "192.0.2.8", "2026-10-01 11:00:04", "PC端", false, "扫码登录"),
                ))
            }
        }
        val manager = AccountManager().apply { onLoginSuccess("cloud-fixture-a", "fixture-user", "1000000001", "Bearer fixture") }
        val auth = AuthRepository(fake, container.credentialStore, container.deviceIdentityStore, container.accountMetadata, manager, container.logger)
        val store = ViewModelStore()
        val theme = mutableStateOf(AppSettings(themeMode = ThemeMode.LIGHT, monet = false))
        lateinit var viewModel: AccountViewModel
        compose.runOnUiThread {
            viewModel = AccountViewModel(auth, manager, container.database.accountDao().observeAccounts())
            store.put("cloud-info", viewModel)
            compose.activity.setContent {
                PanXTheme(theme.value) { Scaffold { ScreenFrame("云盘信息", {}) { CloudInfoScreen(viewModel, {}) } } }
            }
        }
        try {
            compose.waitUntil(5_000) { viewModel.uiState.value.account?.hasCloudInfo == true && viewModel.uiState.value.devicesLoaded }
            compose.onNodeWithTag("cloud_account").assertTextContains("138****8000", substring = true)
            compose.onNodeWithTag("cloud_vip").assertTextContains("非会员", substring = true)
            capture("account-light")
            compose.onNodeWithTag("cloud_info_screen").performScrollToIndex(3)
            compose.onNodeWithTag("cloud_file_count").assertTextContains("22", substring = true)
            compose.onNodeWithTag("cloud_professional_space").assertTextContains("50.0 GB", substring = true)
            capture("storage-light")
            compose.onNodeWithTag("cloud_info_screen").performScrollToNode(hasTestTag("cloud_device_0"))
            compose.onNodeWithTag("cloud_device_0").assertTextContains("当前", substring = true)
            compose.onNodeWithTag("cloud_device_0").assertTextContains("192.0.2.5", substring = true)
            compose.onNodeWithTag("cloud_device_0").assertTextContains("2026-10-01 23:15:36", substring = true)
            capture("devices-light")
            assertEquals(1, devicesCalls.get())
            compose.runOnUiThread { viewModel.onCloudInfoVisible() }
            compose.waitForIdle()
            assertEquals(1, devicesCalls.get())
            compose.runOnUiThread { theme.value = theme.value.copy(themeMode = ThemeMode.DARK) }
            compose.waitForIdle()
            capture("devices-dark")

            deviceFailure = true
            compose.runOnUiThread { viewModel.onCloudInfoVisible(forceDevices = true) }
            compose.waitUntil(5_000) { viewModel.uiState.value.devicesError != null }
            // A transient failure keeps successful devices visible and permits retry.
            assertEquals(2, viewModel.uiState.value.devices.size)
            compose.onNodeWithTag("cloud_info_screen").performScrollToNode(hasTestTag("cloud_devices_retry"))
            compose.onNodeWithTag("cloud_devices_retry").assertIsDisplayed()
            deviceFailure = false
            compose.onNodeWithTag("cloud_devices_retry").performClick()
            compose.waitUntil(5_000) { viewModel.uiState.value.devicesError == null && !viewModel.uiState.value.devicesLoading }

            // Switch to another account whose device request fails: previous account devices must disappear.
            deviceFailure = true
            info = info.copy(uid = 2, nickname = "另一个账户", passport = 15500001234, fileCount = 3, vip = true, vipLevel = 1, vipExpire = "2026-12-01")
            compose.runOnUiThread { manager.onLoginSuccess("cloud-fixture-b", "另一个账户", "2", "Bearer fixture-b") }
            compose.waitUntil(5_000) { viewModel.uiState.value.account?.fileCount == 3L && viewModel.uiState.value.devicesError != null }
            assertTrue(viewModel.uiState.value.devices.isEmpty())
            compose.onNodeWithTag("cloud_info_screen").performScrollToIndex(1)
            compose.onNodeWithTag("cloud_account").assertTextContains("155****1234", substring = true)
            compose.onNodeWithTag("cloud_vip").assertTextContains("VIP1", substring = true)
            compose.onNodeWithTag("cloud_logout").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("cloud_logout_confirm").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("取消").performClick()
            assertNotNull(viewModel.uiState.value.account)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { container.database.accountDao().delete("cloud-fixture-a"); container.database.accountDao().delete("cloud-fixture-b") }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "cloud-info").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
