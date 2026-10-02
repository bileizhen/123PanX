package io.github.bileizhen.pan123x.data.settings

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateRepositoryTest {
    private fun release(tag: String = "v0.5.0", repo: String = ReleaseParser.REPOSITORY) = """{
        "tag_name":"$tag","draft":false,"prerelease":false,
        "html_url":"https://github.com/$repo/releases/tag/$tag","body":"Release notes",
        "assets":[{"name":"123PanX.apk","browser_download_url":"https://github.com/$repo/releases/download/$tag/123PanX.apk"}]
    }"""
    @Test fun validatesOwnRepositoryAssetAndComparesStableAndDebugVersions() {
        assertTrue(ReleaseParser.parseRelease(release(), "0.4.0-m4-debug") is UpdateResult.Available)
        assertEquals(UpdateResult.Current, ReleaseParser.parseRelease(release("v0.4.0"), "0.4.0-m4-debug"))
        assertTrue(ReleaseParser.parseRelease(release(repo = "other/repo"), "0.4.0") is UpdateResult.Failed)
        assertTrue(ReleaseParser.parseRelease(release().replace("123PanX.apk", "unrelated.txt"), "0.4.0") is UpdateResult.Failed)
        assertTrue(ReleaseParser.parseRelease(release().replace("\"prerelease\":false", "\"prerelease\":true"), "0.4.0") is UpdateResult.Failed)
        assertTrue(ReleaseParser.parseRelease(release("v0.5.0-beta.1"), "0.4.0") is UpdateResult.Failed)
    }
    @Test fun distinguishesMissingReleaseInvalidJsonRateLimitAndLatest() = runBlocking {
        MockWebServer().use { server ->
            val repository = UpdateRepository(OkHttpClient(), "0.4.0", server.url("/latest"))
            server.enqueue(MockResponse().setResponseCode(404)); assertEquals(UpdateResult.Unpublished, repository.check())
            server.enqueue(MockResponse().setBody("not-json")); assertTrue(repository.check() is UpdateResult.Failed)
            server.enqueue(MockResponse().setResponseCode(429)); assertTrue((repository.check() as UpdateResult.Failed).message.contains("限流"))
            server.enqueue(MockResponse().setBody(release())); assertTrue(repository.check() is UpdateResult.Available)
            repeat(4) { val request = server.takeRequest(); assertNull(request.getHeader("authorization")); assertNull(request.getHeader("loginuuid")) }
        }
    }
    @Test fun boundsReleaseMetadataBeforeParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(256 * 1024 + 1)))
            val result = UpdateRepository(OkHttpClient(), "0.4.0", server.url("/latest")).check()
            assertTrue(result is UpdateResult.Failed); assertTrue((result as UpdateResult.Failed).message.contains("过大"))
        }
    }
}
