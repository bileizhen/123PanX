package io.github.bileizhen.pan123x.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LoginDeviceApiTest {
    @Test fun requestUsesVerifiedEndpointQueryAndHeadersAndDiscardsDeviceCredentials() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"DeviceS":[{"deviceName":"Xiaomi","platform":"android","ip":"192.0.2.5","lastLoginTime":1790867736,"deviceType":"Android端","curDevice":true,"loginType":"账号登录","LoginUuid":"secret-login-uuid","key":"secret-device-key"}]}}"""))
            val api = PanApi(
                client = PanHttpClientFactory.defaultClient({ DeviceProfile(loginUuid = "test-device") }, AuthorizationProvider { "Bearer test-token" }),
                primaryBaseUrl = server.url("/").toString(), fallbackBaseUrl = null,
            )
            val devices = (api.getLoginDevices() as ApiResult.Success).data
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/b/api/user/device_list?operateType=2&event=deviceManagement", request.path)
            assertEquals("Bearer test-token", request.getHeader("authorization"))
            assertEquals("test-device", request.getHeader("loginuuid"))
            assertEquals("android", request.getHeader("platform"))
            assertEquals(LoginDeviceDto("Xiaomi", "android", "192.0.2.5", "1790867736", "Android端", true, "账号登录"), devices.single())
            assertFalse(devices.toString().contains("secret-"))
        }
    }

    @Test fun referenceAliasesAndEmptyPrimaryArrayAreSupported() {
        val list = LoginDeviceDto.listFromJson(Json.parseToJsonElement("""{"data":{"DeviceS":[],"device_list":[{"device_name":"Desktop","plat_form":"windows","login_ip":"192.0.2.1","last_login_time":"2026-10-01 23:15:36","device_type":"PC端","cur_device":0,"login_type":"扫码登录"}]}}"""))!!
        assertEquals("Desktop", list.single().name)
        assertEquals("windows", list.single().platform)
        assertFalse(list.single().current)
        assertEquals("2026-10-01 23:15:36", list.single().lastLoginTime)
        assertEquals(emptyList<LoginDeviceDto>(), LoginDeviceDto.listFromJson(Json.parseToJsonElement("""{"data":{}}""")))
    }

    @Test fun malformedArraysDoNotPretendToBeEmptySuccess() {
        listOf("""{"data":null}""", """{"data":{"DeviceS":{}}}""", """{"data":{"DeviceS":[1]}}""").forEach {
            assertNull(LoginDeviceDto.listFromJson(Json.parseToJsonElement(it)))
        }
    }

    @Test fun expiredSessionBusinessFailureAndInvalidJsonUseStandardApiResults() = runTest {
        MockWebServer().use { server ->
            server.start()
            val api = PanApi(primaryBaseUrl = server.url("/").toString(), fallbackBaseUrl = null)
            server.enqueue(MockResponse().setBody("""{"code":2,"message":"expired"}"""))
            assertTrue(api.getLoginDevices() is ApiResult.SessionExpired)
            server.enqueue(MockResponse().setBody("""{"code":403,"message":"设备信息暂不可用"}"""))
            assertEquals(ApiResult.ApiError(403, "设备信息暂不可用"), api.getLoginDevices())
            server.enqueue(MockResponse().setBody("not json"))
            assertTrue(api.getLoginDevices() is ApiResult.ParseError)
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"DeviceS":"wrong"}}"""))
            assertTrue(api.getLoginDevices() is ApiResult.ParseError)
        }
    }
}
