package io.github.bileizhen.pan123x.core.transfer.download

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bileizhen.pan123x.AppContainer
import io.github.bileizhen.pan123x.PanXApplication
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadDestination
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机上的「暂停 → 续传」全链路验证（M4 最关键需求）。
 *
 * 这是**真实网络 + 真实账户**的集成测试，不是替身测试：
 * 走 `PanDownloadResolver` 取真链、`NsfxDownloadEngine` 真分段下载、`DownloadStorage` 真落盘。
 * 因此它需要设备上存在一个已登录且可下载的账户，否则以 `assumeTrue` 跳过（记为 ignored，不误报失败）。
 *
 * 断言的核心是 ：**短期 CDN signed URL 不是任务身份**。暂停时 NSFX 把当次解析出的
 * 签名 URL 记进 `segments.json`；续传时协调器会**重新取链**，因此会拿到一个 URL 不同的新地址
 * （真机实测：该 URL 含 `t=` 过期时间戳、`s=` 签名，以及每次请求都变的 `xmfcid` / `x-mf-biz-cid`）。
 * 若引擎把 URL 计入断点身份，续传会被判定失效 → `storage.reset` → 重写断点日志（URL 变成新地址）
 * 并从 0 重下。所以「续传后断点日志里的 URL 仍是暂停时那一个」正是"没有从头重下"的可观测证据。
 */
