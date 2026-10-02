package io.github.bileizhen.pan123x.data.transfer

import io.github.bileizhen.pan123x.core.account.AccountManager
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
import io.github.bileizhen.pan123x.core.network.PanUploadApi
import io.github.bileizhen.pan123x.core.network.TrashData
import io.github.bileizhen.pan123x.core.network.UploadRequestDto
import io.github.bileizhen.pan123x.core.transfer.rapid.RapidFile
import io.github.bileizhen.pan123x.data.file.FileOpsRepository
import io.github.bileizhen.pan123x.data.file.FileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RapidRepository 行为测试（，：纯 JVM、不触网）。
 *
 * 替身策略：PanUploadApi / PanFileApi / PanFileOpsApi / 两个 DAO 全部手写内存替身，
 * FileRepository 与 FileOpsRepository 用真实现按生产方式接线（refresher →
 * FileRepository::refreshDirectory），断言落在替身调用记录上：
 * - 目录命中不再 create（ops.createFolder 调用次数）；
 * - 非 Reuse 失败文案"网盘中不存在相同文件，无法秒传"（绝不本地读取 / 真传）；
 * - cancel 中途停止；progress 推进；成功后 refreshDirectory 恰一次。
 */
class RapidRepositoryTest {

    private val etag = "abcdef0123456789abcdef0123456789"

    private data class ApiCall(val parentFileId: Long, val page: Int, val limit: Int, val trashed: Boolean)
    private data class UploadCall(
        val fileName: String,
        val size: Long,
        val etag: String,
        val parentFileId: Long,
        val duplicate: Int,
    )

    private class FakePanFileApi : PanFileApi {
        val calls = mutableListOf<ApiCall>()
        private val queue = ArrayDeque<ApiResult<FileListDto>>()

        fun enqueue(result: ApiResult<FileListDto>) {
            queue.addLast(result)
        }

        override suspend fun getFileList(
            parentFileId: Long,
            page: Int,
            limit: Int,
            trashed: Boolean,
        ): ApiResult<FileListDto> {
            calls.addLast(ApiCall(parentFileId, page, limit, trashed))
            return queue.removeFirstOrNull() ?: error("缺少第 ${calls.size} 次列表响应")
        }
    }

    private class FakePanFileOpsApi : PanFileOpsApi {
        val createFolderCalls = mutableListOf<Pair<Long, String>>()
        private val createQueue = ArrayDeque<ApiResult<Long>>()

        fun enqueueCreate(result: ApiResult<Long>) {
            createQueue.addLast(result)
        }

        override suspend fun createFolder(parentFileId: Long, folderName: String): ApiResult<Long> {
            createFolderCalls.addLast(parentFileId to folderName)
            return createQueue.removeFirstOrNull() ?: error("缺少第 ${createFolderCalls.size} 次建目录响应")
        }

        override suspend fun trashFile(fileId: Long, restore: Boolean): ApiResult<TrashData> = error("unused")

        override suspend fun deleteForever(fileIds: List<Long>): ApiResult<Unit> = error("unused")

        override suspend fun renameFile(fileId: Long, newFileName: String): ApiResult<Unit> = error("unused")

        override suspend fun moveFiles(fileIds: List<Long>, targetParentId: Long): ApiResult<Unit> = error("unused")

        override suspend fun submitCopy(fileList: List<JsonObject>, targetFileId: Long): ApiResult<CopySubmitData> =
            error("unused")

        override suspend fun pollCopyTask(taskId: String): ApiResult<CopyTaskData> = error("unused")
    }

    private class FakePanUploadApi : PanUploadApi {
        val uploadCalls = mutableListOf<UploadCall>()
        private val queue = ArrayDeque<ApiResult<UploadRequestDto>>()
        var gate: CompletableDeferred<Unit>? = null

        fun enqueue(result: ApiResult<UploadRequestDto>) {
            queue.addLast(result)
        }

        override suspend fun requestUpload(
            fileName: String,
            size: Long,
            etag: String,
            parentFileId: Long,
            duplicate: Int,
        ): ApiResult<UploadRequestDto> {
            uploadCalls.addLast(UploadCall(fileName, size, etag, parentFileId, duplicate))
            gate?.await()
            return queue.removeFirstOrNull() ?: error("缺少第 ${uploadCalls.size} 次秒传响应")
        }

