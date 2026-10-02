package io.github.bileizhen.pan123x.data.file

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateDao
import io.github.bileizhen.pan123x.core.database.DirectoryStateEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.CopySubmitData
import io.github.bileizhen.pan123x.core.network.CopyTaskData
import io.github.bileizhen.pan123x.core.network.PanFileOpsApi
import io.github.bileizhen.pan123x.core.network.TrashData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileOpsRepository 行为测试（纯 JVM、不触网、不依赖 Room）。
 *
 * 替身模式对齐 FileRepositoryTest：手写 PanFileOpsApi / 两个 DAO 的内存替身 + 真
 * AccountManager / AppLogger；CloudFileDao 事务编排复用基类真实现。请求 body 的
 * 字节级断言在 PanFileOpsApiTest（MockWebServer），本文件断言仓库层编排：
 * 同名预查、批量聚合、code==2 重登重试、复制轮询状态机（虚拟时间）、移动过滤与缓存失效。
 */
class FileOpsRepositoryTest {

    private class FakePanFileOpsApi : PanFileOpsApi {
        data class CreateFolderCall(val parentFileId: Long, val folderName: String)
        data class TrashCall(val fileId: Long, val restore: Boolean)
        data class DeleteForeverCall(val fileIds: List<Long>)
        data class RenameCall(val fileId: Long, val newFileName: String)
        data class MoveCall(val fileIds: List<Long>, val targetParentId: Long)
        data class SubmitCopyCall(val fileList: List<JsonObject>, val targetFileId: Long)

        val createFolderCalls = mutableListOf<CreateFolderCall>()
        val trashCalls = mutableListOf<TrashCall>()
        val deleteForeverCalls = mutableListOf<DeleteForeverCall>()
        val renameCalls = mutableListOf<RenameCall>()
        val moveCalls = mutableListOf<MoveCall>()
        val submitCopyCalls = mutableListOf<SubmitCopyCall>()
        val pollTaskIds = mutableListOf<String>()

        val totalApiCalls: Int
            get() = createFolderCalls.size + trashCalls.size + deleteForeverCalls.size +
                renameCalls.size + moveCalls.size + submitCopyCalls.size + pollTaskIds.size

        private val createFolderResults = ArrayDeque<ApiResult<Long>>()
        private val trashResults = ArrayDeque<ApiResult<TrashData>>()
        private val deleteForeverResults = ArrayDeque<ApiResult<Unit>>()
        private val renameResults = ArrayDeque<ApiResult<Unit>>()
        private val moveResults = ArrayDeque<ApiResult<Unit>>()
        private val submitCopyResults = ArrayDeque<ApiResult<CopySubmitData>>()
        private val pollResults = ArrayDeque<ApiResult<CopyTaskData>>()

        /** 队列耗尽后的轮询兜底（模拟"一直进行中"），默认 status=4。 */
        var defaultPoll: ApiResult<CopyTaskData> = ApiResult.Success(CopyTaskData(status = 4, failMsg = null))

        fun enqueueCreateFolder(result: ApiResult<Long>) = createFolderResults.addLast(result)
        fun enqueueTrash(result: ApiResult<TrashData>) = trashResults.addLast(result)
        fun enqueueDeleteForever(result: ApiResult<Unit>) = deleteForeverResults.addLast(result)
        fun enqueueRename(result: ApiResult<Unit>) = renameResults.addLast(result)
        fun enqueueMove(result: ApiResult<Unit>) = moveResults.addLast(result)
        fun enqueueSubmitCopy(result: ApiResult<CopySubmitData>) = submitCopyResults.addLast(result)
        fun enqueuePoll(result: ApiResult<CopyTaskData>) = pollResults.addLast(result)

        override suspend fun createFolder(parentFileId: Long, folderName: String): ApiResult<Long> {
            createFolderCalls += CreateFolderCall(parentFileId, folderName)
            return createFolderResults.removeFirstOrNull() ?: error("缺少第 ${createFolderCalls.size} 个 createFolder 响应")
        }

