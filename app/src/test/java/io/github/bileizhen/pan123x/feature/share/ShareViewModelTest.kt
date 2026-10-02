@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.share

import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.network.ShareItemDto
import io.github.bileizhen.pan123x.data.share.ShareActions
import io.github.bileizhen.pan123x.data.share.ShareListStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 构造协议行样例：字段覆盖 ShareItemDto 全量（DTO 是 data class，非默认字段须逐个给出）。
 * 顶层函数：FakeShareActions 是嵌套类，无法访问外部类的实例成员。
 */
private fun item(
    id: Long,
    shareLink: String = "",
    shareUrl: String = "",
    shareKey: String = "key$id",
    sharePwd: String = "",
    expired: Boolean = false,
) = ShareItemDto(
    shareId = id,
    shareKey = shareKey,
    fileIdList = "$id",
    downloadCount = 1,
    previewCount = 2,
    saveCount = 3,
    shareName = "分享$id",
    expiration = "2099-12-12T08:00:00+08:00",
    expired = expired,
    sharePwd = sharePwd,
    status = 1,
    createAt = "",
    updateAt = "",
    shareUrl = shareUrl,
    shareLink = shareLink,
)

/**
 * ShareViewModel 行为测试（纯 JVM，注入 [ShareActions] 假实现，零 android 依赖）。
 *
 * 覆盖：状态映射（LOADING/EMPTY/CONTENT/ERROR 且失败保留旧列表）、撤销确认状态机
 * （request → 弹窗目标 → dismiss/confirm、无请求时 confirm 是 no-op、成功跟随仓库移除行、
 * 失败跟随仓库 error）、copyLink 三级链接回退 + copiedId 置位、加载更多 no-op 语义
 * （列表不再增长即隐藏按钮，刷新后重新允许尝试）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {

    private val owner = ViewModelStore()
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun cleanup() {
        owner.clear()
        Dispatchers.resetMain()
    }

    // ---- 替身 ----

    /** ShareRepository 的手工假实现：状态完全由测试驱动，不做任何网络 / 账户动作。 */
    private class FakeShareActions : ShareActions {
        val mutableShares = MutableStateFlow<List<ShareItemDto>>(emptyList())
        val mutableStatus = MutableStateFlow(ShareListStatus.LOADING)
        val mutableError = MutableStateFlow<String?>(null)

        var refreshCalls = 0
        var loadMoreCalls = 0
        val revoked = mutableListOf<Long>()
        var revokeResult = true
        /** loadMore 是否还有下一页（true = 追加一行，模拟分页增长；false = no-op）。 */
        var hasNextPage = false
        /** refresh 的结果状态（模拟"仓库把一切失败折成状态"的契约）。 */
        var refreshOutcome: ShareListStatus = ShareListStatus.CONTENT
        /** 非 null 时 refresh 挂起在门闩上，模拟请求在途（页面停留 LOADING）。 */
        var refreshGate: CompletableDeferred<Unit>? = null

        override val shares: StateFlow<List<ShareItemDto>> = mutableShares
        override val status: StateFlow<ShareListStatus> = mutableStatus
        override val error: StateFlow<String?> = mutableError

        override suspend fun refresh() {
            refreshCalls++
            refreshGate?.await()
            mutableStatus.value = refreshOutcome
        }

        override suspend fun loadMore() {
            loadMoreCalls++
            if (hasNextPage) {
                mutableShares.value = mutableShares.value + item(id = 100L + loadMoreCalls)
            }
        }

        override suspend fun revoke(shareId: Long): Boolean {
            revoked += shareId
            if (revokeResult) {
                mutableShares.value = mutableShares.value.filterNot { it.shareId == shareId }
                mutableStatus.value = if (mutableShares.value.isEmpty()) ShareListStatus.EMPTY else ShareListStatus.CONTENT
            } else {
                mutableError.value = "撤销失败，请稍后重试"
            }
            return revokeResult
        }

        fun seed(vararg items: ShareItemDto) {
            mutableShares.value = items.toList()
        }
    }

    // ---- 用例 ----

    @Test fun exposesRestoreAndLoginStatesWithoutPrematureRefresh() {
        val repo = FakeShareActions()
        val session = MutableStateFlow<io.github.bileizhen.pan123x.core.account.SessionState>(io.github.bileizhen.pan123x.core.account.SessionState.Restoring)
        val model = ShareViewModel(repo, copy = {}, session = session, autoRefresh = false).also { owner.put("share-session", it) }
        assertTrue(model.uiState.value.restoring)
        assertEquals(0, repo.refreshCalls)
        session.value = io.github.bileizhen.pan123x.core.account.SessionState.LoggedOut
        assertTrue(model.uiState.value.loggedOut)
        assertFalse(model.uiState.value.restoring)
        session.value = io.github.bileizhen.pan123x.core.account.SessionState.Ready("new-account", "name", "1")
        assertEquals("new-account", model.uiState.value.accountId)
        assertFalse(model.uiState.value.loggedOut)
    }

    @Test
    fun statusMappingCoversLoadingEmptyContentAndError() {
        val repo = FakeShareActions()
        val gate = CompletableDeferred<Unit>()
        repo.refreshGate = gate
        val model = ShareViewModel(repo, copy = {}).also { owner.put("share", it) }

        // init 主动 refresh 挂起在门闩上 → 页面停留在仓库的初始 LOADING。
        assertEquals(ShareListStatus.LOADING, model.uiState.value.status)
        assertEquals(1, repo.refreshCalls)

        repo.refreshOutcome = ShareListStatus.EMPTY
        gate.complete(Unit)
        assertEquals(ShareListStatus.EMPTY, model.uiState.value.status)

        repo.seed(item(1), item(2))
        repo.refreshOutcome = ShareListStatus.CONTENT
        model.refresh()
        assertEquals(ShareListStatus.CONTENT, model.uiState.value.status)
        assertEquals(listOf(1L, 2L), model.uiState.value.shares.map { it.shareId })

        // 失败置 ERROR + 文案，但旧列表保留（网络失败不清空列表）。
        repo.refreshOutcome = ShareListStatus.ERROR
        repo.mutableError.value = "服务繁忙，请稍后重试"
        model.refresh()
        assertEquals(ShareListStatus.ERROR, model.uiState.value.status)
        assertEquals("服务繁忙，请稍后重试", model.uiState.value.error)
        assertEquals(2, model.uiState.value.shares.size)
    }

    @Test
    fun revokeRequiresExplicitConfirmationAndFollowsRepositoryResult() {
        val repo = FakeShareActions()
        repo.seed(item(11), item(22))
        val model = ShareViewModel(repo, copy = {}).also { owner.put("share", it) }

        // 未请求撤销时 confirm 是 no-op（旧 Mock 时代即有的回归保护）。
        model.confirmRevoke()
        assertEquals(0, repo.revoked.size)

        model.requestRevoke(11L)
        assertEquals(11L, model.uiState.value.revokingId)
        model.dismissRevoke()
        assertNull(model.uiState.value.revokingId)
        model.confirmRevoke()
        assertEquals(0, repo.revoked.size)

        // 确认后调仓库 revoke，弹窗目标清空；成功后仓库移除该行，列表跟随。
        model.requestRevoke(11L)
        model.confirmRevoke()
        assertEquals(listOf(11L), repo.revoked)
        assertNull(model.uiState.value.revokingId)
        assertEquals(listOf(22L), model.uiState.value.shares.map { it.shareId })

        // 失败：VM 不镜像结果，仓库的 error 直接进入 UiState，行仍在列表中。
        repo.revokeResult = false
        model.requestRevoke(22L)
        model.confirmRevoke()
        assertEquals(listOf(11L, 22L), repo.revoked)
        assertEquals(listOf(22L), model.uiState.value.shares.map { it.shareId })
        assertEquals("撤销失败，请稍后重试", model.uiState.value.error)
    }

    @Test
    fun copyLinkFallsBackThroughShareLinkShareUrlAndShareKey() {
        val repo = FakeShareActions()
        repo.refreshOutcome = ShareListStatus.CONTENT
        val copied = mutableListOf<String>()
        val model = ShareViewModel(repo, copy = { copied += it }).also { owner.put("share", it) }

        // 一级：协议 shareLink 优先。
        model.copyLink(item(1, shareLink = "https://link.example/1", shareUrl = "https://url.example/1"))
        assertEquals(listOf("https://link.example/1"), copied)
        assertEquals(1L, model.uiState.value.copiedId)

        // 二级：shareLink 缺失用 shareUrl。
        model.copyLink(item(2, shareUrl = "https://url.example/2"))
        assertEquals(listOf("https://link.example/1", "https://url.example/2"), copied)
        assertEquals(2L, model.uiState.value.copiedId)

        // 三级：两者皆空按 ShareKey 拼规范形式（file_service.py:415-416）。
        model.copyLink(item(3, shareKey = "abc"))
        assertEquals(
            listOf("https://link.example/1", "https://url.example/2", "https://www.123pan.cn/s/abc"),
            copied,
        )
        assertEquals(3L, model.uiState.value.copiedId)
    }

    @Test
    fun loadMoreDisablesAfterListStopsGrowingAndRefreshReenables() {
        val repo = FakeShareActions()
        repo.refreshOutcome = ShareListStatus.CONTENT
        repo.seed(item(1))
        val model = ShareViewModel(repo, copy = {}).also { owner.put("share", it) }

        // 刷新后保守允许"试一页"。
        assertTrue(model.uiState.value.canLoadMore)

        repo.hasNextPage = true
        model.loadMore()
        assertEquals(1, repo.loadMoreCalls)
        assertTrue("追加成功后保持可加载", model.uiState.value.canLoadMore)

        // 仓库 no-op（无更多）→ 列表长度不再增长 → 按钮自动消失。
        repo.hasNextPage = false
        model.loadMore()
        assertEquals(2, repo.loadMoreCalls)
        assertFalse(model.uiState.value.canLoadMore)

        // 刷新重置游标后重新允许尝试。
        model.refresh()
        assertTrue(model.uiState.value.canLoadMore)
    }
}
