package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateDao
import io.github.bileizhen.pan123x.core.database.DirectoryStateEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.FileListDto
import io.github.bileizhen.pan123x.core.network.PanFileApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileRepository 行为测试（纯 JVM、不触网、不依赖 Room）。
 *
 * 手写 PanFileApi / 两个 DAO 的内存替身 + 真 AccountManager / AppLogger；节流节奏用
 * kotlinx-coroutines-test 的虚拟时间断言（delay 在 TestScope 中被跳过但计入 currentTime）。
 * CloudFileDao 是带 @Transaction 方法的抽象类——替身只实现内存原语（observe / get /
 * upsert / deleteDirectory / upsertDirectoryState），事务编排（require 守卫 + 删旧 +
 * 写新 + 写状态）直接复用基类真实现，因此断言同时覆盖仓库与事务编排的协作。
 */
class FileRepositoryTest {

    private data class ApiCall(val parentFileId: Long, val page: Int, val limit: Int, val trashed: Boolean)

    /** 可编排多页序列并记录调用的 API 替身。 */
    private class FakePanFileApi : PanFileApi {
        val calls = mutableListOf<ApiCall>()
        private val queue = ArrayDeque<ApiResult<FileListDto>>()
        var onFetch: (() -> Unit)? = null

        fun enqueue(result: ApiResult<FileListDto>) {
            queue.addLast(result)
        }

        /** 按 total / pageSize 生成一串成功页（fileId 从 1 递增，便于去重断言）。 */
        fun enqueuePages(total: Int, pageSize: Int = 100, parentFileId: Long = 0L) {
            var nextId = 1L
            var remaining = total
            while (remaining > 0) {
                val size = minOf(pageSize, remaining)
                val items = List(size) { item(nextId++, parentFileId) }
                remaining -= size
                enqueue(ApiResult.Success(FileListDto(items, total, next = "-1", len = size, isFirst = false)))
            }
        }

        override suspend fun getFileList(
            parentFileId: Long,
            page: Int,
            limit: Int,
            trashed: Boolean,
        ): ApiResult<FileListDto> {
            calls.addLast(ApiCall(parentFileId, page, limit, trashed))
            onFetch?.invoke()
            return queue.removeFirstOrNull() ?: error("缺少编排好的第 ${calls.size} 次响应")
        }
    }

    /** 两个 DAO 替身共享的内存存储，保证文件与目录状态落在同一个小“数据库”里。 */
    private class InMemoryDirectoryStore {
        val files = MutableStateFlow<List<CloudFileEntity>>(emptyList())
        val states = MutableStateFlow<Map<Pair<String, Long>, DirectoryStateEntity>>(emptyMap())
    }

    private class FakeCloudFileDao(private val store: InMemoryDirectoryStore) : CloudFileDao() {
        val current: List<CloudFileEntity> get() = store.files.value

        override fun observeDirectory(accountId: String, parentFileId: Long): Flow<List<CloudFileEntity>> =
            store.files
                .map { list -> list.filter { it.accountId == accountId && it.parentFileId == parentFileId } }
                .distinctUntilChanged()

        override suspend fun get(accountId: String, fileId: Long): CloudFileEntity? =
            store.files.value.firstOrNull { it.accountId == accountId && it.fileId == fileId }

        override suspend fun upsert(files: List<CloudFileEntity>) {
            val incoming = files.mapTo(mutableSetOf()) { it.accountId to it.fileId }
            store.files.value = store.files.value.filterNot { (it.accountId to it.fileId) in incoming } + files
        }

        override suspend fun deleteDirectory(accountId: String, parentFileId: Long) {
            store.files.value = store.files.value
                .filterNot { it.accountId == accountId && it.parentFileId == parentFileId }
        }

        override suspend fun upsertDirectoryState(state: DirectoryStateEntity) {
            store.states.value = store.states.value + ((state.accountId to state.dirId) to state)
        }
    }

    private class FakeDirectoryStateDao(private val store: InMemoryDirectoryStore) : DirectoryStateDao {
        override fun observe(accountId: String, dirId: Long): Flow<DirectoryStateEntity?> =
            store.states.map { it[accountId to dirId] }.distinctUntilChanged()