        override suspend fun trashFile(fileId: Long, restore: Boolean): ApiResult<TrashData> {
            trashCalls += TrashCall(fileId, restore)
            return trashResults.removeFirstOrNull() ?: error("缺少第 ${trashCalls.size} 个 trashFile 响应")
        }

        override suspend fun deleteForever(fileIds: List<Long>): ApiResult<Unit> {
            deleteForeverCalls += DeleteForeverCall(fileIds)
            return deleteForeverResults.removeFirstOrNull() ?: error("缺少第 ${deleteForeverCalls.size} 个 deleteForever 响应")
        }

        override suspend fun renameFile(fileId: Long, newFileName: String): ApiResult<Unit> {
            renameCalls += RenameCall(fileId, newFileName)
            return renameResults.removeFirstOrNull() ?: error("缺少第 ${renameCalls.size} 个 renameFile 响应")
        }

        override suspend fun moveFiles(fileIds: List<Long>, targetParentId: Long): ApiResult<Unit> {
            moveCalls += MoveCall(fileIds, targetParentId)
            return moveResults.removeFirstOrNull() ?: error("缺少第 ${moveCalls.size} 个 moveFiles 响应")
        }

        override suspend fun submitCopy(fileList: List<JsonObject>, targetFileId: Long): ApiResult<CopySubmitData> {
            submitCopyCalls += SubmitCopyCall(fileList, targetFileId)
            return submitCopyResults.removeFirstOrNull() ?: error("缺少第 ${submitCopyCalls.size} 个 submitCopy 响应")
        }