        override suspend fun listUploadedParts(
            bucket: String,
            key: String,
            uploadId: String,
            storageNode: String,
        ): ApiResult<List<Int>> = error("unused")

        override suspend fun presignParts(
            bucket: String,
            key: String,
            uploadId: String,
            storageNode: String,
            partNumberStart: Int,
            partNumberEnd: Int,
        ): ApiResult<Map<Int, String>> = error("unused")

        override suspend fun completeMultipartUpload(
            bucket: String,
            key: String,
            uploadId: String,
            storageNode: String,
        ): ApiResult<Unit> = error("unused")

        override suspend fun finishUpload(fileId: Long): ApiResult<Unit> = error("unused")
    }

    /** 两个 DAO 替身共享的内存存储（FileRepositoryTest 同款）。 */
    private class InMemoryDirectoryStore {
        val files = MutableStateFlow<List<CloudFileEntity>>(emptyList())
        val states = MutableStateFlow<Map<Pair<String, Long>, DirectoryStateEntity>>(emptyMap())
    }

    private class FakeCloudFileDao(private val store: InMemoryDirectoryStore) : CloudFileDao() {
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

    private class Fixture(loggedIn: Boolean = true) {
        val fileApi = FakePanFileApi()
        val opsApi = FakePanFileOpsApi()
        val uploadApi = FakePanUploadApi()
        val store = InMemoryDirectoryStore()
        val manager = AccountManager()
        val files: FileRepository = FileRepository(
            api = fileApi,
            cloudFileDao = FakeCloudFileDao(store),
            directoryStateDao = FakeDirectoryStateDao(store),
            manager = manager,
            relogin = { true },
            logger = AppLogger(),
        )
        val ops: FileOpsRepository = FileOpsRepository(
            api = opsApi,
            cloudFileDao = FakeCloudFileDao(store),
            directoryStateDao = FakeDirectoryStateDao(store),
            manager = manager,
            relogin = { true },
            logger = AppLogger(),
        ).apply { refresher = { dirId -> files.refreshDirectory(dirId) } }
        val repository = RapidRepository(uploadApi, files, ops, manager, AppLogger())

        init {
            if (loggedIn) manager.onLoginSuccess("acc-1", "user@example.com", "42", "Bearer token-1")
        }
    }

    private companion object {
        fun fileItem(
            fileId: Long,
            parentFileId: Long,
            name: String,
            folder: Boolean,
        ) = FileItemDto(
            fileId = fileId,
            parentFileId = parentFileId,
            fileName = name,
            isFolder = folder,
            size = 10L * fileId,
            etag = "etag-$fileId",
            s3KeyFlag = "flag-$fileId",
            contentType = "application/octet-stream",
            createAt = 1_700_000_000_000L + fileId,
            updateAt = 1_700_000_100_000L + fileId,
            hidden = false,
            starred = false,
            pinyin = "",
        )

        fun page(parent: Long, vararg items: FileItemDto) = ApiResult.Success(
            FileListDto(
                infoList = items.toList(),
                total = items.size,
                next = "-1",
                len = items.size,
                isFirst = true,
            ),
        )

        fun reuseResult(fileId: Long) = ApiResult.Success(
            UploadRequestDto(reuse = true, fileId = fileId),
        )
    }

    // ------------------------------------------------------------------
    // 目录复用与建链
    // ------------------------------------------------------------------

