package io.github.bileizhen.pan123x.feature.files

import androidx.lifecycle.ViewModelStore
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateDao
import io.github.bileizhen.pan123x.core.database.DirectoryStateEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.CopySubmitData
import io.github.bileizhen.pan123x.core.network.CopyTaskData
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.FileListDto
import io.github.bileizhen.pan123x.core.network.PanFileApi
import io.github.bileizhen.pan123x.core.network.PanFileOpsApi
import io.github.bileizhen.pan123x.core.network.TrashData
import io.github.bileizhen.pan123x.core.transfer.download.DownloadLauncher
import io.github.bileizhen.pan123x.core.transfer.download.LaunchOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.UploadEnqueueOutcome
import io.github.bileizhen.pan123x.core.share.ShareLauncher
import io.github.bileizhen.pan123x.data.share.ShareOutcome
import io.github.bileizhen.pan123x.core.transfer.upload.UploadLauncher
import io.github.bileizhen.pan123x.data.file.FileOpsRepository
import io.github.bileizhen.pan123x.data.file.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FilesViewModel 行为测试（纯 JVM、不触网、不依赖 Room）。
 *
 * 真 FileRepository / FileOpsRepository + 手写 PanFileApi / PanFileOpsApi / DAO 内存替身
 * （替身实现与 data.file.FileRepositoryTest 同款，事务编排复用 CloudFileDao 基类真实现），
 * 覆盖：缓存先显 + 空缓存自动刷新、刷新失败保留缓存并置 offlineCache、成功后清除、
 * 未登录分支、搜索 / 排序的内存重放；M3 多选状态与一次性 opsMessage（用户指示测试后置，
 * 断言从简，写操作仅冒烟）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    @Test fun selectionUsesLatestMetadataAndDropsRemovedItems() {
        val f = fixture()
        f.seed(entity(5, name = "before.bin"), entity(6, name = "keep.bin"), state = DirectoryStateEntity("acc-1", 0, 2, true, 1))
        val vm = model(f); vm.selectAll()
        f.seed(entity(5, name = "renamed.bin", size = 4096))
        assertEquals("renamed.bin", vm.uiState.value.selected[5]!!.fileName)
        assertEquals(4096L, vm.uiState.value.selected[5]!!.size)
        runBlocking { f.dao.deleteDirectory("acc-1", 0) }
        assertTrue(vm.uiState.value.selected.isEmpty())
        assertTrue(vm.uiState.value.selectMode)
        assertFalse(vm.uiState.value.allSelected)
    }

    @Test fun switchingAccountClearsSelectionQueryAndOldFiles() {
        val f = fixture(); f.seed(entity(5, name = "private.bin"), state = DirectoryStateEntity("acc-1", 0, 1, true, 1))
        val vm = model(f); vm.setSearch("private"); vm.selectAll()
        f.manager.onLogout()
        assertTrue(vm.uiState.value.selected.isEmpty()); assertFalse(vm.uiState.value.selectMode)
        assertEquals("", vm.uiState.value.search); assertTrue(vm.uiState.value.files.isEmpty())
        f.manager.onLoginSuccess("acc-2", "second", "43", "Bearer second")
        assertTrue(vm.uiState.value.selected.isEmpty()); assertTrue(vm.uiState.value.files.none { it.accountId == "acc-1" })
    }

    @Test fun explicitSelectionStartsEmptyAndRejectsStaleOrForeignRows() {
        val f = fixture(); f.seed(entity(5, name = "current.bin"), state = DirectoryStateEntity("acc-1", 0, 1, true, 1))
        val vm = model(f); vm.beginSelection()
        assertTrue(vm.uiState.value.selectMode); assertTrue(vm.uiState.value.selected.isEmpty())
        vm.toggleSelect(entity(99, name = "removed.bin"))
        vm.enterSelectMode(entity(5, name = "foreign.bin").copy(accountId = "acc-2"))
        assertTrue(vm.uiState.value.selected.isEmpty())
        vm.toggleSelect(vm.uiState.value.files.single())
        assertEquals(setOf(5L), vm.uiState.value.selected.keys)
    }

    private val owner = ViewModelStore()

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After fun cleanup() { owner.clear(); Dispatchers.resetMain() }

    private class FakePanFileApi : PanFileApi {
        private val queue = ArrayDeque<ApiResult<FileListDto>>()
        var calls = 0
            private set

        fun enqueue(result: ApiResult<FileListDto>) { queue.addLast(result) }

        override suspend fun getFileList(
            parentFileId: Long,
            page: Int,
            limit: Int,
            trashed: Boolean,
        ): ApiResult<FileListDto> {
            calls++
            // 写操作后的联动刷新在独立协程里跑，可能晚于测试方法结束才到达这里；此前抛
            // error 会成为全局未捕获异常，毒化同 JVM 里下一个用 runTest 的测试类
            // （M6 起按需返回空页：严格断言走 calls 计数或显式入队）。
            val result = queue.removeFirstOrNull() ?: ApiResult.Success(
                FileListDto(infoList = emptyList(), total = 0, next = "-1", len = 0, isFirst = false),
            )
            return result
        }
    }

    /** 写操作替身（M3）：VM 测试只冒烟 createFolder，其余端点被触即失败。 */
    private class FakePanFileOpsApi : PanFileOpsApi {
        private val createFolderResults = ArrayDeque<ApiResult<Long>>()

        fun enqueueCreateFolder(result: ApiResult<Long>) { createFolderResults.addLast(result) }

        override suspend fun createFolder(parentFileId: Long, folderName: String): ApiResult<Long> =
            createFolderResults.removeFirstOrNull() ?: error("缺少编排好的 createFolder 响应")

        override suspend fun trashFile(fileId: Long, restore: Boolean): ApiResult<TrashData> =
            error("FilesViewModelTest 不触发 trash")

        override suspend fun deleteForever(fileIds: List<Long>): ApiResult<Unit> =
            error("FilesViewModelTest 不触发 deleteForever")

        override suspend fun renameFile(fileId: Long, newFileName: String): ApiResult<Unit> =
            error("FilesViewModelTest 不触发 rename")

        override suspend fun moveFiles(fileIds: List<Long>, targetParentId: Long): ApiResult<Unit> =
            error("FilesViewModelTest 不触发 move")

        override suspend fun submitCopy(fileList: List<JsonObject>, targetFileId: Long): ApiResult<CopySubmitData> =
            error("FilesViewModelTest 不触发 copy")

        override suspend fun pollCopyTask(taskId: String): ApiResult<CopyTaskData> =
            error("FilesViewModelTest 不触发 copy 轮询")
    }

    private class InMemoryDirectoryStore {
        val files = MutableStateFlow<List<CloudFileEntity>>(emptyList())
        val states = MutableStateFlow<Map<Pair<String, Long>, DirectoryStateEntity>>(emptyMap())
    }

    private class FakeCloudFileDao(private val store: InMemoryDirectoryStore) : CloudFileDao() {
        override fun observeDirectory(accountId: String, parentFileId: Long): Flow<List<CloudFileEntity>> =
            store.files.map { list -> list.filter { it.accountId == accountId && it.parentFileId == parentFileId } }
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

    private class Fixture(
        val api: FakePanFileApi,
        val opsApi: FakePanFileOpsApi,
        val dao: FakeCloudFileDao,
        val states: FakeDirectoryStateDao,
        val manager: AccountManager,
        val accounts: MutableStateFlow<List<AccountEntity>>,
        val repository: FileRepository,
        val opsRepository: FileOpsRepository,
        val launcher: FakeDownloadLauncher,
        val uploader: FakeUploadLauncher,
        val sharer: FakeShareLauncher,
    )

    private fun fixture(loggedIn: Boolean = true): Fixture {
        val api = FakePanFileApi()
        val opsApi = FakePanFileOpsApi()
        val store = InMemoryDirectoryStore()
        val dao = FakeCloudFileDao(store)
        val states = FakeDirectoryStateDao(store)
        val manager = AccountManager()
        if (loggedIn) {
            manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        } else {
            manager.onLogout()
        }
        val accounts = MutableStateFlow(listOf(AccountEntity("acc-1", "用户甲", "42", usedBytes = 50, totalBytes = 100)))
        val repository = FileRepository(
            api = api, cloudFileDao = dao, directoryStateDao = states,
            manager = manager, relogin = { true }, logger = AppLogger(),
        )
        val opsRepository = FileOpsRepository(
            api = opsApi, cloudFileDao = dao, directoryStateDao = states,
            manager = manager, relogin = { true }, logger = AppLogger(),
        )
        return Fixture(api, opsApi, dao, states, manager, accounts, repository, opsRepository, FakeDownloadLauncher(), FakeUploadLauncher(), FakeShareLauncher())
    }

    private fun model(fixture: Fixture, dirId: Long = 0, key: String = "files-${dirId}", askLocation: Boolean = false): FilesViewModel =
        FilesViewModel(
            dirId, fixture.repository, fixture.manager, fixture.accounts, AppLogger(), fixture.opsRepository,
            downloads = fixture.launcher,
            uploads = fixture.uploader,
            shares = fixture.sharer,
            askDownloadLocation = { askLocation },
        )
            .also { owner.put(key, it) }

    /** 下载入口替身（ViewModel 只依赖 DownloadLauncher 窄接口，不碰 NSFX/SAF/Room）。 */
    private class FakeDownloadLauncher(
        var outcome: (CloudFileEntity) -> LaunchOutcome = { LaunchOutcome.Queued(it.fileName) },
    ) : DownloadLauncher {
        val launched = mutableListOf<Long>()
        val trees = mutableListOf<String>()

        override suspend fun launch(file: CloudFileEntity): LaunchOutcome {
            launched += file.fileId
            return outcome(file)
        }
        override suspend fun launch(file: CloudFileEntity, tree: String): LaunchOutcome { trees += tree; return launch(file) }
    }

    /** 上传入口替身（M5）：记录 uri 与目标目录，可脚本化成功 / 失败结果。 */
    private class FakeUploadLauncher(
        var outcome: (String) -> UploadEnqueueOutcome = { UploadEnqueueOutcome.Queued("file") },
    ) : UploadLauncher {
        val launched = mutableListOf<Pair<String, Long>>()

        override suspend fun launch(uriString: String, parentDirId: Long): UploadEnqueueOutcome {
            launched += uriString to parentDirId
            return outcome(uriString)
        }
    }

    /** 分享入口替身（M6）：记录 fileId 与密码，可脚本化成功 / 失败结果。 */
    private class FakeShareLauncher(
        var outcome: (List<Long>) -> ShareOutcome = { ids -> ShareOutcome.Created("https://www.123pan.cn/s/fake") },
    ) : ShareLauncher {
        val launched = mutableListOf<Pair<List<Long>, String>>()
        val optionsLaunched = mutableListOf<io.github.bileizhen.pan123x.core.share.ShareCreateOptions>()

        override suspend fun launch(fileIds: List<Long>, options: io.github.bileizhen.pan123x.core.share.ShareCreateOptions): ShareOutcome {
            launched += fileIds to options.password
            optionsLaunched += options
            return outcome(fileIds)
        }
    }

    private fun item(fileId: Long, name: String = "file-$fileId", folder: Boolean = false, size: Long = fileId * 10) =
        FileItemDto(
            fileId = fileId, parentFileId = 0, fileName = name, isFolder = folder, size = size,
            etag = "etag-$fileId", s3KeyFlag = "", contentType = "application/octet-stream",
            createAt = 1_700_000_000_000L + fileId, updateAt = 1_700_000_100_000L + fileId,
            hidden = false, starred = false, pinyin = "",
        )

    private fun page(vararg items: FileItemDto) =
        ApiResult.Success(FileListDto(items.toList(), total = items.size, next = "-1", len = items.size, isFirst = true))

    private fun entity(fileId: Long, parentFileId: Long = 0, name: String, folder: Boolean = false, size: Long = 0) =
        CloudFileEntity(
            accountId = "acc-1", fileId = fileId, parentFileId = parentFileId, fileName = name,
            isFolder = folder, size = size, etag = "", s3KeyFlag = "",
            createAt = 1L, updateAt = 1L,
        )

    /** 预置 Room 替身数据：DAO 挂起方法统一经 runBlocking 落库，测试主体保持同步断言。 */
    private fun Fixture.seed(vararg files: CloudFileEntity, state: DirectoryStateEntity? = null) {
        runBlocking {
            dao.upsert(files.toList())
            state?.let { states.upsert(it) }
        }
    }

    @Test fun emptyCacheAutoRefreshesOnceAndSortsFoldersFirst() {
        val f = fixture()
        f.api.enqueue(
            page(
                item(2, name = "a.txt"),
                item(1, name = "照片", folder = true),
                item(3, name = "b.txt"),
            ),
        )
        val vm = model(f)

        // 空缓存触发恰好一次自动刷新（单页），结果按"文件夹在前 + 名称升序"重排。
        assertEquals(listOf(1L, 2L, 3L), vm.uiState.value.files.map { it.fileId })
        assertEquals(1, f.api.calls)
        assertFalse(vm.uiState.value.refreshing)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.loggedOut)
    }

    @Test fun presetCacheShowsImmediatelyWithoutAnyNetworkCall() {
        val f = fixture()
        f.seed(
            entity(5, name = "cached.txt"),
            entity(6, name = "归档", folder = true),
            state = DirectoryStateEntity("acc-1", 0, total = 2, allLoaded = true, updatedAt = 1L),
        )

        val vm = model(f)

        assertEquals(listOf(6L, 5L), vm.uiState.value.files.map { it.fileId })
        assertEquals(0, f.api.calls)
        // 空间卡数据经账户流注入。
        assertEquals("用户甲", vm.uiState.value.account?.displayName)
    }

    @Test fun downloadLocationPromptUsesCapturedSelectionAndCancelQueuesNothing() {
        val f = fixture(); f.seed(entity(5, name = "a.bin"), entity(6, name = "b.bin"))
        val vm = model(f, askLocation = true)
        vm.enterSelectMode(vm.uiState.value.files.first { it.fileId == 5L })
        vm.downloadSelected(); assertTrue(vm.uiState.value.choosingDownloadLocation); assertTrue(f.launcher.launched.isEmpty())
        vm.downloadLocationChosen(null); assertFalse(vm.uiState.value.choosingDownloadLocation); assertTrue(f.launcher.launched.isEmpty()); assertTrue(vm.uiState.value.selectMode)
        vm.downloadSelected()
        vm.toggleSelect(vm.uiState.value.files.first { it.fileId == 6L })
        vm.downloadLocationChosen("content://picked/tree")
        assertEquals(listOf(5L), f.launcher.launched); assertEquals(listOf("content://picked/tree"), f.launcher.trees)
    }

    @Test fun downloadLocationResultCannotCrossAnAccountSwitch() {
        val f = fixture(); f.seed(entity(5, name = "a.bin"))
        val vm = model(f, askLocation = true)
        vm.enterSelectMode(vm.uiState.value.files.first()); vm.downloadSelected()
        f.manager.onLogout(); vm.downloadLocationChosen("content://picked/tree")
        assertTrue(f.launcher.launched.isEmpty()); assertFalse(vm.uiState.value.choosingDownloadLocation)
    }

    @Test fun maintenanceInvalidationRefreshesExistingCachedViewsWithoutClearingOnFailure() {
        val f = fixture(); f.seed(entity(5, name = "cached.bin"), state = DirectoryStateEntity("acc-1", 0, 1, true, 1L))
        val vm = model(f); f.api.enqueue(ApiResult.NetworkError("offline"))
        f.repository.notifyCacheInvalidated()
        assertEquals(1, f.api.calls); assertEquals("cached.bin", vm.uiState.value.files.single().fileName); assertTrue(vm.uiState.value.offlineCache)
    }

    @Test fun refreshFailureKeepsCacheAndMarksOfflineCache() {
        val f = fixture()
        f.seed(entity(5, name = "cached.txt"), state = DirectoryStateEntity("acc-1", 0, total = 1, allLoaded = true, updatedAt = 1L))
        f.api.enqueue(ApiResult.NetworkError("connect timeout"))
        val vm = model(f)

        vm.refresh()

        // 有缓存：列表保留 + offlineCache；错误文案用户可读；不再发第二次请求。
        assertEquals(listOf(5L), vm.uiState.value.files.map { it.fileId })
        assertTrue(vm.uiState.value.offlineCache)
        assertEquals("网络连接失败，请检查网络后重试", vm.uiState.value.error)
        assertFalse(vm.uiState.value.refreshing)
        assertEquals(1, f.api.calls)
    }

    @Test fun refreshFailureWithoutCacheSurfacesErrorState() {
        val f = fixture()
        f.api.enqueue(ApiResult.NetworkError("connect timeout"))
        val vm = model(f)

        // 空缓存自动刷新即失败：无列表可退回，进入错误空态（UI 据此显示重试）。
        assertTrue(vm.uiState.value.files.isEmpty())
        assertFalse(vm.uiState.value.offlineCache)
        assertEquals("网络连接失败，请检查网络后重试", vm.uiState.value.error)
    }

    @Test fun knownEmptyDirectoryDoesNotAutoRefresh() {
        val f = fixture()
        // allLoaded=true 且 total=0：服务端确认的空目录，进入时不再发起网络请求。
        f.seed(state = DirectoryStateEntity("acc-1", 0, total = 0, allLoaded = true, updatedAt = 1L))
        val vm = model(f)

        assertTrue(vm.uiState.value.files.isEmpty())
        assertFalse(vm.uiState.value.refreshing)
        assertNull(vm.uiState.value.error)
        assertEquals(0, f.api.calls)
    }

    @Test fun laterSuccessfulRefreshReplacesCacheAndClearsOfflineFlag() {
        val f = fixture()
        f.seed(entity(5, name = "cached.txt"), state = DirectoryStateEntity("acc-1", 0, total = 1, allLoaded = true, updatedAt = 1L))
        f.api.enqueue(ApiResult.NetworkError("connect timeout"))
        val vm = model(f)
        vm.refresh()
        assertTrue(vm.uiState.value.offlineCache)

        f.api.enqueue(page(item(7, name = "new.txt")))
        vm.refresh()

        assertEquals(listOf(7L), vm.uiState.value.files.map { it.fileId })
        assertFalse(vm.uiState.value.offlineCache)
        assertNull(vm.uiState.value.error)
    }

    @Test fun loggedOutShowsNoFilesAndRefreshIsSkipped() {
        val f = fixture(loggedIn = false)
        val vm = model(f)

        assertTrue(vm.uiState.value.loggedOut)
        assertTrue(vm.uiState.value.files.isEmpty())
        assertFalse(vm.uiState.value.restoring)

        vm.refresh()

        assertFalse(vm.uiState.value.refreshing)
        assertEquals(0, f.api.calls)
    }

    @Test fun searchAndSortReplayAgainstLatestSnapshot() {
        val f = fixture()
        f.seed(
            entity(10, name = "Alpha.TXT", size = 300),
            entity(11, name = "beta.jpg", size = 100),
            entity(12, name = "相册", folder = true),
            entity(13, parentFileId = 7, name = "alpha.txt"),
        )
        val vm = model(f)

        // 搜索：trim + 小写包含，且只作用于当前目录快照（13 在别的目录）。
        vm.setSearch("  ALPHA ")
        assertEquals(listOf(10L), vm.uiState.value.files.map { it.fileId })

        vm.setSearch("")
        assertEquals(listOf(12L, 10L, 11L), vm.uiState.value.files.map { it.fileId })

        vm.setSort(FileSortField.SIZE)
        assertEquals(listOf(12L, 11L, 10L), vm.uiState.value.files.map { it.fileId })

        vm.setAscending(false)
        assertEquals(listOf(12L, 10L, 11L), vm.uiState.value.files.map { it.fileId })
    }

    @Test fun separateDirectoriesUseIndependentViewStates() {
        val f = fixture()
        f.seed(entity(5, name = "root.txt"), entity(6, parentFileId = 9, name = "inner.txt"))
        val root = model(f, dirId = 0, key = "root")
        val inner = model(f, dirId = 9, key = "inner")

        root.setSearch("inner")
        assertEquals(emptyList<Long>(), root.uiState.value.files.map { it.fileId })
        assertEquals(listOf(6L), inner.uiState.value.files.map { it.fileId })
        assertEquals("inner", root.uiState.value.search)
        assertEquals("", inner.uiState.value.search)
    }

    /** M3 多选状态（用户指示测试后置：断言从简，只覆盖状态流转）。 */
    @Test fun selectionTransitionsFollowContract() {
        val f = fixture()
        f.seed(entity(5, name = "a.txt"), entity(6, name = "b.txt"), entity(7, name = "c.txt"))
        val vm = model(f)
        val files = vm.uiState.value.files

        // 长按进入多选并选中首个；多选中点击切换；未在多选模式时 toggle 不生效。
        vm.enterSelectMode(files[0])
        assertTrue(vm.uiState.value.selectMode)
        assertEquals(listOf(5L), vm.uiState.value.selected.keys.toList())

        vm.toggleSelect(files[1])
        assertEquals(setOf(5L, 6L), vm.uiState.value.selected.keys)
        vm.toggleSelect(files[0])
        assertEquals(listOf(6L), vm.uiState.value.selected.keys.toList())

        // 全选 → 已全选时"反选"清空 → 关闭退出多选；退出后 toggle 不再生效。
        vm.selectAll()
        assertTrue(vm.uiState.value.allSelected)
        assertEquals(setOf(5L, 6L, 7L), vm.uiState.value.selected.keys)
        vm.invertSelection()
        assertTrue(vm.uiState.value.selected.isEmpty())
        vm.clearSelection()
        assertFalse(vm.uiState.value.selectMode)
        vm.toggleSelect(files[0])
        assertTrue(vm.uiState.value.selected.isEmpty())
    }

    @Test fun contextActionTargetsCurrentCachedFileWithoutBatchUi() {
        val f = fixture()
        f.seed(entity(5, name = "current.txt"), entity(6, name = "another.txt"))
        val vm = model(f)
        vm.selectAll()
        assertTrue(vm.prepareSingleFileAction(entity(5, name = "stale.txt")))
        assertFalse(vm.uiState.value.selectMode)
        assertEquals(setOf(5L), vm.uiState.value.selected.keys)
        assertEquals("current.txt", vm.uiState.value.selected[5L]?.fileName)
    }

    @Test fun contextActionRejectsMissingOrOtherAccountFile() {
        val f = fixture()
        f.seed(entity(5, name = "current.txt"))
        val vm = model(f)
        assertFalse(vm.prepareSingleFileAction(entity(5, name = "other.txt").copy(accountId = "other-account")))
        assertFalse(vm.prepareSingleFileAction(entity(404, name = "missing.txt")))
        assertTrue(vm.uiState.value.selected.isEmpty())
        assertFalse(vm.uiState.value.selectMode)
    }

    /** M3 写操作冒烟：成功 → 一次性"操作成功"提示 + busy 复位；消费后清空。 */
    @Test fun createFolderSurfacesOneShotMessageAndClearsBusy() {
        val f = fixture()
        f.seed(state = DirectoryStateEntity("acc-1", 0, total = 0, allLoaded = true, updatedAt = 1L))
        f.opsApi.enqueueCreateFolder(ApiResult.Success(99L))
        val vm = model(f)

        vm.createFolder("  新文件夹  ")

        assertEquals("操作成功", vm.uiState.value.opsMessage)
        assertFalse(vm.uiState.value.opsBusy)
        vm.consumeOpsMessage()
        assertNull(vm.uiState.value.opsMessage)
    }

    /** M3 写操作冒烟：失败 → opsMessage 为仓库返回的用户可读文案。 */
    @Test fun createFolderFailureSurfacesUserMessage() {
        val f = fixture()
        f.seed(state = DirectoryStateEntity("acc-1", 0, total = 0, allLoaded = true, updatedAt = 1L))
        f.opsApi.enqueueCreateFolder(ApiResult.ApiError(5066, "文件不存在"))
        val vm = model(f)

        vm.createFolder("新文件夹")

        assertTrue(vm.uiState.value.opsMessage.orEmpty().isNotBlank())
        assertFalse(vm.uiState.value.opsBusy)
    }

    /** M4 下载入口：全部成功 → 逐项发起、退出多选、一次性提示；launcher 收到的是选中项。 */
    @Test fun shareKeepsOptionsAndCopiesPasswordWithResult() {
        val f = fixture()
        f.seed(entity(5, name = "a.bin"))
        val vm = model(f)
        vm.enterSelectMode(vm.uiState.value.files.first())
        val options = io.github.bileizhen.pan123x.core.share.ShareCreateOptions("测试主题", "Ab12", 7)
        vm.shareSelected(options)
        assertEquals(listOf(options), f.sharer.optionsLaunched)
        assertEquals("https://www.123pan.cn/s/fake", vm.uiState.value.shareLink)
        assertEquals("Ab12", vm.uiState.value.sharePassword)
        vm.consumeShareLink()
        assertNull(vm.uiState.value.shareLink)
        assertEquals("", vm.uiState.value.sharePassword)
    }

    @Test fun invalidSharePasswordDoesNotLaunchAndRetainsSelection() {
        val f = fixture()
        f.seed(entity(5, name = "a.bin"))
        val vm = model(f)
        vm.enterSelectMode(vm.uiState.value.files.first())
        vm.shareSelected("!bad")
        assertTrue(f.sharer.launched.isEmpty())
        assertFalse(vm.uiState.value.opsBusy)
        assertEquals(setOf(5L), vm.uiState.value.selected.keys)
        assertTrue(vm.uiState.value.opsMessage.orEmpty().contains("提取码"))
    }

    @Test fun downloadSelectedLaunchesEverySelectionAndExitsSelectMode() {
        val f = fixture()
        f.seed(entity(5, name = "a.bin"), entity(6, name = "b.bin"), entity(7, name = "c", folder = true))
        val vm = model(f)
        vm.enterSelectMode(vm.uiState.value.files.first { it.fileId == 5L })
        vm.toggleSelect(vm.uiState.value.files.first { it.fileId == 7L })

        vm.downloadSelected()

        assertEquals(listOf(5L, 7L), f.launcher.launched)
        assertEquals("已加入下载队列：2 个文件", vm.uiState.value.opsMessage)
        assertFalse(vm.uiState.value.selectMode)
        assertTrue(vm.uiState.value.selected.isEmpty())
        assertFalse(vm.uiState.value.opsBusy)
    }

    /** M4 下载入口：部分失败 → 聚合文案 + 保留选择（与 M3 写操作同一语义）。 */
    @Test fun downloadSelectedAggregatesPartialFailure() {
        val f = fixture()
        f.seed(entity(5, name = "a.bin"), entity(6, name = "b.bin"))
        f.launcher.outcome = { file ->
            if (file.fileId == 6L) LaunchOutcome.Failed("加入下载队列失败，请稍后重试")
            else LaunchOutcome.Queued(file.fileName)
        }
        val vm = model(f)
        vm.enterSelectMode(vm.uiState.value.files.first { it.fileId == 5L })
        vm.toggleSelect(vm.uiState.value.files.first { it.fileId == 6L })

        vm.downloadSelected()

        assertEquals(listOf(5L, 6L), f.launcher.launched)
        assertEquals("已加入下载队列 1 个，失败 1 个：加入下载队列失败，请稍后重试", vm.uiState.value.opsMessage)
        assertTrue(vm.uiState.value.selectMode)
        assertEquals(setOf(5L, 6L), vm.uiState.value.selected.keys)
    }

    @Test fun downloadNavigationEmitsOnceForAcceptedBatchAndRetainsPartialFailure() = runBlocking {
        val f = fixture()
        f.seed(entity(5, name = "a.bin"), entity(6, name = "b.bin"))
        val vm = model(f)
        val events = mutableListOf<String?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { vm.downloadQueuedEvents.collect { events += it } }
        for (failedIds in listOf(emptySet(), setOf(6L), setOf(5L, 6L))) {
            f.launcher.outcome = { if (it.fileId in failedIds) LaunchOutcome.Failed("加入失败") else LaunchOutcome.Queued(it.fileName) }
            vm.enterSelectMode(vm.uiState.value.files.first { it.fileId == 5L })
            vm.toggleSelect(vm.uiState.value.files.first { it.fileId == 6L })
            vm.downloadSelected()
            yield()
        }
        assertEquals(2, events.size)
        assertNull(events[0])
        assertEquals("已加入下载队列 1 个，失败 1 个：加入失败", events[1])
        assertEquals("加入失败", vm.uiState.value.opsMessage)
        collector.cancelAndJoin()
    }
}
