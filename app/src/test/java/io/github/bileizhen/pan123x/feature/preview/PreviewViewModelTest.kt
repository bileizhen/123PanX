package io.github.bileizhen.pan123x.feature.preview

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.database.DirectoryStateEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.network.PanApi
import io.github.bileizhen.pan123x.core.transfer.download.DownloadSource
import io.github.bileizhen.pan123x.core.transfer.download.PanDownloadResolver
import io.github.bileizhen.pan123x.core.transfer.download.ResolveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PreviewViewModel 行为测试（缓存行缺失 → Failed；resolve Failure →
 * Failed(userMessage)；Success → Ready(url)； 纯 JVM、不触网、不依赖 Room）。
 *
 * 真 AccountManager（onLoginSuccess 置 Ready）+ 手写 CloudFileDao 内存替身
 * （风格对齐 FilesViewModelTest）+ 经第 5 个带默认值构造参数注入的 resolve 替身——
 * PanDownloadResolver 是具体类，替身函数让测试不必真实取链（PreviewViewModel KDoc）。
 * Main 置 UnconfinedTestDispatcher，viewModelScope 的取数链在构造内同步走完。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {

    private companion object {
        const val ACCOUNT_ID = "acc-1"
        const val FILE_ID = 4242L
        const val RESOLVED_URL = "https://cdn.example.com/photo.png?signed=1"
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private val manager = AccountManager()

    private fun loggedIn() {
        manager.onLoginSuccess(ACCOUNT_ID, "测试用户", "10001", "Bearer test-token")
    }

    /** 仅 get 有意义；其余抽象成员最小落地（替身不参与被测路径）。 */
    private class FakeCloudFileDao : CloudFileDao() {
        val rows = MutableStateFlow<List<CloudFileEntity>>(emptyList())

        fun put(file: CloudFileEntity) {
            rows.value = rows.value + file
        }

        override fun observeDirectory(accountId: String, parentFileId: Long): Flow<List<CloudFileEntity>> =
            rows.map { list ->
                list.filter { it.accountId == accountId && it.parentFileId == parentFileId }
            }

        override suspend fun get(accountId: String, fileId: Long): CloudFileEntity? =
            rows.value.firstOrNull { it.accountId == accountId && it.fileId == fileId }

        override suspend fun upsert(files: List<CloudFileEntity>) {
            rows.value = rows.value + files
        }

        override suspend fun deleteDirectory(accountId: String, parentFileId: Long) {
            rows.value = rows.value.filterNot {
                it.accountId == accountId && it.parentFileId == parentFileId
            }
        }

        override suspend fun upsertDirectoryState(state: DirectoryStateEntity) = Unit
    }

    private fun sampleEntity(fileId: Long = FILE_ID) = CloudFileEntity(
        accountId = ACCOUNT_ID,
        fileId = fileId,
        parentFileId = 0,
        fileName = "旅行照片.png",
        isFolder = false,
        size = 2_048,
        etag = "etag-alpha",
        s3KeyFlag = "s3-flag",
        createAt = 100L,
        updateAt = 200L,
    )

    private fun expectedSource(file: CloudFileEntity) = DownloadSource(
        fileId = file.fileId,
        fileName = file.fileName,
        size = file.size,
        etag = file.etag,
        s3KeyFlag = file.s3KeyFlag,
        isFolder = file.isFolder,
    )

    /** 类型占位：resolve 已被注入替身，真实 resolver 永远不会被调用。 */
    private fun dummyResolver() = PanDownloadResolver(
        api = PanApi(client = OkHttpClient(), primaryBaseUrl = "http://localhost:1/", fallbackBaseUrl = null),
        transfer = OkHttpClient(),
        logger = AppLogger(),
        relogin = { false },
    )

    private fun viewModel(
        dao: FakeCloudFileDao,
        outcome: ResolveOutcome,
        captured: MutableList<DownloadSource>,
    ): PreviewViewModel = PreviewViewModel(
        fileId = FILE_ID,
        cloudFileDao = dao,
        manager = manager,
        resolver = dummyResolver(),
        resolve = { source ->
            captured += source
            outcome
        },
    )

    @Test
    fun readyWhenCacheRowExistsAndResolveSucceeds() = runTest {
        loggedIn()
        val file = sampleEntity()
        val captured = mutableListOf<DownloadSource>()

        val viewModel = viewModel(
            dao = FakeCloudFileDao().apply { put(file) },
            outcome = ResolveOutcome.Success(RESOLVED_URL, trafficLimited = false),
            captured = captured,
        )

        val ready = viewModel.state.value as PreviewState.Ready
        assertEquals(file, ready.file)
        assertEquals(RESOLVED_URL, ready.url)
        // 缓存行必须按 DownloadSource 的稳定字段全集传给解析器
        assertEquals(listOf(expectedSource(file)), captured)
    }

    @Test
    fun failedWithFixedMessageWhenCacheRowMissing() = runTest {
        loggedIn()
        val captured = mutableListOf<DownloadSource>()

        val viewModel = viewModel(
            dao = FakeCloudFileDao(), // 空缓存：目录刷新被替换 / 未登录
            outcome = ResolveOutcome.Success(RESOLVED_URL, trafficLimited = false),
            captured = captured,
        )

        val failed = viewModel.state.value as PreviewState.Failed
        assertEquals("文件不存在或已刷新，请返回重试", failed.userMessage)
        assertEquals("", failed.fileName)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun failedWithUserMessageAndFileNameWhenResolveFails() = runTest {
        loggedIn()
        val file = sampleEntity()
        val captured = mutableListOf<DownloadSource>()

        val viewModel = viewModel(
            dao = FakeCloudFileDao().apply { put(file) },
            outcome = ResolveOutcome.Failure("下载链接获取失败，请稍后重试"),
            captured = captured,
        )

        val failed = viewModel.state.value as PreviewState.Failed
        assertEquals("下载链接获取失败，请稍后重试", failed.userMessage)
        assertEquals(file.fileName, failed.fileName)
    }

    @Test
    fun failedWhenSessionNotReady() = runTest {
        // 未登录（LoggedOut）等价于缓存不可达：同样落到固定缺态文案，fileName 为空
        manager.onLogout()
        val captured = mutableListOf<DownloadSource>()

        val viewModel = viewModel(
            dao = FakeCloudFileDao(),
            outcome = ResolveOutcome.Success(RESOLVED_URL, trafficLimited = false),
            captured = captured,
        )

        val failed = viewModel.state.value as PreviewState.Failed
        assertEquals("文件不存在或已刷新，请返回重试", failed.userMessage)
        assertEquals("", failed.fileName)
        assertTrue(captured.isEmpty())
    }
}