    @Test
    fun nestedPathsCreateFolderChainOnceAndReuseWithinTask() = runTest {
        val f = Fixture()
        // a 的 ensure：列 0（空）→ 建 a=101 → 操作后刷新 0（带 a）
        f.fileApi.enqueue(page(0))
        f.opsApi.enqueueCreate(ApiResult.Success(101))
        f.fileApi.enqueue(page(0, fileItem(101, 0, "a", folder = true)))
        // b 的 ensure：列 101（空）→ 建 b=102 → 操作后刷新 101（带 b）
        f.fileApi.enqueue(page(101))
        f.opsApi.enqueueCreate(ApiResult.Success(102))
        f.fileApi.enqueue(page(101, fileItem(102, 101, "b", folder = true)))
        // 两个文件的秒传
        f.uploadApi.enqueue(reuseResult(9001))
        f.uploadApi.enqueue(reuseResult(9002))
        // 成功后目标目录联动刷新
        f.fileApi.enqueue(page(0, fileItem(101, 0, "a", folder = true)))

        val report = f.repository.import(
            listOf(RapidFile("a/b/x.txt", etag, 5), RapidFile("a/b/y.txt", etag, 6)),
            parentDirId = 0,
        )

        assertEquals(listOf("a/b/x.txt", "a/b/y.txt"), report.success)
        assertTrue(report.failed.isEmpty())
        // 建目录只发生一次（第二个文件走任务内缓存，不再 create）
        assertEquals(listOf(0L to "a", 101L to "b"), f.opsApi.createFolderCalls)
        // 逐文件秒传：文件名取最后一段，duplicate=1，父目录为最终链
        assertEquals(
            listOf(
                UploadCall("x.txt", 5, etag, 102, 1),
                UploadCall("y.txt", 6, etag, 102, 1),
            ),
            f.uploadApi.uploadCalls,
        )
    }

    @Test
    fun existingFolderReusedWithoutCreateFolderCall() = runTest {
        val f = Fixture()
        // 服务器已有同名文件夹 docs=55：列一次即命中复用，不建目录
        f.fileApi.enqueue(page(0, fileItem(55, 0, "docs", folder = true)))
        f.uploadApi.enqueue(reuseResult(700))
        f.fileApi.enqueue(page(0, fileItem(55, 0, "docs", folder = true)))

        val report = f.repository.import(listOf(RapidFile("docs/z.txt", etag, 7)), parentDirId = 0)

        assertEquals(listOf("docs/z.txt"), report.success)
        assertTrue(f.opsApi.createFolderCalls.isEmpty())
        // 复用的目标目录 id 是 55（服务器既有 docs 文件夹），700 是秒传返回的 fileId。
        assertEquals(55L, f.uploadApi.uploadCalls.single().parentFileId)
    }

    @Test
    fun createFolderFailureMarksFileAsCreateDirFailed() = runTest {
        val f = Fixture()
        f.fileApi.enqueue(page(0))
        f.opsApi.enqueueCreate(ApiResult.ApiError(code = 500, message = "服务繁忙"))

        val report = f.repository.import(listOf(RapidFile("new/x.txt", etag, 8)), parentDirId = 0)

        assertTrue(report.success.isEmpty())
        assertEquals(listOf("new/x.txt" to "创建目录失败"), report.failed)
        // 失败路径不再触碰秒传接口，也不做成功后的目标目录刷新
        assertTrue(f.uploadApi.uploadCalls.isEmpty())
        assertEquals(1, f.fileApi.calls.size)
        assertEquals(RapidProgress(1, 1, "new/x.txt"), f.repository.progress.value)
    }

    // ------------------------------------------------------------------
    // 秒传判定与失败文案
    // ------------------------------------------------------------------

    @Test
    fun nonReuseFailsWithReadableMessageAndNeverUploadsContent() = runTest {
        val f = Fixture()
        // Reuse=false（含 5060 冲突形态）：只记失败，绝不读本地、绝不走 S3 真传
        f.uploadApi.enqueue(ApiResult.Success(UploadRequestDto(conflict = true)))

        val report = f.repository.import(listOf(RapidFile("solo.bin", etag, 9)), parentDirId = 0)

        assertTrue(report.success.isEmpty())
        assertEquals(listOf("solo.bin" to "网盘中不存在相同文件，无法秒传"), report.failed)
        // 全失败：成功后的目标目录刷新不触发（本例也无目录链，fileApi 零调用）
        assertTrue(f.fileApi.calls.isEmpty())
        assertTrue(f.opsApi.createFolderCalls.isEmpty())
    }

    @Test
    fun reuseWithoutFileIdCountsAsMiss() = runTest {
        val f = Fixture()
        f.uploadApi.enqueue(ApiResult.Success(UploadRequestDto(reuse = true, fileId = 0)))

        val report = f.repository.import(listOf(RapidFile("ghost.bin", etag, 1)), parentDirId = 0)

        assertEquals(listOf("ghost.bin" to "网盘中不存在相同文件，无法秒传"), report.failed)
    }

