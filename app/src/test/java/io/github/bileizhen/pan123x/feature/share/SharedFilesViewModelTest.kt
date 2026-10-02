@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.bileizhen.pan123x.feature.share

import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.FileListDto
import io.github.bileizhen.pan123x.data.share.SharedFilesActions
import io.github.bileizhen.pan123x.data.share.SharedQueueResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * SharedFilesViewModel 行为测试（纯 JVM）：加载与分页、目录进退与选中清理、
 * 提取码上限、save/download 的 busy 与反馈状态、入队成功后 queuedRevision 推进。
 */
@RunWith(JUnit4::class)
class SharedFilesViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() { Dispatchers.setMain(mainDispatcher) }

    @After
    fun cleanup() { Dispatchers.resetMain() }

    /** SharedFilesActions 假实现：响应由队列驱动，入参全部记录。 */
    private class FakeSharedFilesActions : SharedFilesActions {
        val listCalls = mutableListOf<Triple<Long, Int, String>>()
        val saveCalls = mutableListOf<Pair<List<Long>, Long>>()
        val downloadCalls = mutableListOf<String?>()
        var keySeen: String = ""
        var passwordSeen: String = ""
        private val listQueue = ArrayDeque<ApiResult<FileListDto>>()
        var saveResult: String? = null
        /** 非 null 时 save 挂起在门闩上，模拟转存在途（busy 窗口）。 */
        var saveGate: CompletableDeferred<Unit>? = null
        var downloadResult = SharedQueueResult(0)

        fun enqueueList(vararg results: ApiResult<FileListDto>) { results.forEach { listQueue.addLast(it) } }

        override suspend fun list(key: String, password: String, parentId: Long, page: Int, next: String): ApiResult<FileListDto> {
            keySeen = key
            passwordSeen = password
            listCalls.addLast(Triple(parentId, page, next))
            return listQueue.removeFirstOrNull() ?: error("缺少第 ${listCalls.size} 次 list 响应")
        }

        override suspend fun save(key: String, password: String, files: List<FileItemDto>, targetId: Long): String? {
            saveCalls.addLast(files.map { it.fileId } to targetId)
            saveGate?.await()
            return saveResult
        }

        override suspend fun download(key: String, password: String, files: List<FileItemDto>, tree: String?): SharedQueueResult {
            downloadCalls.addLast(tree)
            return downloadResult
        }
    }

    private fun model(link: String = "https://1838272570.mshare.123pan.cn/123pan/O0mFTd-uHjIh?pwd=kkaE", actions: SharedFilesActions) =
        SharedFilesViewModel(io.github.bileizhen.pan123x.core.share.SharedLink(link, "kkaE"), actions)

    private fun file(id: Long, name: String = "f$id", folder: Boolean = false) = FileItemDto(
        fileId = id, parentFileId = 0, fileName = name, isFolder = folder, size = 100, etag = "e",
        s3KeyFlag = "s", contentType = "", createAt = 0, updateAt = 0, hidden = false, starred = false, pinyin = "",
    )

    @Test
    fun initLoadsRootListWithLinkKeyAndPasswordAndExtractsKeyFromMobileUrl() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(listOf(file(1), file(2, "d", folder = true)), 2, "-1", 2, true)))
        val vm = model(actions = actions)
        val state = vm.state.value
        // 移动端链接路径段 O0mFTd-uHjIh 即 shareKey；提取码来自解析结果。
        assertEquals("O0mFTd-uHjIh", actions.keySeen)
        assertEquals("kkaE", actions.passwordSeen)
        assertEquals(listOf(0L to "全部文件"), state.trail)
        assertEquals(listOf(1L, 2L), state.files.map { it.fileId })
        assertTrue(!state.loading)
        assertNull(state.error)
    }

    @Test
    fun enterFolderClearsSelectionAndAncestorTruncatesTrail() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(
            ApiResult.Success(FileListDto(listOf(file(2, "相册", folder = true)), 1, "-1", 1, true)),
            ApiResult.Success(FileListDto(listOf(file(11)), 1, "-1", 1, true)),
            ApiResult.Success(FileListDto(listOf(file(11)), 1, "-1", 1, true)),
        )
        val vm = model(actions = actions)
        vm.toggle(2)
        vm.enter(vm.state.value.files.single { it.isFolder })
        assertEquals(listOf(0L to "全部文件", 2L to "相册"), vm.state.value.trail)
        assertTrue(vm.state.value.selected.isEmpty())
        assertEquals(2L, actions.listCalls.last().first)
        // 面包屑截断回根目录会重新加载根列表。
        vm.ancestor(0)
        assertEquals(0L, actions.listCalls.last().first)
        assertEquals(listOf(0L to "全部文件"), vm.state.value.trail)
    }

    @Test
    fun passwordInputIsCappedAtFourCharacters() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(emptyList(), 0, "-1", 0, true)))
        val vm = model(actions = actions)
        vm.password("abcd1234")
        assertEquals("abcd", vm.state.value.password)
    }

    @Test
    fun listFailureSurfacesSharedErrorMapping() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.NetworkError("offline"))
        val vm = model(actions = actions)
        assertEquals("网络连接失败，请稍后重试", vm.state.value.error)
        assertTrue(vm.state.value.files.isEmpty())
    }

    @Test
    fun loadMoreAppendsPagesUntilNoMoreCursor() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(
            ApiResult.Success(FileListDto(listOf(file(1)), 2, "c1", 1, true)),
            ApiResult.Success(FileListDto(listOf(file(2)), 2, "-1", 1, false)),
        )
        val vm = model(actions = actions)
        vm.more()
        assertEquals(listOf(1L, 2L), vm.state.value.files.map { it.fileId })
        assertEquals("-1", vm.state.value.next)
        // 已到末页：再次 more 不发请求。
        vm.more()
        assertEquals(2, actions.listCalls.size)
    }

    @Test
    fun savePublishesBusyThenSuccessMessageAndForwardsTarget() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(listOf(file(1), file(2)), 2, "-1", 2, true)))
        val vm = model(actions = actions)
        vm.toggle(1); vm.toggle(2)
        vm.save(9)
        val state = vm.state.value
        assertNull(state.error)
        assertEquals("已保存至云盘", state.message)
        assertTrue(!state.busy)
        assertEquals(listOf(listOf(1L, 2L) to 9L), actions.saveCalls)
    }

    @Test
    fun saveFailureKeepsBusyFalseAndShowsError() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(listOf(file(1)), 1, "-1", 1, true)))
        actions.saveResult = "转存失败，请稍后重试"
        val vm = model(actions = actions)
        vm.toggle(1)
        vm.save(0)
        assertEquals("转存失败，请稍后重试", vm.state.value.error)
        assertNull(vm.state.value.message)
        assertTrue(!vm.state.value.busy)
    }

    @Test
    fun downloadQueuesAndBumpsRevisionOnlyWhenQueued() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(listOf(file(1)), 1, "-1", 1, true)))
        val vm = model(actions = actions)
        vm.toggle(1)
        actions.downloadResult = SharedQueueResult(queued = 2)
        vm.download()
        assertEquals(1, vm.state.value.queuedRevision)
        assertEquals("已加入 2 个下载任务", vm.state.value.message)
        // 失败（0 入队）不推进 revision。
        actions.downloadResult = SharedQueueResult(0, "分享不可用，请检查链接和提取码")
        vm.download()
        assertEquals(1, vm.state.value.queuedRevision)
        assertEquals("分享不可用，请检查链接和提取码", vm.state.value.error)
    }

    @Test
    fun selectAllTogglesWholePageAndClearSelectionResets() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(listOf(file(1), file(2)), 2, "-1", 2, true)))
        val vm = model(actions = actions)
        vm.selectAll()
        assertEquals(setOf(1L, 2L), vm.state.value.selected)
        vm.selectAll()
        assertTrue(vm.state.value.selected.isEmpty())
        vm.toggle(1)
        vm.clearSelection()
        assertTrue(vm.state.value.selected.isEmpty())
    }

    @Test
    fun busyOperationsIgnoreReentrantActions() {
        val actions = FakeSharedFilesActions()
        actions.enqueueList(ApiResult.Success(FileListDto(listOf(file(1)), 1, "-1", 1, true)))
        val vm = model(actions = actions)
        vm.toggle(1)
        // save 挂起在门闩上，模拟转存在途。
        actions.saveGate = CompletableDeferred<Unit>()
        vm.save(5)
        assertTrue(vm.state.value.busy)
        // busy 期间的目录跳转 / 选择变化 / 再次保存都应被忽略。
        vm.enter(file(9, "x", folder = true))
        vm.toggle(1)
        vm.save(6)
        assertEquals(listOf(0L to "全部文件"), vm.state.value.trail)
        assertEquals(setOf(1L), vm.state.value.selected)
        assertEquals(1, actions.saveCalls.size)
        actions.saveGate?.complete(Unit)
        assertTrue(!vm.state.value.busy)
    }
}
