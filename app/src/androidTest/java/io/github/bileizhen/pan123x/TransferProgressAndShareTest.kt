package io.github.bileizhen.pan123x

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.*
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.transfer.download.*
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.*
import io.github.bileizhen.pan123x.core.transfer.storage.*
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.data.settings.*
import io.github.bileizhen.pan123x.data.transfer.TransferPartView
import io.github.bileizhen.pan123x.feature.transfer.*
import io.github.bileizhen.pan123x.ui.theme.PanXTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class TransferProgressAndShareTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ViewModelStore()
    private val folder = File(context.filesDir, "downloads/transfer-test-${System.nanoTime()}").apply { mkdirs() }
    private val work = File(context.cacheDir, "transfer-test-${System.nanoTime()}")
    private val bytes = "123PanX local download fixture".toByteArray()

    @Before fun seedAccount(): Unit = runBlocking { database.accountDao().upsert(AccountEntity("fixture", "Fixture")) }
    @After fun cleanup() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        compose.runOnIdle { store.clear() }
        database.close()
        folder.deleteRecursively()
        File(context.filesDir, "downloads/${folder.name}-range.bin").delete()
        work.deleteRecursively()
    }

    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun task(uri: String = Uri.fromFile(File(folder, "fixture.txt").apply { writeBytes(bytes) }).toString()) =
        TransferTaskEntity("fixture", "download", fileName = "fixture.txt", direction = TransferDirection.DOWNLOAD,
            state = TransferState.COMPLETED, size = bytes.size.toLong(), downloadedBytes = bytes.size.toLong(), targetUri = uri)

    private fun show(id: String) {
        val source = object : TransferTasksSource {
            override fun observeTasks() = database.transferTaskDao().observeTasks("fixture")
            override fun observeParts(accountId: String, taskId: String) = database.downloadSegmentDao().observe(accountId, taskId).map { list ->
                list.map { TransferPartView(it.segmentIndex, it.end - it.start, it.downloaded, it.downloaded >= it.end - it.start) }
            }
            override suspend fun pause(accountId: String, taskId: String) = Unit
            override suspend fun resume(accountId: String, taskId: String) = Unit
            override suspend fun cancel(accountId: String, taskId: String) = Unit
            override suspend fun resolveConflict(accountId: String, taskId: String, policy: ConflictPolicy) = Unit
            override suspend fun clearFinished(accountId: String) = Unit
        }
        val vm = TransferViewModel(source)
        store.put("transfer", vm)
        compose.setContent {
            PanXTheme(AppSettings(themeMode = ThemeMode.LIGHT, monet = false)) { TransferDetailScreen(vm, id) }
        }
        compose.waitUntil(5_000) { vm.uiState.value.tasks.any { it.taskId == id } }
    }

    @Test fun localRangeDownloadUpdatesRoomAndVisibleSegmentsThenSharesExactFile(): Unit = runBlocking {
        val body = ByteArray(11 * 1024 * 1024 + 1) { (it % 251).toByte() }
        val server = MockWebServer()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val header = request.getHeader("Range") ?: return MockResponse().setHeader("ETag", "\"fixture\"")
                    .setBody(Buffer().write(body)).throttleBody(128 * 1024L, 100, TimeUnit.MILLISECONDS)
                val range = header.removePrefix("bytes=").split('-')
                val start = range[0].toInt(); val end = range[1].toInt()
                return MockResponse().setResponseCode(206).setHeader("ETag", "\"fixture\"")
                    .setHeader("Content-Range", "bytes $start-$end/${body.size}")
                    .setBody(Buffer().write(body, start, end - start + 1))
                    .throttleBody(128 * 1024L, 100, TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        try {
            val config = NsfxConfig(threads = 2, enableDynamicSegments = false)
            val engine = NsfxDownloadEngine(config, NsfxHttpClient(config, OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()))
            val storage = DownloadStorage(context, AppLogger())
            val gateway = object : DownloadStorageGateway {
                override fun check(destination: DownloadDestination) = storage.check(destination)
                override fun open(destination: DownloadDestination, totalSize: Long, existingUri: String?) = storage.open(destination, totalSize, existingUri)
                override fun complete(opened: OpenedSink, destination: DownloadDestination, size: Long) = storage.complete(opened, destination, size)
                override fun discard(opened: OpenedSink, destination: DownloadDestination) = storage.discard(opened, destination)
            }
            val manager = AccountManager().apply { onLoginSuccess("fixture", "Fixture", "1", "test") }
            val coordinator = DownloadCoordinator(DownloadResolver { ResolveOutcome.Success(server.url("/file").toString(), false) },
                gateway, DownloadExecutor { request, checkpoints, sink, progress, telemetry -> engine.download(request, checkpoints, sink, progress, telemetry) },
                database.transferTaskDao(), database.downloadSegmentDao(), manager, AppLogger(), work, config)
            coordinator.attach(scope)
            val destinationName = "${folder.name}-range.bin"
            val id = coordinator.enqueue(DownloadSource(1, "range.bin", body.size.toLong(), "", "", false), DownloadDestination.Internal(destinationName))
            show(id)
            withTimeout(10_000) {
                database.downloadSegmentDao().observe("fixture", id).first { segments ->
                    segments.any { it.downloaded > 0 && it.downloaded < it.end - it.start }
                }
            }
            compose.onNodeWithTag("transfer-detail").performScrollToNode(hasTestTag("transfer_part_0"))
            compose.onNodeWithTag("transfer_part_0").assertIsDisplayed()
            val completed = withTimeout(20_000) { database.transferTaskDao().observeTasks("fixture").first { it.single().state == TransferState.COMPLETED }.single() }
            val parts = database.downloadSegmentDao().observe("fixture", id).first()
            assertEquals(2, parts.size)
            assertTrue(parts.all { it.downloaded == it.end - it.start })
            val intent = LocalDownloadFiles(context).prepare(completed, true)
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)!!
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(body, it.readBytes()) }
            compose.onNodeWithTag("transfer-detail").performScrollToNode(hasTestTag("download_share_file"))
            compose.onNodeWithTag("download_share_file").assertIsDisplayed()
            screenshot("transfer-completed.png")
        } finally { server.shutdown() }
    }

    @Test fun completedLegacyFileOpensAndSharesThroughChooserWithReadPermissionOnly(): Unit = runBlocking {
        val row = task()
        database.transferTaskDao().upsert(row)
        show(row.taskId)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val captured = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                captured.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            listOf("download_share_file" to Intent.ACTION_SEND, "download_open_file" to Intent.ACTION_VIEW).forEach { (tag, action) ->
                captured.set(null)
                compose.onNodeWithTag(tag).performClick()
                compose.waitUntil(5_000) { captured.get() != null }
                val chooser = captured.get()
                val intent = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
                assertEquals(action, intent.action)
                assertEquals("text/plain", intent.type)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val uri = intent.data ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)!!
                assertEquals("content", uri.scheme)
                assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
                assertEquals(uri, chooser.clipData!!.getItemAt(0).uri)
                context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun missingFileShowsRecoverableError(): Unit = runBlocking {
        val row = task(Uri.fromFile(File(folder, "missing.txt")).toString())
        database.transferTaskDao().upsert(row)
        show(row.taskId)
        compose.onNodeWithTag("download_share_file").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("download_file_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("download_file_error").assertTextEquals("文件不存在或已移除，请重新下载")
        compose.onNodeWithTag("download_share_file").assertIsEnabled()
    }

    @Test fun pausedSegmentsReactToRoomUpdatesAndDoNotOfferSharing(): Unit = runBlocking {
        val row = task().copy(state = TransferState.PAUSED, size = 1024, downloadedBytes = 256)
        database.transferTaskDao().upsert(row)
        database.downloadSegmentDao().insert(listOf(DownloadSegmentEntity("fixture", row.taskId, 0, 0, 1024, 256)))
        show(row.taskId)
        compose.onNodeWithTag("transfer-detail").performScrollToNode(hasTestTag("transfer_part_0"))
        compose.onNodeWithText("25% · 256 B / 1.0 KB").assertIsDisplayed()
        database.downloadSegmentDao().updateProgress(listOf(DownloadSegmentEntity("fixture", row.taskId, 0, 0, 1024, 768)))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("75% · 768 B / 1.0 KB").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("75% · 768 B / 1.0 KB").assertIsDisplayed()
        database.transferTaskDao().updateProgress("fixture", row.taskId, 768, TransferState.PAUSED.name, System.currentTimeMillis())
        compose.waitForIdle()
        screenshot("transfer-progress.png")
        compose.onNodeWithTag("download_share_file").assertDoesNotExist()
    }

    @Test fun legacyCompletedSegmentsDisplayFullProgressAndUploadsHaveNoDownloadActions(): Unit = runBlocking {
        val row = task()
        database.transferTaskDao().upsert(row)
        database.downloadSegmentDao().insert(listOf(DownloadSegmentEntity("fixture", row.taskId, 0, 0, 1024, 0)))
        show(row.taskId)
        compose.onNodeWithTag("transfer-detail").performScrollToNode(hasTestTag("transfer_part_0"))
        compose.onNodeWithText("100% · 1.0 KB / 1.0 KB").assertIsDisplayed()
        database.transferTaskDao().upsert(row.copy(direction = TransferDirection.UPLOAD))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("download_share_file").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun legacyFilesOutsideDownloadDirectoryAreRejected(): Unit = runBlocking {
        val privateFile = File(context.cacheDir, "private-share-test.txt").apply { writeText("private") }
        try {
            val error = LocalDownloadFiles(context).launch(task(Uri.fromFile(privateFile).toString()), true)
            assertEquals("无法读取此下载文件", error)
        } finally { privateFile.delete() }
    }
}