    @Test
    fun apiFailureFailsOnlyThatFile() = runTest {
        val f = Fixture()
        f.uploadApi.enqueue(ApiResult.ApiError(code = 4000, message = "配额不足"))
        f.uploadApi.enqueue(reuseResult(800))
        f.fileApi.enqueue(page(0))

        val report = f.repository.import(
            listOf(RapidFile("a.bin", etag, 1), RapidFile("b.bin", etag, 2)),
            parentDirId = 0,
        )

        assertEquals(listOf("b.bin"), report.success)
        assertEquals(listOf("a.bin" to "配额不足"), report.failed)
    }

    @Test
    fun notLoggedInFailsAllFilesWithoutAnyRequest() = runTest {
        val f = Fixture(loggedIn = false)

        val report = f.repository.import(
            listOf(RapidFile("a.txt", etag, 1), RapidFile("b.txt", etag, 2)),
            parentDirId = 0,
        )

        assertTrue(report.success.isEmpty())
        assertEquals(2, report.failed.size)
        assertTrue(report.failed.all { it.second == "请先登录" })
        assertTrue(f.uploadApi.uploadCalls.isEmpty())
        assertTrue(f.fileApi.calls.isEmpty())
        assertNull(f.repository.progress.value)
    }

    // ------------------------------------------------------------------
    // 取消与进度
    // ------------------------------------------------------------------

    @Test
    fun cancelStopsBeforeNextFileAndKeepsFinishedOnes() = runTest {
        val f = Fixture()
        f.uploadApi.enqueue(reuseResult(1))
        f.uploadApi.enqueue(reuseResult(2))
        f.fileApi.enqueue(page(0))
        var polls = 0
        val cancel = { polls++ >= 1 }

        val report = f.repository.import(
            listOf(RapidFile("a.txt", etag, 1), RapidFile("b.txt", etag, 2)),
            parentDirId = 0,
            cancel = cancel,
        )

        assertEquals(listOf("a.txt"), report.success)
        assertTrue(report.failed.isEmpty())
        assertEquals(1, f.uploadApi.uploadCalls.size)
        assertEquals(RapidProgress(1, 2, "a.txt"), f.repository.progress.value)
    }

    @Test
    fun progressAdvancesPerFileAndStartsAtZero() = runTest {
        val f = Fixture()
        f.uploadApi.enqueue(reuseResult(1))
        f.uploadApi.enqueue(reuseResult(2))
        f.fileApi.enqueue(page(0))
        f.uploadApi.gate = CompletableDeferred()

        val job = launch {
            f.repository.import(
                listOf(RapidFile("f1.bin", etag, 1), RapidFile("f2.bin", etag, 2)),
                parentDirId = 0,
            )
        }
        runCurrent()
        assertEquals(RapidProgress(0, 2, ""), f.repository.progress.value)
        assertEquals(1, f.uploadApi.uploadCalls.size)
        f.uploadApi.gate?.complete(Unit)
        job.join()

        assertEquals(RapidProgress(2, 2, "f2.bin"), f.repository.progress.value)
    }

    // ------------------------------------------------------------------
    // 缓存联动
    // ------------------------------------------------------------------

    @Test
    fun successfulImportRefreshesTargetDirectoryExactlyOnce() = runTest {
        val f = Fixture()
        f.uploadApi.enqueue(reuseResult(1))
        f.uploadApi.enqueue(reuseResult(2))
        f.fileApi.enqueue(page(0))

        val report = f.repository.import(
            listOf(RapidFile("x.txt", etag, 1), RapidFile("y.txt", etag, 2)),
            parentDirId = 0,
        )

        assertEquals(2, report.success.size)
        // 平铺文件无目录链：唯一一次列表调用即成功后的目标目录刷新
        assertEquals(listOf(ApiCall(0, 1, 100, false)), f.fileApi.calls)
    }

    @Test
    fun emptyImportIsNoOp() = runTest {
        val f = Fixture()

        val report = f.repository.import(emptyList(), parentDirId = 0)

        assertTrue(report.success.isEmpty())
        assertTrue(report.failed.isEmpty())
        assertTrue(f.fileApi.calls.isEmpty())
        assertTrue(f.uploadApi.uploadCalls.isEmpty())
    }
}