        override suspend fun pollCopyTask(taskId: String): ApiResult<CopyTaskData> {
            pollTaskIds += taskId
            return pollResults.removeFirstOrNull() ?: defaultPoll
        }
    }

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
        val api: FakePanFileOpsApi,
        val files: FakeCloudFileDao,
        val states: FakeDirectoryStateDao,
        val relogin: ReloginStub,
        val refreshes: MutableList<Long>,
        val repository: FileOpsRepository,
    )

    private fun fixture(loggedIn: Boolean = true): Fixture {
        val api = FakePanFileOpsApi()
        val store = InMemoryDirectoryStore()
        val files = FakeCloudFileDao(store)
        val states = FakeDirectoryStateDao(store)
        val manager = AccountManager()
        if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        val relogin = ReloginStub()
        val refreshes = mutableListOf<Long>()
        val repository = FileOpsRepository(
            api = api,
            cloudFileDao = files,
            directoryStateDao = states,
            manager = manager,
            relogin = relogin.invoke,
            logger = AppLogger(),
        ).apply { refresher = { dirId -> refreshes += dirId } }
        return Fixture(api, files, states, relogin, refreshes, repository)
    }

    private companion object {
        fun entity(
            fileId: Long,
            parentFileId: Long = 0L,
            name: String = "file-$fileId",
            folder: Boolean = false,
        ) = CloudFileEntity(
            accountId = "acc-1",
            fileId = fileId,
            parentFileId = parentFileId,
            fileName = name,
            isFolder = folder,
            size = fileId * 10,
            etag = "etag-$fileId",
            s3KeyFlag = "flag-$fileId",
            createAt = 1_700_000_000_000L + fileId,
            updateAt = 1_700_000_100_000L + fileId,
        )

        fun trashOk(vararg ids: Long) = ApiResult.Success(TrashData(infoList = ids.toList(), abnormalFileIds = emptyList()))

        /** 复制完整对象的 PascalCase 键序（13 字段 + DriveId，与仓库构造顺序一致）。 */
        val COPY_KEYS = listOf(
            "FileId", "ParentFileId", "FileName", "Type", "Size", "Etag", "S3KeyFlag",
            "ContentType", "CreateAt", "UpdateAt", "Hidden", "StarredStatus", "PinYin", "DriveId",
        )
    }

    // ---- createFolder ----

    @Test
    fun createFolderSameNameHitsCacheWithoutApiCall() = runTest {
        val f = fixture()
        f.files.upsert(listOf(entity(fileId = 5, name = "docs", folder = true)))

        val outcome = f.repository.createFolder(parentId = 0, name = " docs ")

        assertEquals(OpsOutcome.Success("已存在同名文件夹"), outcome)
        assertTrue(f.api.createFolderCalls.isEmpty())
        assertTrue(f.refreshes.isEmpty())
        assertNull(f.states.get("acc-1", 0))
    }

    @Test
    fun createFolderSuccessCallsApiAndInvalidatesParent() = runTest {
        val f = fixture()
        f.api.enqueueCreateFolder(ApiResult.Success(501L))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 1, allLoaded = true, updatedAt = 9L))

        val outcome = f.repository.createFolder(parentId = 0, name = "新建文件夹")

        assertEquals(OpsOutcome.Success(null), outcome)
        assertEquals(listOf(FakePanFileOpsApi.CreateFolderCall(0, "新建文件夹")), f.api.createFolderCalls)
        assertNull(f.states.get("acc-1", 0))
        assertEquals(listOf(0L), f.refreshes)
    }

    @Test
    fun createFolderEmptyNameFailsWithoutApiCall() = runTest {
        val f = fixture()
        assertEquals(OpsOutcome.Failure("名称不能为空"), f.repository.createFolder(0, "   "))
        assertTrue(f.api.totalApiCalls == 0)
    }

    // ---- rename ----

    @Test
    fun renameSuccessUpdatesRowAndInvalidatesParent() = runTest {
        val f = fixture()
        val original = entity(fileId = 5, name = "old.txt")
        f.files.upsert(listOf(original))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 1, allLoaded = true, updatedAt = 9L))
        f.api.enqueueRename(ApiResult.Success(Unit))

        val outcome = f.repository.rename(original, "new.txt")

        assertEquals(OpsOutcome.Success(null), outcome)
        assertEquals(listOf(FakePanFileOpsApi.RenameCall(5, "new.txt")), f.api.renameCalls)
        assertEquals(listOf("new.txt"), f.files.current.map { it.fileName })
        assertNull(f.states.get("acc-1", 0))
        assertEquals(listOf(0L), f.refreshes)
    }

    @Test
    fun renameFailureKeepsCacheUntouched() = runTest {
        val f = fixture()
        val original = entity(fileId = 5, name = "old.txt")
        f.files.upsert(listOf(original))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 1, allLoaded = true, updatedAt = 9L))
        f.api.enqueueRename(ApiResult.ApiError(code = 403, message = "无权限操作"))

        assertEquals(OpsOutcome.Failure("无权限操作"), f.repository.rename(original, "new.txt"))
        assertEquals(listOf("old.txt"), f.files.current.map { it.fileName })
        assertEquals(1, f.states.get("acc-1", 0)?.total)
        assertTrue(f.refreshes.isEmpty())
    }

    // ---- trash / restore ----

    @Test
    fun trashAllSuccessRemovesRowsFromParentCacheAndInvalidates() = runTest {
        val f = fixture()
        val targets = listOf(entity(1), entity(2))
        f.files.upsert(targets + entity(3))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 3, allLoaded = true, updatedAt = 9L))
        f.api.enqueueTrash(trashOk(1))
        f.api.enqueueTrash(trashOk(2))

        val outcome = f.repository.trash(targets)

        assertEquals(OpsOutcome.Success(null), outcome)
        assertEquals(
            listOf(FakePanFileOpsApi.TrashCall(1, restore = false), FakePanFileOpsApi.TrashCall(2, restore = false)),
            f.api.trashCalls,
        )
        // 已删除项立即从父目录缓存消失，剩余行原样保留
        assertEquals(listOf(3L), f.files.current.map { it.fileId })
        assertNull(f.states.get("acc-1", 0))
        assertEquals(listOf(0L), f.refreshes)
    }

    @Test
    fun trashPartialFailureAggregatesCountsAndFirstReason() = runTest {
        val f = fixture()
        val targets = listOf(entity(1), entity(2), entity(3))
        f.files.upsert(targets + entity(4))
        f.api.enqueueTrash(trashOk(1))
        f.api.enqueueTrash(ApiResult.ApiError(code = 5066, message = "文件不存在"))
        f.api.enqueueTrash(ApiResult.ApiError(code = 403, message = "无权限操作"))

        val outcome = f.repository.trash(targets)

        assertEquals(OpsOutcome.Failure("成功 1 个，失败 2 个：文件不存在"), outcome)
        // 已成功项仍要从缓存移除（不允许 UI 假成功），失败项与未选择项保留
        assertEquals(listOf(2L, 3L, 4L), f.files.current.map { it.fileId }.sorted())
        assertEquals(listOf(0L), f.refreshes)
    }

    @Test
    fun trashAllFailKeepsCacheAndSkipsRefresh() = runTest {
        val f = fixture()
        val targets = listOf(entity(1), entity(2))
        f.files.upsert(targets)
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 2, allLoaded = true, updatedAt = 9L))
        f.api.enqueueTrash(ApiResult.NetworkError("offline"))
        f.api.enqueueTrash(ApiResult.ApiError(code = 403, message = "无权限操作"))

        assertEquals(
            OpsOutcome.Failure("网络连接失败，请检查网络后重试"),
            f.repository.trash(targets),
        )
        assertEquals(2, f.files.current.size)
        assertEquals(2, f.states.get("acc-1", 0)?.total)
        assertTrue(f.refreshes.isEmpty())
    }

    @Test
    fun restorePassesRestoreTruePerItem() = runTest {
        val f = fixture()
        f.api.enqueueTrash(trashOk(8))
        f.api.enqueueTrash(trashOk(9))

        val outcome = f.repository.restore(listOf(entity(8, parentFileId = 7), entity(9, parentFileId = 7)))

        assertEquals(OpsOutcome.Success(null), outcome)
        assertEquals(
            listOf(FakePanFileOpsApi.TrashCall(8, restore = true), FakePanFileOpsApi.TrashCall(9, restore = true)),
            f.api.trashCalls,
        )
        // 恢复不改普通目录缓存，只失效来源目录
        assertEquals(listOf(7L), f.refreshes)
    }

    @Test
    fun trashEmptySelectionFailsWithoutApiCall() = runTest {
        val f = fixture()
        assertEquals(OpsOutcome.Failure("未选择任何文件"), f.repository.trash(emptyList()))
        assertEquals(0, f.api.totalApiCalls)
    }

    // ---- 会话过期重登 ----

    @Test
    fun sessionExpiredReloginSucceedsRetriesSameCallOnce() = runTest {
        val f = fixture()
        f.api.enqueueTrash(ApiResult.SessionExpired)
        f.api.enqueueTrash(trashOk(1))

        val outcome = f.repository.trash(listOf(entity(1)))

        assertEquals(OpsOutcome.Success(null), outcome)
        assertEquals(2, f.api.trashCalls.size)
        assertEquals(1, f.relogin.calls)
    }

    @Test
    fun reloginFailureFailsWithoutRetryingApiCall() = runTest {
        val f = fixture()
        f.relogin.result = false
        f.api.enqueueTrash(ApiResult.SessionExpired)

        assertEquals(
            OpsOutcome.Failure("登录状态已失效，请重新登录"),
            f.repository.trash(listOf(entity(1))),
        )
        assertEquals(1, f.api.trashCalls.size)
        assertEquals(1, f.relogin.calls)
    }

    // ---- deleteForever ----

    @Test
    fun deleteForeverSubmitsWholeListOnceAndInvalidatesParents() = runTest {
        val f = fixture()
        f.api.enqueueDeleteForever(ApiResult.Success(Unit))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 2, allLoaded = true, updatedAt = 9L))

        val outcome = f.repository.deleteForever(listOf(entity(1), entity(2)))

        assertEquals(OpsOutcome.Success(null), outcome)
        assertEquals(listOf(FakePanFileOpsApi.DeleteForeverCall(listOf(1, 2))), f.api.deleteForeverCalls)
        assertNull(f.states.get("acc-1", 0))
        assertEquals(listOf(0L), f.refreshes)
    }

    // ---- move ----

    @Test
    fun moveFiltersItemsAlreadyInTargetAndFolderIntoItself() = runTest {
        val f = fixture()
        val inTarget = listOf(entity(10, parentFileId = 5), entity(11, parentFileId = 5))
        val folderItself = entity(5, name = "target-folder", folder = true)
        f.api.enqueueMove(ApiResult.Success(Unit))

        val outcome = f.repository.move(inTarget + folderItself, targetDirId = 5)

        // 全部被过滤：已在目标目录的 10/11 + 目录自身（fileId==targetDirId）
        assertEquals(OpsOutcome.Failure("所选文件已在该文件夹中"), outcome)
        assertTrue(f.api.moveCalls.isEmpty())
        assertTrue(f.refreshes.isEmpty())
    }

    @Test
    fun moveSubmitsRemainingItemsAndInvalidatesSourceAndTarget() = runTest {
        val f = fixture()
        f.files.upsert(listOf(entity(10, parentFileId = 5), entity(9)))
        f.states.upsert(DirectoryStateEntity("acc-1", 0, total = 1, allLoaded = true, updatedAt = 9L))
        f.states.upsert(DirectoryStateEntity("acc-1", 5, total = 2, allLoaded = true, updatedAt = 9L))
        f.api.enqueueMove(ApiResult.Success(Unit))

        val outcome = f.repository.move(listOf(entity(10, parentFileId = 5), entity(9)), targetDirId = 5)

        assertEquals(OpsOutcome.Success(null), outcome)
        // 已在目标目录的 fileId 10 被过滤，只提交 9
        assertEquals(listOf(FakePanFileOpsApi.MoveCall(fileIds = listOf(9), targetParentId = 5)), f.api.moveCalls)
        // 源目录与目标目录状态行均被删（修正参考实现只标目标的疏漏）
        assertNull(f.states.get("acc-1", 0))
        assertNull(f.states.get("acc-1", 5))
        assertEquals(listOf(0L, 5L), f.refreshes)
    }

    // ---- copy ----

    @Test
    fun copyBuildsFullObjectFileListFromCache() = runTest {
        val f = fixture()
        val source = entity(fileId = 7, name = "a.txt")
        f.files.upsert(listOf(source))
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 2, failMsg = null)))

        val outcome = f.repository.copy(listOf(source), targetDirId = 5)

        assertEquals(OpsOutcome.Success(null), outcome)
        val submit = f.api.submitCopyCalls.single()
        assertEquals(5L, submit.targetFileId)
        val item = submit.fileList.single()
        // 13 个 PascalCase 字段 + DriveId，顺序与值都来自缓存行
        assertEquals(COPY_KEYS, item.keys.toList())
        assertEquals(7L, item["FileId"]!!.jsonPrimitive.long)
        assertEquals(0L, item["ParentFileId"]!!.jsonPrimitive.long)
        assertEquals("a.txt", item["FileName"]!!.jsonPrimitive.content)
        assertEquals(0L, item["Type"]!!.jsonPrimitive.long) // 非文件夹
        assertEquals(70L, item["Size"]!!.jsonPrimitive.long)
        assertEquals("etag-7", item["Etag"]!!.jsonPrimitive.content)
        assertEquals("flag-7", item["S3KeyFlag"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000_007L, item["CreateAt"]!!.jsonPrimitive.long)
        assertEquals(1_700_000_100_007L, item["UpdateAt"]!!.jsonPrimitive.long)
        assertEquals(0L, item["DriveId"]!!.jsonPrimitive.long)
        // 复制只失效目标目录，不动源目录
        assertEquals(listOf(5L), f.refreshes)
    }

    @Test
    fun copyFolderEntryHasTypeOne() = runTest {
        val f = fixture()
        val folder = entity(fileId = 6, name = "docs", folder = true)
        f.files.upsert(listOf(folder))
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 2, failMsg = null)))

        f.repository.copy(listOf(folder), targetDirId = 5)

        val item = f.api.submitCopyCalls.single().fileList.single()
        assertEquals(1L, item["Type"]!!.jsonPrimitive.long)
    }

    @Test
    fun copyFallsBackToFileIdOnlyWhenCacheMissing() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 2, failMsg = null)))

        f.repository.copy(listOf(entity(99)), targetDirId = 5)

        val item = f.api.submitCopyCalls.single().fileList.single()
        assertEquals(setOf("FileId"), item.keys)
        assertEquals(99L, item["FileId"]!!.jsonPrimitive.long)
    }

    @Test
    fun copyPollsUntilSuccessStatus() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 4, failMsg = null)))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 1, failMsg = null)))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 2, failMsg = null)))

        val outcome = f.repository.copy(listOf(entity(1)), targetDirId = 5)

        assertEquals(OpsOutcome.Success(null), outcome)
        // 4 → 1 → 2 共轮询 3 次，taskId 来自提交响应
        assertEquals(listOf("t-1", "t-1", "t-1"), f.api.pollTaskIds)
        assertEquals(2_000L, currentTime) // 前两次轮询后各等待 1s
        assertEquals(listOf(5L), f.refreshes)
    }

    @Test
    fun copyPollStatusThreeFailsWithFailMsg() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 3, failMsg = "目标空间不足")))

        assertEquals(
            OpsOutcome.Failure("目标空间不足"),
            f.repository.copy(listOf(entity(1)), targetDirId = 5),
        )
        assertTrue(f.refreshes.isEmpty())
    }

    @Test
    fun copyPollStatusThreeWithBlankFailMsgUsesDefaultText() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = 3, failMsg = " ")))

        assertEquals(
            OpsOutcome.Failure("复制任务失败"),
            f.repository.copy(listOf(entity(1)), targetDirId = 5),
        )
    }

    @Test
    fun copyPollMissingStatusTreatedAsSuccessDefensively() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))
        f.api.enqueuePoll(ApiResult.Success(CopyTaskData(status = null, failMsg = null)))

        assertEquals(OpsOutcome.Success(null), f.repository.copy(listOf(entity(1)), targetDirId = 5))
        assertEquals(1, f.api.pollTaskIds.size)
    }

    @Test
    fun copyPollTimesOutAfterSixtyAttempts() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData("t-1")))

        val outcome = f.repository.copy(listOf(entity(1)), targetDirId = 5)

        assertEquals(OpsOutcome.Failure("复制超时，请稍后刷新查看结果"), outcome)
        assertEquals(60, f.api.pollTaskIds.size)
        // 60 次轮询、每次间隔 1s：虚拟时间至少走到 59s
        assertTrue(currentTime >= 59_000L)
        assertTrue(f.refreshes.isEmpty())
    }

    @Test
    fun copySubmitWithoutTaskIdFailsBeforePolling() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.Success(CopySubmitData(taskId = null)))

        assertEquals(
            OpsOutcome.Failure("响应中未找到任务 ID"),
            f.repository.copy(listOf(entity(1)), targetDirId = 5),
        )
        assertTrue(f.api.pollTaskIds.isEmpty())
    }

    @Test
    fun copySubmitApiFailureFailsWithoutPolling() = runTest {
        val f = fixture()
        f.api.enqueueSubmitCopy(ApiResult.ApiError(code = 403, message = "无权限操作"))

        assertEquals(
            OpsOutcome.Failure("无权限操作"),
            f.repository.copy(listOf(entity(1)), targetDirId = 5),
        )
        assertTrue(f.api.pollTaskIds.isEmpty())
    }

    // ---- 未登录 / 会话守卫 ----

    @Test
    fun notLoggedInShortCircuitsEveryOperation() = runTest {
        val f = fixture(loggedIn = false)
        val file = entity(1)

        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.createFolder(0, "a"))
        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.rename(file, "b"))
        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.trash(listOf(file)))
        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.restore(listOf(file)))
        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.deleteForever(listOf(file)))
        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.move(listOf(file), 5))
        assertEquals(OpsOutcome.Failure("请先登录"), f.repository.copy(listOf(file), 5))
        assertEquals(0, f.api.totalApiCalls)
    }
}
