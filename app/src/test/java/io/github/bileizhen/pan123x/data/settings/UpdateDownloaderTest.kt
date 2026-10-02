package io.github.bileizhen.pan123x.data.settings

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class UpdateDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = "fixture APK bytes".toByteArray()
    private fun release() = AppRelease("0.5.0", "Notes", "https://github.com/bileizhen/123PanX/releases/tag/v0.5.0",
        "https://github.com/bileizhen/123PanX/releases/download/v0.5.0/123PanX-0.5.0.apk", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun secureServer(block: (MockWebServer, OkHttpClient, File) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            block(server, client, folder.newFolder())
        }
    }

    @Test fun followsHttpsRedirectsVerifiesDigestAndReusesOnlyVerifiedCache() = secureServer { server, client, dir -> runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/asset"))
        server.enqueue(MockResponse().setBody(String(bytes)))
        val downloader = UpdateDownloader(client, dir) { _, _ -> server.url("/download").toString() }
        val progress = mutableListOf<Long>()
        val file = downloader.download(release(), UpdateSource.GITHUB) { read, _ -> progress.add(read) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), progress.last())
        repeat(2) { val request = server.takeRequest(); assertNull(request.getHeader("authorization")); assertNull(request.getHeader("loginuuid")) }
        assertEquals(file, downloader.download(release(), UpdateSource.MIRROR) { _, _ -> })
        assertEquals(2, server.requestCount)
        file.writeBytes("tampered".toByteArray())
        server.enqueue(MockResponse().setBody(String(bytes)))
        assertArrayEquals(bytes, downloader.download(release(), UpdateSource.MIRROR) { _, _ -> }.readBytes())
        assertEquals(3, server.requestCount)
    } }

    @Test fun rejectsDigestMismatchAndIncompleteBodiesAndCleansTemporaryFiles() = secureServer { server, client, dir -> runBlocking {
        val downloader = UpdateDownloader(client, dir) { _, _ -> server.url("/asset").toString() }
        server.enqueue(MockResponse().setBody("x".repeat(bytes.size)))
        assertTrue(runCatching { downloader.download(release(), UpdateSource.MIRROR) { _, _ -> } }.isFailure)
        server.enqueue(MockResponse().setChunkedBody("short", 2))
        assertTrue(runCatching { downloader.download(release(), UpdateSource.GITHUB) { _, _ -> } }.isFailure)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    } }

    @Test fun refusesHttpsDowngradeAndHttpError() = secureServer { server, client, dir -> runBlocking {
        val downloader = UpdateDownloader(client, dir) { _, _ -> server.url("/asset").toString() }
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:${server.port}/insecure"))
        assertTrue(runCatching { downloader.download(release(), UpdateSource.GITHUB) { _, _ -> } }.isFailure)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(runCatching { downloader.download(release(), UpdateSource.GITHUB) { _, _ -> } }.isFailure)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    } }

    @Test fun cancellationStopsBlockedNetworkCallAndRemovesPartialFile() = secureServer { server, client, dir -> runBlocking {
        server.enqueue(MockResponse().setBody(String(bytes)).setBodyDelay(2, TimeUnit.SECONDS))
        val downloader = UpdateDownloader(client, dir) { _, _ -> server.url("/asset").toString() }
        val job = async { downloader.download(release(), UpdateSource.GITHUB) { _, _ -> } }
        while (server.requestCount == 0) delay(10)
        withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    } }
}
