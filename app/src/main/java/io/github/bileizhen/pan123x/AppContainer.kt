package io.github.bileizhen.pan123x

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.DeviceIdentityStore
import io.github.bileizhen.pan123x.core.account.KeystoreCredentialCrypto
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AppDatabase
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanApi
import io.github.bileizhen.pan123x.core.network.PanHttpClientFactory
import io.github.bileizhen.pan123x.core.network.PanQrApiClient
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.diagnostics.DiagnosticsRepository
import io.github.bileizhen.pan123x.data.diagnostics.RoomDiagnosticsSource
import io.github.bileizhen.pan123x.data.offline.OfflineRepository
import io.github.bileizhen.pan123x.data.transfer.RapidRepository
import io.github.bileizhen.pan123x.data.auth.RoomAccountMetadataStore
import io.github.bileizhen.pan123x.data.file.FileOpsRepository
import io.github.bileizhen.pan123x.data.file.FileRepository
import io.github.bileizhen.pan123x.data.file.RecycleRepository
import io.github.bileizhen.pan123x.data.settings.SettingsRepository
import io.github.bileizhen.pan123x.core.transfer.download.DefaultDownloadLauncher
import io.github.bileizhen.pan123x.core.transfer.download.DownloadCoordinator
import io.github.bileizhen.pan123x.core.transfer.download.PanDownloadResolver
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxConfig
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxDownloadEngine
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadStorage
import io.github.bileizhen.pan123x.core.transfer.upload.ContentUriUploadSource
import io.github.bileizhen.pan123x.core.transfer.upload.DefaultUploadLauncher
import io.github.bileizhen.pan123x.core.transfer.upload.UploadCoordinator
import io.github.bileizhen.pan123x.core.transfer.upload.engine.OkHttpUploadPartTransport
import io.github.bileizhen.pan123x.core.transfer.upload.engine.UploadEngine
import io.github.bileizhen.pan123x.core.transfer.background.TransferBackgroundLauncher
import io.github.bileizhen.pan123x.core.transfer.background.TransferNotifications
import io.github.bileizhen.pan123x.core.transfer.preview.PdfPreviewCache
import io.github.bileizhen.pan123x.core.share.ShareLauncher
import io.github.bileizhen.pan123x.data.share.ShareRepository
import io.github.bileizhen.pan123x.data.transfer.TransferRepository
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Optional explicit dependencies for tests; production uses the verified default clients. */
data class ContainerOverrides(
    val database: AppDatabase? = null,
    val preferencesPrefix: String = "",
    val restoreCredentials: Boolean = true,
    val automaticUpdates: Boolean = true,
    val apiClient: OkHttpClient? = null,
    val transferClient: OkHttpClient? = null,
    val qrClient: OkHttpClient? = null,
)

/** Application owns dependency lifetimes; ViewModels receive concrete dependencies explicitly. */
class AppContainer(context: Context, overrides: ContainerOverrides = ContainerOverrides()) {
    val automaticUpdates = overrides.automaticUpdates
    private val appContextInternal = context.applicationContext