        override suspend fun get(accountId: String, dirId: Long): DirectoryStateEntity? =
            store.states.value[accountId to dirId]

        override suspend fun upsert(state: DirectoryStateEntity) {
            store.states.value = store.states.value + ((state.accountId to state.dirId) to state)
        }

        override suspend fun delete(accountId: String, dirId: Long) {
            store.states.value = store.states.value - (accountId to dirId)
        }
    }

    private class ReloginStub {
        var result = true
        var calls = 0
        val invoke: suspend () -> Boolean = {
            calls++
            result
        }
    }

    private class Fixture(
        val api: FakePanFileApi,
        val files: FakeCloudFileDao,
        val states: FakeDirectoryStateDao,
        val manager: AccountManager,
        val relogin: ReloginStub,
        val repository: FileRepository,
    )

    private fun fixture(loggedIn: Boolean = true): Fixture {
        val api = FakePanFileApi()
        val store = InMemoryDirectoryStore()
        val files = FakeCloudFileDao(store)
        val states = FakeDirectoryStateDao(store)
        val manager = AccountManager()
        if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        val relogin = ReloginStub()
        val repository = FileRepository(
            api = api,
            cloudFileDao = files,
            directoryStateDao = states,
            manager = manager,
            relogin = relogin.invoke,
            logger = AppLogger(),
        )
        return Fixture(api, files, states, manager, relogin, repository)
    }

    private companion object {
        fun item(
            fileId: Long,
            parentFileId: Long = 0L,
            name: String = "file-$fileId",
            folder: Boolean = false,
        ) = FileItemDto(
            fileId = fileId,
            parentFileId = parentFileId,
            fileName = name,
            isFolder = folder,
            size = fileId * 10,
            etag = "etag-$fileId",
            s3KeyFlag = "flag-$fileId",
            contentType = "application/octet-stream",
            createAt = 1_700_000_000_000L + fileId,
            updateAt = 1_700_000_100_000L + fileId,
            hidden = false,
            starred = false,
            pinyin = "",
        )
    }

    private fun firstPage(count: Int, total: Int, parentFileId: Long = 0L) =
        ApiResult.Success(
            FileListDto(
                infoList = List(count) { item(fileId = 1_000L + it, parentFileId = parentFileId) },
                total = total,
                next = "-1",
                len = count,
                isFirst = true,
            ),
        )

    @Test
    fun accountSwitchDuringFetchDiscardsResponseAndKeepsExistingCache() = runTest {
        val f = fixture()
        val cached = CloudFileEntity("acc-1", 99, 0, "cached.txt", isFolder = false)
        f.files.upsert(listOf(cached))
        f.api.enqueue(firstPage(count = 100, total = 200))
        f.api.onFetch = { f.manager.onLoginSuccess("acc-2", "second", "2", "Bearer fake") }
        assertEquals(RefreshOutcome.Failure(FileMessages.ACCOUNT_CHANGED), f.repository.refreshDirectory(0))
        assertEquals(listOf(cached), f.files.current)
        assertEquals(1, f.api.calls.size)
        assertNull(f.states.get("acc-1", 0))
    }

    @Test
    fun singlePageSuccessReplacesCacheAndWritesState() = runTest {
        val f = fixture()
        f.files.upsert(listOf(CloudFileEntity("acc-1", 99, 0, "stale.txt", isFolder = false)))

        f.api.enqueue(
            ApiResult.Success(
                FileListDto(listOf(item(1, folder = true), item(2)), total = 2, next = "-1", len = 2, isFirst = true),
            ),
        )
        assertEquals(RefreshOutcome.Success, f.repository.refreshDirectory(0))

        // 旧缓存（fileId 99）被整体替换，映射逐字段来自 DTO（isFolder / 毫秒时间）
        val cached = f.files.current
        assertEquals(2, cached.size)
        val folder = cached.single { it.fileId == 1L }
        assertTrue(folder.isFolder)
        assertEquals("file-1", folder.fileName)
        assertEquals(10L, folder.size)
        assertEquals("etag-1", folder.etag)
        assertEquals("flag-1", folder.s3KeyFlag)
        assertEquals(1_700_000_000_001L, folder.createAt)
        assertEquals(1_700_000_100_001L, folder.updateAt)
        val state = f.states.get("acc-1", 0)
        assertEquals(2, state?.total)
        assertEquals(true, state?.allLoaded)
        assertTrue(state != null && state.updatedAt >= 0)
        assertEquals(listOf(ApiCall(parentFileId = 0, page = 1, limit = 100, trashed = false)), f.api.calls)
    }

