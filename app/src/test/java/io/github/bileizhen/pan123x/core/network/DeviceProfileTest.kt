package io.github.bileizhen.pan123x.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备指纹取值池与生成规则，对应参考源 `devices.py`。 */
class DeviceProfileTest {

    @Test
    fun defaultProfileMatchesProtocolConstants() {
        val device = DeviceProfile(loginUuid = "0123456789abcdef0123456789abcdef")
        assertEquals("android", device.platform)
        assertEquals("Xiaomi", device.deviceName)
        assertEquals("61", device.appVersion)
        assertEquals("2.4.0", device.xAppVersion)
        assertEquals("Android_13", device.osVersion)
        assertTrue(DeviceProfile.DEVICE_TYPES.contains(device.deviceType))
        assertEquals("123pan/v2.4.0(Android_13;Xiaomi)", device.userAgent)
    }

    @Test
    fun newLoginUuidIsLowercaseHexWithoutDashes() {
        repeat(20) {
            assertTrue(DeviceProfile.newLoginUuid().matches(Regex("^[0-9a-f]{32}$")))
        }
    }

    @Test
    fun generatePicksValuesFromReferencePools() {
        repeat(50) {
            val profile = DeviceProfile.generate()
            assertTrue(DeviceProfile.OS_VERSIONS.contains(profile.osVersion))
            assertTrue(DeviceProfile.DEVICE_TYPES.contains(profile.deviceType))
            assertTrue(profile.loginUuid.matches(Regex("^[0-9a-f]{32}$")))
            assertTrue(profile.userAgent.startsWith("123pan/v2.4.0(Android_"))
            assertTrue(profile.userAgent.endsWith(";Xiaomi)"))
        }
    }

    @Test
    fun poolsMatchReferenceSourceSizes() {
        assertEquals(14, DeviceProfile.OS_VERSIONS.size)
        assertEquals(691, DeviceProfile.DEVICE_TYPES.size)
    }
}