@RunWith(AndroidJUnit4::class)
class DownloadResumeInstrumentedTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<PanXApplication>().container
    }

    @Test
    fun pauseWritesCheckpointAndResumeKeepsItInsteadOfRestarting() = runBlocking {
        val session = container.accountManager.awaitRestored()
        assumeTrue("设备上没有已登录账户，跳过真实取链验证", session is SessionState.Ready)
        val accountId = (session as SessionState.Ready).accountId

        // 选一个真实的多分段候选（>8MB 才会被 SegmentPlanner 切段），且必须带真实 etag。
        val root = container.database.cloudFileDao().observeDirectory(accountId, 0L).first()
        val target = root.firstOrNull { !it.isFolder && it.size > MIN_MULTI_SEGMENT_BYTES && it.etag.isNotBlank() }
        assumeTrue("根目录没有 >8MB 的可下载文件，跳过", target != null)
        val file = target!!

        val taskDao = container.database.transferTaskDao()
        // 用独立文件名，避免与用户已有产物互相覆盖；测试结束在 finally 里清理。
        val taskId = container.downloadCoordinator.enqueue(
            source = file.toDownloadSource(),
            destination = DownloadDestination.Internal(PROBE_NAME),
        )

        try {
            val probe = pauseMidFlight(accountId, taskId, file.size)
            // 跳过时把终止原因一并带出：否则"没跑到 1/6"既可能是下载失败（如 5113 流量超限），
            // 也可能是网络太快已经下完，二者对排查的意义完全不同。
            assumeTrue(
                "下载在 1/6 进度前就结束，无法验证中途暂停（state=${probe.state}, error=${probe.error}）",
                probe.paused,
            )

            val row = taskDao.get(accountId, taskId)
            assertNotNull("任务行必须存在", row)
            assertEquals("暂停后必须是 PAUSED", TransferState.PAUSED, row!!.state)
            val checkpoint = row.downloadedBytes
            assertTrue("暂停时必须已落库部分进度，实际 $checkpoint", checkpoint > 0)
            assertTrue("暂停进度必须小于总大小，实际 $checkpoint / ${file.size}", checkpoint < file.size)

            // 断点状态必须落盘：权威来源是 NSFX 工作目录，不是 download_segments 表。
            val workDir = container.downloadCoordinator.workDirOf(accountId, taskId)
            val journal = File(workDir, "segments.json")
            assertTrue("断点日志 segments.json 必须留存：$journal", journal.isFile)
            val journalBefore = journal.readText()
            assertTrue("断点日志必须记录 size", journalBefore.contains("\"size\":${file.size}"))
            val offsets = workDir.listFiles().orEmpty().filter { it.name.endsWith(OFFSET_SUFFIX) }
            assertTrue("必须存在 <index>.offset 断点标记", offsets.isNotEmpty())
            val offsetSum = offsets.sumOf { it.readText().trim().toLong() }
            assertTrue("断点偏移之和必须 > 0（表示真的存了进度），实际 $offsetSum", offsetSum > 0)

            val urlBefore = urlOf(journalBefore)
            assertNotNull("断点日志必须记录 URL（仅诊断用，不参与身份）", urlBefore)

            // 续传：协调器会重新取链，URL 必然与暂停时不同。
            container.downloadCoordinator.resume(taskId)
            // 引擎的"是否可续传"决策发生在 probe 之后；若判失效会立刻 reset + save（URL 变新地址）。
            delay(RESTART_DETECT_WINDOW_MS)
            val journalAfter = runCatching { journal.readText() }.getOrNull()
            if (journalAfter != null) {
                assertEquals(
                    "续传不得重置断点：断点日志里的 URL 应仍是暂停时那一个（变了说明从头重下）",
                    urlBefore,
                    urlOf(journalAfter),
                )
            }

            val finalState = awaitTerminal(accountId, taskId)
            assertEquals("续传后必须完成", TransferState.COMPLETED, finalState)

            val done = taskDao.get(accountId, taskId)!!
            assertEquals("完成后 downloadedBytes 必须等于总大小", file.size, done.downloadedBytes)
            val artifact = File(container.downloadStorage.internalRoot(), PROBE_NAME)
            assertTrue("产物必须落盘：$artifact", artifact.isFile)
            assertEquals("产物大小必须等于云端 size", file.size, artifact.length())
        } finally {
            cleanup(accountId, taskId)
        }
    }

    /** 暂停探测结果：是否成功在下载中途暂停，以及未成功时的终止状态与错误文案。 */
    private data class PauseProbe(val paused: Boolean, val state: TransferState?, val error: String?)

    /**
     * 等到进度超过 1/6 再暂停，避免"还没开始就暂停"或"已经下完"两种无效窗口。
     */
    private suspend fun pauseMidFlight(accountId: String, taskId: String, size: Long): PauseProbe {
        val taskDao = container.database.transferTaskDao()
        var paused = false
        var terminal: TransferState? = null
        var error: String? = null
        withTimeout(PAUSE_WINDOW_MS) {
            while (true) {
                val row = taskDao.get(accountId, taskId) ?: return@withTimeout
                if (row.state == TransferState.COMPLETED || row.state == TransferState.FAILED) {
                    terminal = row.state
                    error = row.error
                    return@withTimeout
                }
                if (row.downloadedBytes > size / 6) {
                    container.downloadCoordinator.pause(taskId)
                    paused = true
                    return@withTimeout
                }
                delay(POLL_MS)
            }
        }
        return PauseProbe(paused, terminal, error)
    }

    private suspend fun awaitTerminal(accountId: String, taskId: String): TransferState? {
        val taskDao = container.database.transferTaskDao()
        var terminal: TransferState? = null
        withTimeout(RESUME_WINDOW_MS) {
            while (true) {
                val row = taskDao.get(accountId, taskId) ?: return@withTimeout
                if (row.state == TransferState.COMPLETED || row.state == TransferState.FAILED) {
                    terminal = row.state
                    return@withTimeout
                }
                delay(POLL_MS)
            }
        }
        return terminal
    }

    private fun urlOf(journal: String): String? =
        URL_RE.find(journal)?.groupValues?.get(1)

    /** 清理本次测试产生的任务行（级联删除分段）、产物与断点目录，避免污染用户数据。 */
    private suspend fun cleanup(accountId: String, taskId: String) {
        runCatching { container.database.transferTaskDao().delete(accountId, taskId) }
        runCatching { File(container.downloadStorage.internalRoot(), PROBE_NAME).delete() }
        runCatching { container.downloadCoordinator.workDirOf(accountId, taskId).deleteRecursively() }
    }

    private companion object {
        const val PROBE_NAME = "m4-resume-probe.bin"
        const val OFFSET_SUFFIX = ".offset"
        const val POLL_MS = 60L
        const val PAUSE_WINDOW_MS = 30_000L
        const val RESTART_DETECT_WINDOW_MS = 800L
        const val RESUME_WINDOW_MS = 300_000L
        /** 8MB 是 SegmentPlanner 的切段下限，小于它的文件拿不到多分段。 */
        const val MIN_MULTI_SEGMENT_BYTES = 9L * 1024 * 1024
        val URL_RE = Regex("\"url\":\"([^\"]*)\"")
    }
}