    @Test
    fun multiPageLoopStopsAtTotalWithoutThrottle() = runTest {
        val f = fixture()
        f.api.enqueuePages(total = 250) // 100 + 100 + 50，共 3 页

        assertEquals(RefreshOutcome.Success, f.repository.refreshDirectory(0))

        assertEquals(listOf(1, 2, 3), f.api.calls.map { it.page })
        assertEquals(250, f.files.current.size)
        assertEquals(250, f.states.get("acc-1", 0)?.total)
        // 3 页不足 5 页：无节流等待（虚拟时间不前进）
        assertEquals(0L, currentTime)
    }

    @Test
    fun fullLoadThrottlesEveryFivePages() = runTest {
        val f = fixture()
        f.api.enqueuePages(total = 550) // 6 页

        assertEquals(RefreshOutcome.Success, f.repository.refreshDirectory(0))

        assertEquals(listOf(1, 2, 3, 4, 5, 6), f.api.calls.map { it.page })
        // 第 5 页取完后等待 500ms 才继续第 6 页，唯一一次节流
        assertEquals(500L, currentTime)
    }

    @Test
    fun pageFailureKeepsPreviousCacheUntouched() = runTest {
        val f = fixture()
        f.files.upsert(listOf(CloudFileEntity("acc-1", 9, 0, "cached.txt", isFolder = false)))
        f.api.enqueue(firstPage(count = 100, total = 150)) // 第 1 页成功，还差 50
        f.api.enqueue(ApiResult.NetworkError("connect timeout")) // 第 2 页失败

        val outcome = f.repository.refreshDirectory(0)

        assertEquals(RefreshOutcome.Failure("网络连接失败，请检查网络后重试"), outcome)
        // 部分失败绝不落库：旧缓存与目录状态原样保留
        assertEquals(listOf("cached.txt"), f.files.current.map { it.fileName })
        assertNull(f.states.get("acc-1", 0))
        assertEquals(2, f.api.calls.size)
    }

    @Test
    fun sessionExpiredReloginsAndRetriesSamePageOnce() = runTest {
        val f = fixture()
        f.api.enqueue(ApiResult.SessionExpired)
        f.api.enqueue(firstPage(count = 100, total = 101))
        f.api.enqueue(
            ApiResult.Success(
                FileListDto(listOf(item(2_000)), total = 101, next = "-1", len = 1, isFirst = false),
            ),
        )

        val outcome = f.repository.refreshDirectory(0)

        assertEquals(RefreshOutcome.Success, outcome)
        // 第 1 页：过期 -> 重登 -> 重试当页成功；然后第 2 页
        assertEquals(listOf(1, 1, 2), f.api.calls.map { it.page })
        assertEquals(1, f.relogin.calls)
        assertEquals(101, f.files.current.size)
    }

    @Test
    fun reloginFailureFailsWithSessionExpiredMessageAndKeepsCache() = runTest {
        val f = fixture()
        f.relogin.result = false
        f.api.enqueue(ApiResult.SessionExpired)

        assertEquals(
            RefreshOutcome.Failure("登录状态已失效，请重新登录"),
            f.repository.refreshDirectory(0),
        )
        assertEquals(1, f.api.calls.size)
        assertEquals(1, f.relogin.calls)
        assertNull(f.states.get("acc-1", 0))
    }

    @Test
    fun retryStillExpiredFailsAndSendsNoFurtherRequests() = runTest {
        val f = fixture()
        f.api.enqueue(ApiResult.SessionExpired)
        f.api.enqueue(ApiResult.SessionExpired)

        assertEquals(
            RefreshOutcome.Failure("登录状态已失效，请重新登录"),
            f.repository.refreshDirectory(0),
        )
        // 原次 + 重试当页一次，之后不再发请求
        assertEquals(2, f.api.calls.size)
        assertEquals(1, f.relogin.calls)
    }

