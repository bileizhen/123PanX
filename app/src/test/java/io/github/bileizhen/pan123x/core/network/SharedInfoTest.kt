package io.github.bileizhen.pan123x.core.network

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SharedInfoTest {
    @Test fun officialMetadataAndLocalFileDatesAreParsed() {
        val info = SharedInfoDto.fromJson(Json.parseToJsonElement("""{"data":{"ShareName":"Root工具模块.zip","UserNickName":"133****6243","HasPwd":true,"Expiration":"2099-12-12T08:00:00+08:00","CreateAt":"2026-09-06T19:11:03+08:00","Expired":false,"IsSVip":1}}""").jsonObject)!!
        assertEquals("133****6243", info.owner)
        assertEquals(true, info.hasPassword)
        assertTrue(info.vip)
        assertFalse(info.expired)
        assertEquals(Instant.parse("2099-12-12T00:00:00Z").toEpochMilli(), info.expiration)
        val file = FileItemDto.fromJsonObject(Json.parseToJsonElement("""{"FileId":1,"Status":2,"UpdateAt":"2026/9/6 19:09:23"}""").jsonObject)
        assertEquals(Instant.parse("2026-09-06T11:09:23Z").toEpochMilli(), file.updateAt)
        assertEquals(2, file.status)
    }
    @Test fun timestampFormatsAndUnavailableValuesRemainDistinct() {
        val expected = Instant.parse("2026-09-06T11:09:23Z").toEpochMilli()
        for (text in listOf("2026/9/6 19:09:23", "2026-09-06 19:09:23", "2026-09-06T19:09:23+08:00", "2026-09-06T11:09:23Z", expected.toString(), (expected / 1000).toString())) {
            assertEquals(text, expected, parsePanTimestamp(text))
        }
        for (text in listOf("", "null", "invalid", "NaN", "Infinity", "0", "-1")) assertEquals(0L, parsePanTimestamp(text))
        assertNull(SharedInfoDto.fromJson(Json.parseToJsonElement("""{"data":{}}""").jsonObject))
        assertEquals("", SharedInfoDto.fromJson(Json.parseToJsonElement("""{"data":{"UserNickName":null}}""").jsonObject)!!.owner)
        assertNull(FileItemDto.fromJsonObject(Json.parseToJsonElement("""{"FileId":1}""").jsonObject).status)
    }
    @Test fun metadataReadUsesGetAndEncodesShareKey() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"UserNickName":"owner","Expired":true}}"""))
            val api = PanApi(client = PanHttpClientFactory.defaultClient(device = { DeviceProfile(loginUuid = "0123456789abcdef0123456789abcdef") }, auth = AuthorizationProvider { "" }),
                primaryBaseUrl = server.url("/").toString(), fallbackBaseUrl = null)
            val result = api.sharedInfo("key & value")
            assertTrue(result is ApiResult.Success)
            assertTrue((result as ApiResult.Success).data!!.expired)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/b/api/share/info", request.requestUrl!!.encodedPath)
            assertEquals("key & value", request.requestUrl!!.queryParameter("shareKey"))
        } finally { server.shutdown() }
    }
}