    /** application context：PanXApp 的 stopBackground 回调等少数接线点需要（不存 UI 状态）。 */
    val appContext: Context get() = appContextInternal
    val logger = AppLogger()
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        logger.e(LogSource.APP, "后台基础任务失败：${error.javaClass.simpleName}")
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    /** 传输任务专用 scope：下载任务在 IO 上跑，进程内不随 UI 生命周期结束。 */
    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    val database: AppDatabase = overrides.database ?: AppDatabase.create(appContext)
    private val preferences = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler {
            logger.w(LogSource.APP, "外观设置文件损坏，已恢复默认外观")
            emptyPreferences()
        },
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        produceFile = { appContext.preferencesDataStoreFile(overrides.preferencesPrefix + "appearance") },
    )
    // 凭据文件只存 AES-GCM 密文与非敏感元数据：损坏时按未登录处理，
    // 不静默重建，避免把半份数据当成完整会话。
    private val credentialPreferences = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler {
            logger.w(LogSource.AUTH, "凭据文件损坏，本次按未登录处理")
            emptyPreferences()
        },
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        produceFile = { appContext.preferencesDataStoreFile(overrides.preferencesPrefix + "credentials") },
    )
    // 设备指纹是伪装身份而非敏感数据，损坏后重新抽取一枚即可继续使用。
    private val devicePreferences = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler {
            logger.w(LogSource.AUTH, "设备指纹文件损坏，已重新生成")
            emptyPreferences()
        },
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        produceFile = { appContext.preferencesDataStoreFile(overrides.preferencesPrefix + "device_identity") },
    )
    val settings = SettingsRepository(preferences, applicationScope, logger)

    private val credentialCrypto = KeystoreCredentialCrypto()
    private val proxyPreferences = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO),
        produceFile = { appContext.preferencesDataStoreFile(overrides.preferencesPrefix + "network_proxy") },
    )
    val proxySettings = io.github.bileizhen.pan123x.data.settings.ProxySettingsRepository(proxyPreferences, credentialCrypto, applicationScope)
    private val networkRouting = io.github.bileizhen.pan123x.core.network.NetworkRouting({ proxySettings.state.value }, { proxySettings.loaded }, systemProxy = io.github.bileizhen.pan123x.core.network.AndroidSystemProxy(appContext)::select)
    val imageHttpClient = overrides.transferClient ?: PanHttpClientFactory.imageClient(networkRouting)
    val appUpdates = io.github.bileizhen.pan123x.data.settings.UpdateRepository(imageHttpClient, BuildConfig.VERSION_NAME)
    val updateDownloader = io.github.bileizhen.pan123x.data.settings.UpdateDownloader(imageHttpClient, File(appContext.cacheDir, "updates"))
    val updateInstaller = io.github.bileizhen.pan123x.data.settings.AndroidUpdateInstaller(appContext)
    val credentialStore = SecureCredentialStore(credentialPreferences, credentialCrypto)
    val deviceIdentityStore = DeviceIdentityStore(devicePreferences)
    val accountManager = AccountManager()
    // 类型取具体 PanApi：同时满足 PanAuthApi + PanFileApi，供认证与文件两个仓库复用。
    val panApi: PanApi = PanApi(
        overrides.apiClient ?: PanHttpClientFactory.defaultClient(accountManager::deviceProfile, accountManager,
            simulation = { settings.state.value.clientSimulation }, routing = networkRouting),
        logger = logger,
    )
    val accountMetadata = RoomAccountMetadataStore(database.accountDao())
    val authRepository = AuthRepository(
        panApi,
        credentialStore,
        deviceIdentityStore,
        accountMetadata,
        accountManager,
        logger,
        // M7 多账户：移除账户时删行级联清缓存。
        accountDao = database.accountDao(),
        // M7 QR 登录：LOGIN_BASE_URL 直连 + 持久化设备指纹的 loginuuid。
        qrApi = PanQrApiClient(identity = { deviceIdentityStore.loadOrCreate() }, client = overrides.qrClient ?: imageHttpClient, logger = logger),
    )
    val fileRepository = FileRepository(
        api = panApi,
        cloudFileDao = database.cloudFileDao(),
        directoryStateDao = database.directoryStateDao(),
        manager = accountManager,
        relogin = { authRepository.relogin() is ApiResult.Success },
        logger = logger,
    )
    // M3 文件操作（创建/重命名/删除/恢复/永久删除/移动/复制）；回收站复用其恢复与永久删除能力。
    val fileOpsRepository = FileOpsRepository(panApi, database.cloudFileDao(), database.directoryStateDao(), accountManager, { authRepository.relogin() is ApiResult.Success }, logger)
    // 回收站：列表只存内存（trashed 行与正常目录缓存同 parentFileId，混写会污染浏览缓存）。
    val recycleRepository = RecycleRepository(panApi, fileOpsRepository, accountManager, logger)

    // ---- M4 下载：取链 → NSFX 引擎 → SAF/应用内存储 ----
    // 取链走 PanApiClient（带设备头/认证头），下载走 TransferClient（干净、无 API 头、手动跟随重定向）。
    private val transferClient = overrides.transferClient ?: PanHttpClientFactory.transferClient(routing = networkRouting)
    private val downloadLimiter = io.github.bileizhen.pan123x.core.transfer.LiveRateLimiter(rate = { settings.state.value.downloadSpeedLimit })
    private val uploadLimiter = io.github.bileizhen.pan123x.core.transfer.LiveRateLimiter(rate = { settings.state.value.uploadSpeedLimit })
    val panDownloadResolver = PanDownloadResolver(
        api = panApi,
        transfer = transferClient,
        logger = logger,
        relogin = { authRepository.relogin() is ApiResult.Success },
    )
    val downloadStorage = DownloadStorage(appContext, logger)
    val engineConfig = NsfxConfig()
    val downloadCoordinator = DownloadCoordinator(
        api = panApi,
        resolver = panDownloadResolver,
        storage = downloadStorage,
        taskDao = database.transferTaskDao(),
        segmentDao = database.downloadSegmentDao(),
        manager = accountManager,
        logger = logger,
        engine = NsfxDownloadEngine(engineConfig,
            http = io.github.bileizhen.pan123x.core.transfer.download.nsfx.NsfxHttpClient(engineConfig, transferClient), logger = logger,
            retriesEnabled = { settings.state.value.errorBackoffRetry }, consumeBytes = downloadLimiter::consume),
        workRoot = File(appContext.filesDir, TRANSFER_WORK_DIR),
        engineConfig = engineConfig,
        connections = { if (settings.state.value.multiThreadDownload) settings.state.value.downloadConnections else 1 },
        taskLimit = { settings.state.value.maxConcurrentDownloads },
    )
    // 文件页"下载"入口：负责来源映射与默认保存位置策略，UI 只依赖这个窄接口。
    val downloadLauncher = DefaultDownloadLauncher(downloadCoordinator, downloadStorage, logger,
        preferredTree = { settings.state.value.downloadTree })

    // ---- M5 上传：S3 multipart，分片 PUT 走 TransferClient ----
    // 元数据（upload_request 等）走 panApi（带认证/设备头）；分片字节走 transferClient（干净、无 API 头，
    //）。上传不做字节级断点，分片粒度断点的权威来源是服务端 s3_list_upload_parts（硬约束）。
    val transferNotifications = TransferNotifications(appContext).also { it.ensureChannel() }
    val uploadEngine = UploadEngine(
        api = panApi,
        transport = OkHttpUploadPartTransport(transferClient, uploadLimiter,
            speedLimit = { settings.state.value.uploadSpeedLimit }, concurrentTasks = { settings.state.value.maxConcurrentUploads }),
        logger = logger,
        threadsProvider = { settings.state.value.uploadThreads },
        retriesEnabled = { settings.state.value.errorBackoffRetry },
    )
    val uploadCoordinator = UploadCoordinator(
        api = panApi,
        engine = uploadEngine,
        taskDao = database.transferTaskDao(),
        partDao = database.uploadPartDao(),
        manager = accountManager,
        logger = logger,
        taskLimit = { settings.state.value.maxConcurrentUploads },
    )
    // 统一传输任务视图（上传/下载共用工作台）：UI 只依赖它，不直接碰两个协调器。
    val transferRepository = TransferRepository(
        context = appContext,
        taskDao = database.transferTaskDao(),
        segmentDao = database.downloadSegmentDao(),
        partDao = database.uploadPartDao(),
        download = downloadCoordinator,
        upload = uploadCoordinator,
        accountManager = accountManager,
        uploadSourceFromUri = { uri -> ContentUriUploadSource.create(appContext, android.net.Uri.parse(uri)) },
        logger = logger,
    )
    // 文件页"上传"入口：SAF uri → ContentUriUploadSource → enqueueUpload，UI 只依赖窄接口。
    val uploadLauncher = DefaultUploadLauncher(appContext, transferRepository, logger)

    /** 工作台在任务离开活跃态时调用，撤掉后台执行外壳（前台服务 / User-Initiated Job）。 */
    fun stopTransferBackground(taskId: String) = TransferBackgroundLauncher.stop(appContext, taskId)

    /** Application foreground refresh survives UI navigation and preserves cached account information. */
    fun synchronizeAccountInfo() = applicationScope.launch { authRepository.syncUserInfoIfStale() }

    private fun onCloudContentChanged(accountId: String) {
        authRepository.invalidateUserInfo(accountId)
        synchronizeAccountInfo()
    }

    // ---- M6 分享与预览----
    // 分享元数据走 panApi（带认证/设备头），仓库只存内存（RecycleRepository 先例：分享行与
    // 文件缓存无外键关系，入 Room 收益为零）。reset 订阅会话状态，登出/切换账户即清列表。
    val shareRepository = ShareRepository(
        api = panApi,
        manager = accountManager,
        relogin = { authRepository.relogin() is ApiResult.Success },
        logger = logger,
    )
    // 文件页多选"分享"入口的窄接口，UI / ViewModel 不直接碰分享 API。
    val shareLauncher = ShareLauncher { fileIds, options -> shareRepository.create(fileIds, options) }

    // ---- M7 离线下载 / 秒传----
    val offlineRepository = OfflineRepository(
        api = panApi,
        manager = accountManager,
        relogin = { authRepository.relogin() is ApiResult.Success },
        logger = logger,
    )
    val rapidRepository = RapidRepository(panApi, fileRepository, fileOpsRepository, accountManager, logger)

    // ---- M7 诊断----
    val diagnosticsRepository = DiagnosticsRepository(appContext, RoomDiagnosticsSource(database), logger)
    val diagnosticReport = io.github.bileizhen.pan123x.data.diagnostics.DiagnosticReport(appContext, logger, diagnosticsRepository)
    val maintenanceRepository = io.github.bileizhen.pan123x.data.settings.CacheMaintenanceRepository(
        database.cacheMaintenanceDao(), accountManager, fileRepository) {
            val previews = io.github.bileizhen.pan123x.data.settings.PreviewCacheCleaner(appContext.cacheDir).clear()
            val images = coil3.SingletonImageLoader.get(appContext)
            val diskBytes = images.diskCache?.size ?: 0L
            kotlinx.coroutines.withContext(Dispatchers.IO) { images.diskCache?.clear(); images.memoryCache?.clear() }
            previews + diskBytes
        }

    /** 预览拉流客户端（文本/PDF 直链，：TransferClient 干净、无 API 认证头）。 */
    val previewHttpClient get() = transferClient
    // PDF 预览缓存（PdfPreviewCache 内部自拼 previews/ 子目录，这里给 cacheDir 根）。
    val pdfPreviewCache = PdfPreviewCache(appContext.cacheDir, transferClient)

    init {
        // 会话登出 / 切换账户即清分享内存列表；无关的状态重发射不清，
        // 否则用户正在浏览的列表会被无关 emission 抹掉。
        shareRepository.bindSession(applicationScope)
        // 启动即恢复会话：无论结果如何都会离开 Restoring，登录页可安全等待。
        if (overrides.restoreCredentials) applicationScope.launch { proxySettings.awaitLoaded(); authRepository.restoreSession() }
        else accountManager.onLogout()
        applicationScope.launch { settings.state.collect { logger.minimumLevel = io.github.bileizhen.pan123x.core.logging.LogLevel.valueOf(it.logLevel) } }
        // M3 文件操作成功后的目录缓存联动刷新：仓库失效目录状态后立即拉新
        // （不允许 UI 假成功；ViewModel 不手动刷）。
        fileOpsRepository.refresher = { dirId ->
            val accountId = (accountManager.state.value as? SessionState.Ready)?.accountId
            fileRepository.refreshDirectory(dirId)
            accountId?.let(::onCloudContentChanged)
        }
        uploadCoordinator.onUploaded = ::onCloudContentChanged
        // M4 下载 / M5 上传任务跑在独立 IO scope 上，脱离 ViewModel/UI 生命周期。
        downloadCoordinator.attach(transferScope)
        uploadCoordinator.attach(transferScope)
        // 进程重启后的遗留活跃任务不自动重启，只转 WAITING_USER 等用户显式继续。
        // 必须等会话恢复完成，否则拿不到 accountId。
        transferScope.launch {
            accountManager.awaitRestored()
            downloadCoordinator.recoverOnStart()
            uploadCoordinator.recoverOnStart()
        }
    }

    private companion object {
        /** 应用内下载工作目录（分段日志与未发布数据），FileProvider 只暴露 downloads/。 */
        const val TRANSFER_WORK_DIR = "transfers"
    }
}