    @Test
    fun notLoggedInFailsWithoutCallingApi() = runTest {
        val f = fixture(loggedIn = false)

        assertEquals(RefreshOutcome.Failure("请先登录"), f.repository.refreshDirectory(0))
        assertTrue(f.api.calls.isEmpty())
    }

    @Test
    fun trashedFlagIsPassedThroughToApi() = runTest {
        val f = fixture()
        f.api.enqueue(
            ApiResult.Success(FileListDto(emptyList(), total = 0, next = "-1", len = 0, isFirst = true)),
        )

        assertEquals(RefreshOutcome.Success, f.repository.refreshDirectory(dirId = 7, trashed = true))

        assertEquals(listOf(ApiCall(parentFileId = 7, page = 1, limit = 100, trashed = true)), f.api.calls)
        // 空目录也落状态：区分“确为空”与“从未加载”
        assertEquals(0, f.states.get("acc-1", 7)?.total)
        assertEquals(true, f.states.get("acc-1", 7)?.allLoaded)
    }

    @Test
    fun emptyInfoListStopsEvenWhenTotalIsPositive() = runTest {
        val f = fixture()
        f.api.enqueue(
            ApiResult.Success(FileListDto(emptyList(), total = 100, next = "-1", len = 0, isFirst = true)),
        )

        assertEquals(RefreshOutcome.Success, f.repository.refreshDirectory(0))

        assertEquals(1, f.api.calls.size)
        assertTrue(f.files.current.isEmpty())
        assertEquals(100, f.states.get("acc-1", 0)?.total)
    }

    @Test
    fun apiErrorWithBlankMessageFallsBackToErrorCode() = runTest {
        val f = fixture()
        f.api.enqueue(ApiResult.ApiError(code = 500, message = " "))

        assertEquals(RefreshOutcome.Failure("加载失败（错误码 500）"), f.repository.refreshDirectory(0))
    }

    @Test
    fun apiErrorMessagePassesThroughWhenNotBlank() = runTest {
        val f = fixture()
        f.api.enqueue(ApiResult.ApiError(code = 4001, message = "请求过于频繁"))

        assertEquals(RefreshOutcome.Failure("请求过于频繁"), f.repository.refreshDirectory(0))
    }

    @Test
    fun parseErrorMapsToReadableMessage() = runTest {
        val f = fixture()
        f.api.enqueue(ApiResult.ParseError("invalid json"))

        assertEquals(RefreshOutcome.Failure("服务器响应异常，请稍后重试"), f.repository.refreshDirectory(0))
    }

    @Test
    fun snapshotIsEmptyWhenLoggedOut() = runTest {
        val f = fixture(loggedIn = false)

        assertEquals(
            DirectorySnapshot(files = emptyList(), total = 0, allLoaded = false, updatedAt = null),
            f.repository.observeDirectory(0).first(),
        )
    }

    @Test
    fun snapshotCombinesCacheAndStateForReadyAccount() = runTest {
        val f = fixture()
        f.files.upsert(listOf(CloudFileEntity("acc-1", 1, 0, "a.txt", isFolder = false)))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 3, allLoaded = true, updatedAt = 77L))

        val snapshot = f.repository.observeDirectory(0).first()

        assertEquals(listOf("a.txt"), snapshot.files.map { it.fileName })
        assertEquals(3, snapshot.total)
        assertTrue(snapshot.allLoaded)
        assertEquals(77L, snapshot.updatedAt)
    }

    @Test
    fun snapshotFallsBackToCacheSizeWhenStateMissing() = runTest {
        val f = fixture()
        f.files.upsert(listOf(CloudFileEntity("acc-1", 1, 0, "a.txt", isFolder = false)))

        val snapshot = f.repository.observeDirectory(0).first()

        assertEquals(1, snapshot.total)
        assertEquals(false, snapshot.allLoaded)
        assertNull(snapshot.updatedAt)
    }
}
