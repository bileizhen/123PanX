package io.github.bileizhen.pan123x.data.share

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.CreateShareDto
import io.github.bileizhen.pan123x.core.network.PanShareApi
import io.github.bileizhen.pan123x.core.network.ShareItemDto
import io.github.bileizhen.pan123x.core.network.SharePageDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShareRepository 行为测试（纯 JVM、不触网）。
 *
 * 手写 PanShareApi 替身 + 真 AccountManager / AppLogger，对齐 FileRepositoryTest 风格：
 * - refresh 整体替换与 EMPTY / CONTENT 推导、失败保留旧列表置 ERROR；
 * - loadMore 追加 + 游标推进 + "-1" 后 no-op + 加载中 no-op（替身挂起构造并发窗口）；
 * - create 去重 fileIds（替身捕获入参断言）；revoke 成功移除行 / 失败写 error；
 * - reset 清空；未登录时全部操作安全且不发请求；
 * - code==2 经注入 relogin 重登一次并仅重试一次（对齐 FileOpsRepository.withReauth）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShareRepositoryTest {

    /** 可编排响应序列并记录调用的 API 替身；[gate] 非空时 listShares 挂起直至放行。 */
    private class FakePanShareApi : PanShareApi {
        val createCalls = mutableListOf<Pair<List<Long>, String>>()
        val listCalls = mutableListOf<Triple<Int, String, String>>()
        val deleteCalls = mutableListOf<Long>()
        private val createQueue = ArrayDeque<ApiResult<CreateShareDto>>()
        private val listQueue = ArrayDeque<ApiResult<SharePageDto>>()
        private val deleteQueue = ArrayDeque<ApiResult<Unit>>()

        var gate: CompletableDeferred<Unit>? = null

        fun enqueueCreate(result: ApiResult<CreateShareDto>) {
            createQueue.addLast(result)
        }

        fun enqueueList(result: ApiResult<SharePageDto>) {
            listQueue.addLast(result)
        }

        fun enqueueDelete(result: ApiResult<Unit>) {
            deleteQueue.addLast(result)
        }

        override suspend fun createShare(fileIds: List<Long>, options: io.github.bileizhen.pan123x.core.share.ShareCreateOptions): ApiResult<CreateShareDto> {
            createCalls.addLast(fileIds to options.password)
            return createQueue.removeFirstOrNull() ?: error("缺少第 ${createCalls.size} 次 createShare 响应")
        }

        override suspend fun listShares(limit: Int, next: String, search: String): ApiResult<SharePageDto> {
            gate?.await()
            listCalls.addLast(Triple(limit, next, search))
            return listQueue.removeFirstOrNull() ?: error("缺少第 ${listCalls.size} 次 listShares 响应")
        }

        override suspend fun deleteShare(shareId: Long): ApiResult<Unit> {
            deleteCalls.addLast(shareId)
            return deleteQueue.removeFirstOrNull() ?: error("缺少第 ${deleteCalls.size} 次 deleteShare 响应")
        }
    }

    private class ReloginStub {
        var result = true
        var calls = 0
        val invoke: () -> Boolean = {
            calls++
            result
        }
    }

    private class Fixture(loggedIn: Boolean = true) {
        val api = FakePanShareApi()
        val manager = AccountManager()
        val relogin = ReloginStub()
        val repository = ShareRepository(
            api = api,
            manager = manager,
            relogin = relogin.invoke,
            logger = AppLogger(),
        )

        init {
            if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        }
    }

    private companion object {
        fun item(shareId: Long, name: String = "s-$shareId") = ShareItemDto(
            shareId = shareId,
            shareKey = "key$shareId",
            fileIdList = "$shareId",
            downloadCount = 0,
            previewCount = 0,
            saveCount = 0,
            shareName = name,
            expiration = "",
            expired = false,
            sharePwd = "",
            status = 1,
            createAt = "",
            updateAt = "",
            shareUrl = "",
            shareLink = "",
        )

        fun page(vararg items: ShareItemDto, next: String = "-1", total: Int = items.size) =
            ApiResult.Success(SharePageDto(next = next, total = total, items = items.toList()))
    }

    // ------------------------------------------------------------------
    // refresh
    // ------------------------------------------------------------------

    @Test
    fun sessionBindingClearsOldSharesBeforePublishingNewAccountAndLoadsOnDemand() = runTest {
        val f = Fixture(loggedIn = false)
        f.repository.bindSession(backgroundScope)
        runCurrent()
        assertTrue(f.api.listCalls.isEmpty())
        f.manager.onLoginSuccess("acc-1", "first", "1", "Bearer test")
        runCurrent()
        assertEquals("acc-1", (f.repository.sessionState.value as io.github.bileizhen.pan123x.core.account.SessionState.Ready).accountId)
        f.api.enqueueList(page(item(1)))
        f.repository.refresh()
        f.manager.onLoginSuccess("acc-2", "second", "2", "Bearer test")
        runCurrent()
        assertTrue(f.repository.shares.value.isEmpty())
        assertEquals("acc-2", (f.repository.sessionState.value as io.github.bileizhen.pan123x.core.account.SessionState.Ready).accountId)
        assertEquals(1, f.api.listCalls.size)
        f.api.enqueueList(page(item(2)))
        f.repository.refresh()
        assertEquals(listOf(2L), f.repository.shares.value.map { it.shareId })
        f.manager.onLogout()
        runCurrent()
        assertTrue(f.repository.shares.value.isEmpty())
        assertEquals(io.github.bileizhen.pan123x.core.account.SessionState.LoggedOut, f.repository.sessionState.value)
    }

    @Test
    fun refreshReplacesListAndDerivesContentStatus() = runTest {
        val f = Fixture()
        assertEquals(ShareListStatus.LOADING, f.repository.status.value)

        f.api.enqueueList(page(item(1), item(2)))
        f.repository.refresh()

        assertEquals(listOf(1L, 2L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.CONTENT, f.repository.status.value)
        assertNull(f.repository.error.value)
        // 首页固定 next="0"、limit=500（参考源 share_service.py:34 默认值）
        assertEquals(listOf(Triple(500, "0", "")), f.api.listCalls)
        assertFalse(f.repository.canLoadMore)
    }

    @Test
    fun refreshEmptyPageYieldsEmptyStatus() = runTest {
        val f = Fixture()

        f.api.enqueueList(page())
        f.repository.refresh()

        assertTrue(f.repository.shares.value.isEmpty())
        assertEquals(ShareListStatus.EMPTY, f.repository.status.value)
        assertFalse(f.repository.canLoadMore)
    }

    @Test
    fun refreshFailureKeepsOldListAndSetsError() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1), item(2)))
        f.repository.refresh()

        f.api.enqueueList(ApiResult.ApiError(4000, "分享服务不可用"))
        f.repository.refresh()

        // 失败保留旧列表与旧游标（网络失败不清数据）
        assertEquals(listOf(1L, 2L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.ERROR, f.repository.status.value)
        assertEquals("分享服务不可用", f.repository.error.value)
        assertEquals(2, f.api.listCalls.size)
    }

    @Test
    fun refreshFailureMessagesMapByResultType() = runTest {
        val f = Fixture()
        f.api.enqueueList(ApiResult.ApiError(4000, " "))
        f.repository.refresh()
        assertEquals(ShareMessages.LIST_FAILED, f.repository.error.value)

        f.api.enqueueList(ApiResult.NetworkError("connect timeout"))
        f.repository.refresh()
        assertEquals(ShareMessages.NETWORK, f.repository.error.value)

        f.api.enqueueList(ApiResult.ParseError("invalid json"))
        f.repository.refresh()
        assertEquals(ShareMessages.MALFORMED, f.repository.error.value)
    }

    @Test
    fun refreshSessionExpiredReloginRetriesOnce() = runTest {
        val f = Fixture()
        f.api.enqueueList(ApiResult.SessionExpired)
        f.api.enqueueList(page(item(1)))

        f.repository.refresh()

        assertEquals(listOf(1L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.CONTENT, f.repository.status.value)
        assertEquals(1, f.relogin.calls)
        assertEquals(2, f.api.listCalls.size)
    }

    @Test
    fun refreshSessionExpiredWithoutReloginFailsWithCopy() = runTest {
        val f = Fixture()
        f.relogin.result = false
        f.api.enqueueList(ApiResult.SessionExpired)

        f.repository.refresh()

        assertTrue(f.repository.shares.value.isEmpty())
        assertEquals(ShareMessages.SESSION_EXPIRED, f.repository.error.value)
        assertEquals(1, f.relogin.calls)
        // 重试结果不再做第二次重登
        assertEquals(1, f.api.listCalls.size)
    }

    @Test
    fun refreshWithExistingContentShowsRefreshingState() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1)))
        f.repository.refresh()

        // 挂起在替身 gate 上时，旧内容仍在 → 状态为 REFRESHING 而非 LOADING
        val gate = CompletableDeferred<Unit>()
        f.api.gate = gate
        f.api.enqueueList(page(item(1)))
        val job = launch { f.repository.refresh() }
        runCurrent()
        assertEquals(ShareListStatus.REFRESHING, f.repository.status.value)

        gate.complete(Unit)
        job.join()
        assertEquals(ShareListStatus.CONTENT, f.repository.status.value)
    }

    // ------------------------------------------------------------------
    // loadMore
    // ------------------------------------------------------------------

    @Test
    fun loadMoreAppendsAndAdvancesCursorUntilNoMore() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1), next = "42", total = 2))
        f.repository.refresh()

        f.api.enqueueList(page(item(2), next = "-1", total = 2))
        f.repository.loadMore()

        assertEquals(listOf(1L, 2L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.CONTENT, f.repository.status.value)
        assertEquals(listOf("0", "42"), f.api.listCalls.map { it.second })
        assertFalse(f.repository.canLoadMore)

        // 游标 "-1" 后 loadMore 为 no-op
        f.repository.loadMore()
        assertEquals(2, f.api.listCalls.size)
    }

    @Test
    fun loadMoreBeforeFirstRefreshIsNoop() = runTest {
        val f = Fixture()

        f.repository.loadMore()

        assertTrue(f.api.listCalls.isEmpty())
        assertFalse(f.repository.canLoadMore)
    }

    @Test
    fun loadMoreIsNoopWhileAnotherLoadIsInFlight() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1), next = "9"))
        f.repository.refresh()

        val gate = CompletableDeferred<Unit>()
        f.api.gate = gate
        f.api.enqueueList(page(item(2), next = "-1"))
        val job = launch { f.repository.loadMore() }
        runCurrent() // job 已挂起在 gate 上并持有 in-flight 标记

        f.repository.loadMore() // 第二次调用应为 no-op

        gate.complete(Unit)
        job.join()
        assertEquals(2, f.api.listCalls.size)
        assertEquals(listOf(1L, 2L), f.repository.shares.value.map { it.shareId })
        assertFalse(f.repository.canLoadMore)
    }

    @Test
    fun loadMoreFailureKeepsListAndCursorForRetry() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1), next = "9"))
        f.repository.refresh()

        f.api.enqueueList(ApiResult.NetworkError("timeout"))
        f.repository.loadMore()

        assertEquals(listOf(1L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.ERROR, f.repository.status.value)
        assertEquals(ShareMessages.NETWORK, f.repository.error.value)
        assertTrue(f.repository.canLoadMore)

        // 旧游标保留：重试成功后照常追加并清错
        f.api.enqueueList(page(item(2), next = "-1"))
        f.repository.loadMore()
        assertEquals(listOf(1L, 2L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.CONTENT, f.repository.status.value)
        assertNull(f.repository.error.value)
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    @Test
    fun createDedupesFileIdsAndReturnsCreatedUrl() = runTest {
        val f = Fixture()
        f.api.enqueueCreate(ApiResult.Success(CreateShareDto(shareKey = "abc", url = "https://www.123pan.cn/s/abc")))

        val outcome = f.repository.create(fileIds = listOf(7, 7, 9), sharePwd = "ab12")

        assertEquals(ShareOutcome.Created("https://www.123pan.cn/s/abc"), outcome)
        // 替身捕获入参：fileIds 去重后才传给 API（file_service.py:390 归一化语义）
        assertEquals(listOf(listOf(7L, 9L) to "ab12"), f.api.createCalls)
    }

    @Test
    fun createSessionExpiredReloginRetriesOnce() = runTest {
        val f = Fixture()
        f.api.enqueueCreate(ApiResult.SessionExpired)
        f.api.enqueueCreate(ApiResult.Success(CreateShareDto("k", "https://www.123pan.cn/s/k")))

        val outcome = f.repository.create(listOf(5), "")

        assertEquals(ShareOutcome.Created("https://www.123pan.cn/s/k"), outcome)
        assertEquals(1, f.relogin.calls)
        assertEquals(2, f.api.createCalls.size)
    }

    @Test
    fun createFailureMapsServerMessageAndBlankFallback() = runTest {
        val f = Fixture()
        f.api.enqueueCreate(ApiResult.ApiError(4000, "包含违规内容"))
        assertEquals(ShareOutcome.Failed("包含违规内容"), f.repository.create(listOf(1), ""))

        f.api.enqueueCreate(ApiResult.ApiError(4000, " "))
        assertEquals(ShareOutcome.Failed(ShareMessages.CREATE_FAILED), f.repository.create(listOf(1), ""))
    }

    @Test
    fun createEmptySelectionFailsWithoutApiCall() = runTest {
        val f = Fixture()

        val outcome = f.repository.create(emptyList(), "")

        assertEquals(ShareOutcome.Failed(ShareMessages.CREATE_EMPTY_SELECTION), outcome)
        assertTrue(f.api.createCalls.isEmpty())
    }

    // ------------------------------------------------------------------
    // revoke
    // ------------------------------------------------------------------

    @Test
    fun revokeSuccessRemovesRowAndReturnsTrue() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1), item(2)))
        f.repository.refresh()

        f.api.enqueueDelete(ApiResult.Success(Unit))
        assertTrue(f.repository.revoke(1))
        assertEquals(listOf(2L), f.repository.shares.value.map { it.shareId })
        assertNull(f.repository.error.value)
        assertEquals(listOf(1L), f.api.deleteCalls)

        // 撤销到空列表后推导 EMPTY 态
        f.api.enqueueDelete(ApiResult.Success(Unit))
        assertTrue(f.repository.revoke(2))
        assertTrue(f.repository.shares.value.isEmpty())
        assertEquals(ShareListStatus.EMPTY, f.repository.status.value)
    }

    @Test
    fun revokeFailureWritesErrorAndReturnsFalse() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1)))
        f.repository.refresh()

        f.api.enqueueDelete(ApiResult.ApiError(4000, " "))
        assertFalse(f.repository.revoke(1))
        // 失败不移动列表、状态保持 CONTENT，仅写用户可读 error
        assertEquals(listOf(1L), f.repository.shares.value.map { it.shareId })
        assertEquals(ShareListStatus.CONTENT, f.repository.status.value)
        assertEquals(ShareMessages.REVOKE_FAILED, f.repository.error.value)
    }

    // ------------------------------------------------------------------
    // reset / 未登录
    // ------------------------------------------------------------------

    @Test
    fun resetClearsAllState() = runTest {
        val f = Fixture()
        f.api.enqueueList(page(item(1), item(2)))
        f.repository.refresh()

        f.repository.reset()

        assertTrue(f.repository.shares.value.isEmpty())
        assertEquals(ShareListStatus.LOADING, f.repository.status.value)
        assertNull(f.repository.error.value)
        assertFalse(f.repository.canLoadMore)
        // reset 后未重新加载首页前 loadMore 是 no-op
        f.repository.loadMore()
        assertEquals(1, f.api.listCalls.size)
    }

    @Test
    fun notLoggedInOperationsAreSafeWithoutApiCalls() = runTest {
        val f = Fixture(loggedIn = false)

        f.repository.refresh()
        f.repository.loadMore()
        assertTrue(f.api.listCalls.isEmpty())
        assertEquals(ShareListStatus.LOADING, f.repository.status.value)

        val outcome = f.repository.create(listOf(1, 2), "")
        assertEquals(ShareOutcome.Failed(ShareMessages.NOT_LOGGED_IN), outcome)
        assertTrue(f.api.createCalls.isEmpty())

        assertFalse(f.repository.revoke(1))
        assertTrue(f.api.deleteCalls.isEmpty())
        assertEquals(ShareMessages.NOT_LOGGED_IN, f.repository.error.value)
    }
}
