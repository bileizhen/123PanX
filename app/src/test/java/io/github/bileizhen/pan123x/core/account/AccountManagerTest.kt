package io.github.bileizhen.pan123x.core.account

import io.github.bileizhen.pan123x.core.network.DeviceProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountManagerTest {

    private val initialProfile = DeviceProfile(
        osVersion = "Android_13",
        deviceType = "M2011K2C",
        loginUuid = "0123456789abcdef0123456789abcdef",
    )

    @Test
    fun initialStateIsRestoringWithoutToken() {
        val manager = AccountManager(initialProfile)

        assertEquals(SessionState.Restoring, manager.state.value)
        assertNull(manager.current())
    }

    @Test
    fun awaitRestoredReturnsAfterLoginSuccess() = runTest {
        val manager = AccountManager(initialProfile)
        val pending = async { manager.awaitRestored() }

        assertFalse(pending.isCompleted)

        manager.onLoginSuccess("account-1", "阿碧", "42", "Bearer token")

        assertEquals(SessionState.Ready("account-1", "阿碧", "42"), pending.await())
    }

    @Test
    fun awaitRestoredReturnsAfterLogout() = runTest {
        val manager = AccountManager(initialProfile)
        val pending = async { manager.awaitRestored() }

        manager.onLogout()

        assertEquals(SessionState.LoggedOut, pending.await())
    }

    @Test
    fun currentFollowsLoginAndLogout() {
        val manager = AccountManager(initialProfile)

        assertNull(manager.current())

        manager.onLoginSuccess("account-1", "阿碧", "42", "Bearer token")
        assertEquals("Bearer token", manager.current())

        manager.onLogout()
        assertNull(manager.current())
    }

    @Test
    fun updateProfileMapsDeviceIdentityOntoCurrentProfile() {
        val manager = AccountManager(initialProfile)

        manager.updateProfile(DeviceIdentity("M2102K1AC", "Android_12", "fedcba9876543210fedcba9876543210"))

        val profile = manager.deviceProfile()
        assertEquals("M2102K1AC", profile.deviceType)
        assertEquals("Android_12", profile.osVersion)
        assertEquals("fedcba9876543210fedcba9876543210", profile.loginUuid)
        // 非身份字段保留初始指纹，不被置空或重生成
        assertEquals(initialProfile.platform, profile.platform)
        assertEquals(initialProfile.deviceName, profile.deviceName)
        assertEquals(initialProfile.appVersion, profile.appVersion)
        assertEquals(initialProfile.xAppVersion, profile.xAppVersion)
    }

    @Test
    fun deviceProfileReturnsInitialProfileBeforeAnyUpdate() {
        val manager = AccountManager(initialProfile)

        assertEquals(initialProfile, manager.deviceProfile())
    }

    @Test
    fun onMetadataOnlyUpdatesReadyState() {
        val manager = AccountManager(initialProfile)

        manager.onLogout()
        manager.onMetadata("阿碧", "42")
        assertEquals(SessionState.LoggedOut, manager.state.value)

        manager.onLoginSuccess("account-1", "旧昵称", "1", "Bearer token")
        manager.onMetadata("阿碧", "42")
        assertEquals(SessionState.Ready("account-1", "阿碧", "42"), manager.state.value)
    }
}
