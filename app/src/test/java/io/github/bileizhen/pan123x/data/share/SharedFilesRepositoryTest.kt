package io.github.bileizhen.pan123x.data.share

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.DownloadLinkDto
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.FileListDto
import io.github.bileizhen.pan123x.core.network.PanSharedFilesApi
import io.github.bileizhen.pan123x.core.transfer.download.DownloadSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SharedFilesRepository 行为测试（纯 JVM、不触网）：
 * - save：未登录短路、提交去重、轮询直至完成并回调 onSaved、失败/超时文案、
 *   SessionExpired 重登一次、轮询途中切账户中止；
 * - download：递归展开文件夹（含分页）、文件名带父目录前缀、来源携带 shareKey、
 *   入队计数与切账户中止。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedFilesRepositoryTest {

    private class FakePanSharedFilesApi : PanSharedFilesApi {
        val listCalls = mutableListOf<Triple<Long, Int, String>>()
        val saveCalls = mutableListOf<Pair<List<Long>, Long>>()
        val statusCalls = mutableListOf<String>()
        val downloadLinkCalls = mutableListOf<Long>()
        private val listQueue = ArrayDeque<ApiResult<FileListDto>>()
        private val saveQueue = ArrayDeque<ApiResult<io.github.bileizhen.pan123x.core.network.SharedSaveTask>>()
        private val statusQueue = ArrayDeque<ApiResult<io.github.bileizhen.pan123x.core.network.SharedSaveTask>>()
        private val linkQueue = ArrayDeque<ApiResult<DownloadLinkDto>>()

        fun enqueueList(result: ApiResult<FileListDto>) { listQueue.addLast(result) }
        fun enqueueSave(result: ApiResult<io.github.bileizhen.pan123x.core.network.SharedSaveTask>) { saveQueue.addLast(result) }
        fun enqueueStatus(vararg results: ApiResult<io.github.bileizhen.pan123x.core.network.SharedSaveTask>) { results.forEach { statusQueue.addLast(it) } }
        fun enqueueLink(result: ApiResult<DownloadLinkDto>) { linkQueue.addLast(result) }

        override suspend fun sharedFiles(key: String, password: String, parentId: Long, page: Int, next: String): ApiResult<FileListDto> {
            listCalls.addLast(Triple(parentId, page, next))
            return listQueue.removeFirstOrNull() ?: error("缺少第 ${listCalls.size} 次 sharedFiles 响应")
        }

        override suspend fun saveSharedFiles(key: String, password: String, files: List<FileItemDto>, targetId: Long): ApiResult<io.github.bileizhen.pan123x.core.network.SharedSaveTask> {
            saveCalls.addLast(files.map { it.fileId } to targetId)
            return saveQueue.removeFirstOrNull() ?: error("缺少第 ${saveCalls.size} 次 saveSharedFiles 响应")
        }

        override suspend fun sharedSaveStatus(taskId: String): ApiResult<io.github.bileizhen.pan123x.core.network.SharedSaveTask> {
            statusCalls.addLast(taskId)
            // 挂起点让"测试主体切账户"落在状态查询期间，覆盖循环顶部的切账户检查。
            kotlinx.coroutines.yield()
            return statusQueue.removeFirstOrNull() ?: error("缺少第 ${statusCalls.size} 次 sharedSaveStatus 响应")
        }

        override suspend fun sharedDownloadLink(source: DownloadSource): ApiResult<DownloadLinkDto> {
            downloadLinkCalls.addLast(source.fileId)
            return linkQueue.removeFirstOrNull() ?: error("缺少第 ${downloadLinkCalls.size} 次 sharedDownloadLink 响应")
        }
    }

    private class Fixture(loggedIn: Boolean = true) {
        val api = FakePanSharedFilesApi()
        val manager = AccountManager()
        var reloginResult = true
        var reloginCalls = 0
        val enqueued = mutableListOf<Pair<DownloadSource, String?>>()
        val savedTo = mutableListOf<Pair<String, Long>>()
        /** 每次轮询间隔把"等待"变成挂起点，保证测试协程调度可见。 */
        var polls = 0
        val repository = SharedFilesRepository(
            api = api,
            manager = manager,
            relogin = { reloginCalls++; reloginResult },
            enqueue = { source, tree ->
                // 挂起点让"测试主体切账户"可插入，模拟真实入队的耗时窗口。
                kotlinx.coroutines.yield()
                enqueued.addLast(source to tree)
                io.github.bileizhen.pan123x.core.transfer.download.LaunchOutcome.Queued(source.fileName)
            },
            onSaved = { accountId, targetId -> savedTo.addLast(accountId to targetId) },
            pollDelay = { polls++ },
        )

        init {
            if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        }
    }

    private companion object {
        fun file(id: Long, name: String = "f$id", folder: Boolean = false, parent: Long = 0) = FileItemDto(
            fileId = id, parentFileId = parent, fileName = name, isFolder = folder, size = if (folder) 0 else 100,
            etag = "e$id", s3KeyFlag = "s3$id", contentType = "", createAt = 0, updateAt = 0, hidden = false, starred = false, pinyin = "",
        )

        fun list(vararg items: FileItemDto, next: String = "-1") = ApiResult.Success(FileListDto(infoList = items.toList(), total = items.size, next = next, len = items.size, isFirst = true))
    }

    // ------------------------------------------------------------------
    // save
    // ------------------------------------------------------------------

    @Test
    fun saveWithoutSessionReturnsLoginHintAndSkipsApi() = runTest {
        val f = Fixture(loggedIn = false)
        val error = f.repository.save("key", "pwd", listOf(file(1)), 0)
        assertEquals("请先登录后保存至云盘", error)
        assertTrue(f.api.saveCalls.isEmpty())
    }

    @Test
    fun saveDeduplicatesFilesAndPollsUntilCompleteThenNotifies() = runTest {
        val f = Fixture()
        f.api.enqueueSave(ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = false)))
        f.api.enqueueStatus(
            ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = false)),
            ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = true)),
        )
        val error = f.repository.save("key", "pwd", listOf(file(1), file(1), file(2)), 7)
        assertNull(error)
        assertEquals(listOf(listOf(1L, 2L) to 7L), f.api.saveCalls)
        assertEquals(listOf("t1", "t1"), f.api.statusCalls)
        assertEquals(2, f.polls)
        assertEquals(listOf("acc-1" to 7L), f.savedTo)
    }

    @Test
    fun saveReportsFailureWhenTaskMarksFailed() = runTest {
        val f = Fixture()
        f.api.enqueueSave(ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = false)))
        f.api.enqueueStatus(ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = false, failed = true)))
        val error = f.repository.save("key", "pwd", listOf(file(1)), 0)
        assertEquals("转存失败，请稍后重试", error)
        assertTrue(f.savedTo.isEmpty())
    }

    @Test
    fun saveMapsSubmitErrorFromSharedError() = runTest {
        val f = Fixture()
        f.api.enqueueSave(ApiResult.NetworkError("offline"))
        val error = f.repository.save("key", "pwd", listOf(file(1)), 0)
        assertEquals("网络连接失败，请稍后重试", error)
        assertTrue(f.api.statusCalls.isEmpty())
    }

    @Test
    fun saveRetriesOnceAfterReloginOnSessionExpired() = runTest {
        val f = Fixture()
        f.api.enqueueSave(ApiResult.SessionExpired)
        f.api.enqueueSave(ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = true)))
        val error = f.repository.save("key", "pwd", listOf(file(1)), 3)
        assertNull(error)
        assertEquals(1, f.reloginCalls)
        assertEquals(2, f.api.saveCalls.size)
        assertEquals(listOf("acc-1" to 3L), f.savedTo)
    }

    @Test
    fun saveAbortsWhenAccountSwitchesDuringPolling() = runTest {
        val f = Fixture()
        f.api.enqueueSave(ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = false)))
        f.api.enqueueStatus(ApiResult.Success(io.github.bileizhen.pan123x.core.network.SharedSaveTask("t1", complete = false)))
        var error: String? = "未完成"
        val job = launch { error = f.repository.save("key", "pwd", listOf(file(1)), 0) }
        kotlinx.coroutines.yield()
        // 第二个账户登录后，旧账户的轮询应立即中止。
        f.manager.onLoginSuccess("acc-2", "other", "43", "Bearer token-2")
        job.join()
        assertEquals("账户已切换，请在原账户检查转存结果", error)
        assertTrue(f.savedTo.isEmpty())
    }

    // ------------------------------------------------------------------
    // download
    // ------------------------------------------------------------------

    @Test
    fun downloadWithoutSessionReturnsLoginHint() = runTest {
        val f = Fixture(loggedIn = false)
        val result = f.repository.download("key", "pwd", listOf(file(1)), null)
        assertEquals(0, result.queued)
        assertEquals("请先登录后下载", result.error)
        assertTrue(f.enqueued.isEmpty())
    }

    @Test
    fun downloadExpandsFoldersRecursivelyAndQueuesFilesWithShareIdentity() = runTest {
        val f = Fixture()
        // 只选中文件 1 与文件夹"相册"；相册内两个文件分两页返回。
        f.api.enqueueList(list(file(11, "a.jpg", parent = 10), next = "cursor-1"))
        f.api.enqueueList(list(file(12, "b.jpg", parent = 10)))
        val result = f.repository.download("key", "pwd", listOf(file(1), file(10, "相册", folder = true)), "/tree")
        assertNull(result.error)
        assertEquals(3, result.queued)
        val sources = f.enqueued.map { it.first }
        assertEquals(setOf(1L, 11L, 12L), sources.map { it.fileId }.toSet())
        // 文件夹内文件名带父目录前缀，落盘不重名。
        assertEquals("相册 - a.jpg", sources.single { it.fileId == 11L }.fileName)
        // 全部来源携带分享身份与目标树。
        sources.forEach { assertEquals("key", it.shareKey); assertEquals("pwd", it.sharePassword) }
        assertTrue(f.enqueued.all { it.second == "/tree" })
    }

    @Test
    fun downloadReportsListErrorWithoutQueueingAnything() = runTest {
        val f = Fixture()
        f.api.enqueueList(ApiResult.ApiError(5104, "分享已失效"))
        val result = f.repository.download("key", "pwd", listOf(file(10, "d", folder = true)), null)
        assertEquals(0, result.queued)
        assertEquals("分享已失效", result.error)
        assertTrue(f.enqueued.isEmpty())
    }

    @Test
    fun downloadEmptyFolderYieldsHint() = runTest {
        val f = Fixture()
        f.api.enqueueList(list())
        val result = f.repository.download("key", "pwd", listOf(file(10, "d", folder = true)), null)
        assertEquals("所选文件夹为空", result.error)
        assertEquals(0, result.queued)
    }

    @Test
    fun downloadStopsQueueingWhenAccountSwitches() = runTest {
        val f = Fixture()
        f.api.enqueueList(list(file(1), file(2)))
        var result = SharedQueueResult(0, "未完成")
        val job = launch { result = f.repository.download("key", "pwd", listOf(file(1), file(2)), null) }
        kotlinx.coroutines.yield()
        f.manager.onLoginSuccess("acc-2", "other", "43", "Bearer token-2")
        job.join()
        assertEquals(1, result.queued)
        assertEquals("账户已切换，请重新操作", result.error)
    }
}
