package io.github.bileizhen.pan123x.data.auth

import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.DeviceIdentityStore
import io.github.bileizhen.pan123x.core.account.PlainCredential
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountDao
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanAuthApi
import io.github.bileizhen.pan123x.core.network.PanQrApi
import io.github.bileizhen.pan123x.core.network.QrPollDto
import io.github.bileizhen.pan123x.core.network.UserInfoDto
import io.github.bileizhen.pan123x.core.network.LoginDeviceDto
import io.github.bileizhen.pan123x.core.network.PanDeviceApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 认证仓库：登录 / 登出 / 会话恢复 / 用户信息刷新。
 *
 * 职责边界：
 * - 敏感凭据只经 [SecureCredentialStore]（密文落盘），公开元数据只经 [AccountMetadataStore]；
 * - 会话内存状态与请求头素材由 [AccountManager] 单点维护；
 * - 123pan 无 refresh token：code==2 时用已存密码整体重登一次（非幂等 POST，
 *   仅在明确的会话过期场景发起且只发一次）；
 * - 登出无服务端接口，仅清理本地。
 */
class AuthRepository(
    private val api: PanAuthApi,
    private val credentials: SecureCredentialStore,
    private val identityStore: DeviceIdentityStore,
    private val metadata: AccountMetadataStore,
    private val manager: AccountManager,
    private val logger: AppLogger,
    // ---- M7 多账户----
    /** accounts 表直连：移除账户时删行以触发外键级联清缓存（AppContainer 接线 Room DAO；缺省 null 时仅清凭据）。 */
    private val accountDao: AccountDao? = null,
    // ---- M7 QR 登录----
    /** 扫码登录协议入口（AppContainer 接线 PanQrApiClient；缺省 null 时扫码登录不可用）。 */
    private val qrApi: PanQrApi? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val deviceApi: PanDeviceApi? = api as? PanDeviceApi,
) {

    val session: StateFlow<SessionState> = manager.state
    private val userInfoMutex = Mutex()
    @Volatile private var lastSync: SyncStamp? = null
    private val metadataRevision = AtomicLong()
    private data class SyncStamp(val generation: Long, val revision: Long, val at: Long, val successful: Boolean)

    fun invalidateUserInfo(accountId: String) {
        if ((manager.state.value as? SessionState.Ready)?.accountId == accountId) metadataRevision.incrementAndGet()
    }

    /** Foreground/page-entry/startup requests coalesce; failed sync keeps the cached metadata. */
    suspend fun syncUserInfoIfStale(): ApiResult<UserInfoDto>? = userInfoMutex.withLock {
        val ready = manager.awaitRestored() as? SessionState.Ready ?: return@withLock null
        val stamp = lastSync
        val interval = if (stamp?.successful == true) 30_000L else 10_000L
        if (stamp != null && stamp.generation == manager.generation && stamp.revision == metadataRevision.get() && clock() - stamp.at in 0 until interval) return@withLock null
        val generation = manager.generation
        val revision = metadataRevision.get()
        val result = refreshUserInfoLocked()
        if ((manager.state.value as? SessionState.Ready)?.accountId == ready.accountId && manager.generation == generation) {
            lastSync = SyncStamp(generation, revision, clock(), result is ApiResult.Success)
        }
        if (result !is ApiResult.Success) logger.w(LogSource.AUTH, "账户信息自动同步未完成，保留本地缓存")
        result
    }

    suspend fun login(passport: String, password: String): LoginOutcome {
        val trimmed = passport.trim()
        if (trimmed.isEmpty()) return LoginOutcome.Failure(EMPTY_PASSPORT_MESSAGE)
        // 等启动恢复结束再登录，避免恢复流程晚于登录完成时覆盖会话状态。
        manager.awaitRestored()
        // 登录请求头需与持久化指纹一致：先取（或首次生成）设备身份并切换请求指纹。
        val identity = identityStore.loadOrCreate()
        manager.updateProfile(identity)

        val result = api.login(trimmed, password)
        val authorization = (result as? ApiResult.Success)?.data
        if (authorization == null) {
            val userMessage = AuthMessages.loginFailure(result)
            logger.w(LogSource.AUTH, "登录失败：$userMessage")
            return LoginOutcome.Failure(userMessage)
        }

        val accountId = SecureCredentialStore.accountIdFor(trimmed)
        // Ready 会立即启动文件加载；accounts 外键必须先存在，不能等用户信息响应再创建。
        try {
            ensureAccountRow(accountId, trimmed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            logger.e(LogSource.DATABASE, "登录账户记录准备失败：${failure.javaClass.simpleName}")
            return LoginOutcome.Failure(ACCOUNT_STORAGE_MESSAGE)
        }
        // 账户记录就绪后落凭据、置会话，再补全用户信息：
        // - getUserInfo 的 authorization 头由 AccountManager 提供，token 必须先写入；
        // - 进程在补全元数据途中被杀时，重启后仍可凭磁盘凭据恢复会话。
        credentials.save(
            PlainCredential(
                accountId = accountId,
                passport = trimmed,
                password = password,
                authorization = authorization,
                identity = identity,
            ),
        )
        manager.onLoginSuccess(accountId, displayName = trimmed, uid = "", authorization)

        val generation = manager.generation
        val revision = metadataRevision.get()
        val info = api.getUserInfo()
        if (info is ApiResult.Success && isCurrent(accountId, generation)) {
            persistUserInfo(accountId, trimmed, info.data, revision)
        } else {
            // 元数据只是展示增强：用户信息失败不阻塞登录成功，昵称暂用账号名。
            logger.w(LogSource.AUTH, "登录成功，但用户信息获取失败，暂用账号名作为昵称")
        }
        logger.i(LogSource.AUTH, "登录成功")
        return LoginOutcome.Success
    }

    /** 仅清本地当前账户凭据；Room 元数据与其他账户条目保留，供多账户再次登录复用。 */
    suspend fun logout() {
        val generation = manager.generation
        val ready = manager.state.value as? SessionState.Ready
        if (ready != null) {
            credentials.clear(ready.accountId)
        } else {
            credentials.clearActive()
        }
        manager.logoutIfCurrent(generation)
        logger.i(LogSource.AUTH, "已退出登录")
    }

    /** AppContainer 启动时调用；无论凭据是否存在、读取是否失败，结束时一定离开 Restoring。 */
    suspend fun restoreSession() {
        val credential = try {
            credentials.active()
        } catch (failure: IOException) {
            logger.e(LogSource.AUTH, "凭据读取失败，本次按未登录处理")
            null
        }
        if (credential == null) {
            manager.onLogout()
            return
        }
        manager.updateProfile(credential.identity)
        val account = try {
            ensureAccountRow(credential.accountId, credential.passport)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            logger.e(LogSource.DATABASE, "恢复账户记录准备失败：${failure.javaClass.simpleName}")
            // 保留磁盘凭据供重试，但禁止发布缺少父行的 Ready。
            manager.onLogout()
            return
        }
        manager.onLoginSuccess(
            accountId = credential.accountId,
            displayName = account.displayName.ifBlank { credential.passport },
            uid = account.uid,
            authorization = credential.authorization,
        )
        logger.i(LogSource.AUTH, "已恢复登录会话")
        syncUserInfoIfStale()
    }

    /**
     * 拉取最新用户信息。code==2（会话过期）时用保存的密码重登一次：
     * 成功则更新 token 并重试一次 getUserInfo；重登失败或重试仍过期则登出并返回失败。
     */
    suspend fun refreshUserInfo(): ApiResult<UserInfoDto> = userInfoMutex.withLock { refreshUserInfoLocked() }

    /** Device details stay in memory; ignore results after logout or account changes. */
    suspend fun getLoginDevices(): ApiResult<List<LoginDeviceDto>> = userInfoMutex.withLock {
        val ready = manager.awaitRestored() as? SessionState.Ready ?: return@withLock ApiResult.SessionExpired
        val endpoint = deviceApi ?: return@withLock ApiResult.NetworkError("登录设备信息暂不可用")
        var generation = manager.generation
        val first = endpoint.getLoginDevices()
        if (!isCurrent(ready.accountId, generation)) return@withLock accountChanged()
        if (first !is ApiResult.SessionExpired) return@withLock first
        val renewed = relogin()
        when (renewed) {
            is ApiResult.Success -> Unit
            is ApiResult.ApiError -> return@withLock renewed
            is ApiResult.NetworkError -> return@withLock renewed
            is ApiResult.ParseError -> return@withLock renewed
            ApiResult.SessionExpired -> return@withLock ApiResult.SessionExpired
        }
        if ((manager.state.value as? SessionState.Ready)?.accountId != ready.accountId) return@withLock accountChanged()
        generation = manager.generation
        val retried = endpoint.getLoginDevices()
        if (!isCurrent(ready.accountId, generation)) return@withLock accountChanged()
        if (retried is ApiResult.SessionExpired) logout()
        retried
    }

    private suspend fun refreshUserInfoLocked(): ApiResult<UserInfoDto> {
        val ready = manager.state.value as? SessionState.Ready ?: return ApiResult.SessionExpired
        var generation = manager.generation
        val revision = metadataRevision.get()
        val first = api.getUserInfo()
        if (!isCurrent(ready.accountId, generation)) return accountChanged()
        if (first !is ApiResult.SessionExpired) {
            if (first is ApiResult.Success) persistUserInfoFromSession(ready, generation, revision, first.data)
            return first
        }
        val renewed = relogin()
        if (renewed !is ApiResult.Success) {
            return asUserInfoFailure(renewed)
        }
        if ((manager.state.value as? SessionState.Ready)?.accountId != ready.accountId) return accountChanged()
        generation = manager.generation
        val retried = api.getUserInfo()
        if (!isCurrent(ready.accountId, generation)) return accountChanged()
        if (retried is ApiResult.SessionExpired) {
            logger.w(LogSource.AUTH, "自动重登后仍提示会话失效，已自动登出")
            logout()
            return retried
        }
        if (retried is ApiResult.Success) {
            persistUserInfoFromSession(ready, generation, revision, retried.data)
        }
        return retried
    }

    /**
     * 会话过期后的统一重登（123pan 无 refresh token，：非幂等 POST 仅在
     * 明确过期场景发起且只发一次）。成功：更新磁盘 token 与会话状态并返回 Success；
     * 失败或本地无凭据：登出并返回失败原因。文件域等调用方以此恢复会话后重试原请求。
     */
    suspend fun relogin(): ApiResult<String> {
        val generation = manager.generation
        val ready = manager.state.value as? SessionState.Ready ?: return ApiResult.SessionExpired
        val credential = credentials.active()
        if (!isCurrent(ready.accountId, generation)) return accountChanged()
        if (credential == null) {
            logger.w(LogSource.AUTH, "会话已失效且本地没有可重登的凭据，已自动登出")
            logout()
            return ApiResult.ApiError(code = 2, message = "登录状态已失效")
        }
        if (credential.accountId != ready.accountId) return accountChanged()
        val reloginResult = api.login(credential.passport, credential.password)
        if (!isCurrent(ready.accountId, generation)) return accountChanged()
        val renewed = (reloginResult as? ApiResult.Success)?.data
        if (renewed == null) {
            logger.w(LogSource.AUTH, "会话过期后自动重登失败，已自动登出")
            logout()
            return reloginResult
        }
        if (!credentials.renewIfActive(credential, renewed) || !manager.renewIfCurrent(generation, renewed)) return accountChanged()
        logger.i(LogSource.AUTH, "会话过期后自动重登成功")
        return reloginResult
    }

    // ---- M7 多账户----

    /**
     * 切换到已保存凭据的账户：激活凭据 → 走与 [restoreSession] 相同的恢复链。
     *
     * 顺序约束与 [login] 一致：先 [AccountManager.onLoginSuccess] 写入 token 再拉用户信息，
     * 因为 getUserInfo 的 authorization 头由 [AccountManager] 提供。切换前先清内存会话
     * （token 与 Ready 状态），但不删任何账户的凭据，由 activate 重定向活跃指针。
     * accounts 行必须在发布 Ready 前准备完成。用户信息失败可保留占位行继续使用；
     * 本地账户记录无法准备时返回失败，不启动文件缓存写入。
     */
    suspend fun switchTo(accountId: String): SwitchOutcome {
        // 等启动恢复结束再切换，避免恢复流程晚于切换完成时覆盖会话状态（与 login 相同）。
        manager.awaitRestored()
        if (manager.state.value is SessionState.Ready) manager.onLogout()
        val credential = try {
            credentials.activate(accountId)
        } catch (failure: IOException) {
            logger.e(LogSource.AUTH, "切换账户时凭据读取失败，本次按无凭据处理")
            null
        }
        if (credential == null) {
            logger.w(LogSource.AUTH, "切换账户失败：该账户没有保存的登录凭据")
            return SwitchOutcome.Failed(NO_SAVED_CREDENTIAL_MESSAGE)
        }
        manager.updateProfile(credential.identity)
        try {
            ensureAccountRow(accountId, credential.passport)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            logger.e(LogSource.DATABASE, "切换账户记录准备失败：${failure.javaClass.simpleName}")
            return SwitchOutcome.Failed(ACCOUNT_STORAGE_MESSAGE)
        }
        manager.onLoginSuccess(
            accountId = accountId,
            displayName = credential.passport,
            uid = "",
            authorization = credential.authorization,
        )
        val generation = manager.generation
        val revision = metadataRevision.get()
        val info = api.getUserInfo()
        if (!isCurrent(accountId, generation)) return SwitchOutcome.Failed("账户已切换，请重新选择")
        if (info is ApiResult.Success) {
            persistUserInfo(accountId, credential.passport, info.data, revision)
        } else {
            // 父账户行已经存在，用户信息失败不会阻塞切换和文件缓存。
            logger.w(LogSource.AUTH, "切换账户成功，但用户信息获取失败，暂用账号名")
        }
        val displayName = (manager.state.value as? SessionState.Ready)?.displayName
            ?.ifBlank { null } ?: credential.passport
        logger.i(LogSource.AUTH, "已切换账户")
        return SwitchOutcome.Switched(displayName)
    }

    /**
     * 移除本地已保存的账户：清凭据 + 删 accounts 行（外键级联清文件缓存、
     * 传输任务等）。移除当前活跃账户等价登出：活跃指针随凭据条目一并清除，会话回到
     * LoggedOut。只动本地数据，云端账户本身不受影响。
     *
     * @return true=已移除；本地既无凭据也无该账户的元数据行（不存在）时返回 false。
     */
    suspend fun removeAccount(accountId: String): Boolean {
        val hasCredential = try {
            accountId in credentials.accountIds()
        } catch (failure: IOException) {
            // 枚举失败按"可能有凭据"处理，让清理流程继续走 clear 与删行，不留半份残留。
            logger.e(LogSource.AUTH, "移除账户时凭据枚举失败，按存在凭据继续清理")
            true
        }
        val row = runCatching { metadata.get(accountId) }
            .onFailure { logger.w(LogSource.DATABASE, "账户元数据读取失败，移除时按凭据存在性判断") }
            .getOrNull()
        if (!hasCredential && row == null) {
            logger.w(LogSource.AUTH, "移除账户失败：本地没有该账户")
            return false
        }
        if (hasCredential) credentials.clear(accountId)
        val dao = accountDao
        if (dao != null) {
            runCatching { dao.delete(accountId) }
                .onFailure { logger.e(LogSource.DATABASE, "账户行删除失败：${it.javaClass.simpleName}") }
        }
        if ((manager.state.value as? SessionState.Ready)?.accountId == accountId) manager.onLogout()
        logger.i(LogSource.AUTH, "已移除本地保存的账户")
        return true
    }

    private suspend fun ensureAccountRow(accountId: String, passport: String): AccountEntity {
        // Keep existing account details and child caches when logging in again.
        return metadata.get(accountId) ?: AccountEntity(accountId, passport).also { metadata.upsert(it) }
    }

    /** 用户信息落库并同步会话；总容量包含永久与临期空间，昵称为空回退账号名。 */
    private suspend fun persistUserInfo(accountId: String, passport: String, info: UserInfoDto, revision: Long = metadataRevision.get()) {
        if ((manager.state.value as? SessionState.Ready)?.accountId != accountId) return
        val generation = manager.generation
        val entity = userInfoEntity(accountId, passport, info)
        metadata.upsert(entity)
        if (isCurrent(accountId, generation)) {
            manager.onMetadata(entity.displayName, entity.uid, accountId)
            lastSync = SyncStamp(generation, revision, clock(), successful = true)
        }
    }

    private fun userInfoEntity(accountId: String, passport: String, info: UserInfoDto) = AccountEntity(
            accountId = accountId,
            displayName = info.nickname.ifBlank { passport },
            uid = info.uid.toString(),
            usedBytes = info.spaceUsed,
            totalBytes = info.spaceTotal + info.spaceTemp,
            avatarUri = info.headImage.ifBlank { null },
            hasCloudInfo = true,
            maskedPassport = maskPassport(info.passport.takeIf { it > 0 }?.toString() ?: passport),
            vip = info.vip, vipLevel = info.vipLevel, vipExpire = info.vipExpire,
            permanentBytes = info.spaceTotal, temporaryBytes = info.spaceTemp,
            professionalTotalBytes = info.professionalSpacePermanent,
            professionalUsedBytes = info.professionalSpaceUsed,
            standardTotalBytes = info.standardSpacePermanent,
            standardUsedBytes = info.standardSpaceUsed,
            fileCount = info.fileCount, directTrafficBytes = info.directTraffic,
        )

    /** 健康路径下的落库：账户上下文优先取磁盘凭据，缺失时回退会话状态。 */
    private suspend fun persistUserInfoFromSession(ready: SessionState.Ready, generation: Long, revision: Long, info: UserInfoDto) {
        val credential = credentials.active()
        if (!isCurrent(ready.accountId, generation)) return
        val fallbackName = credential?.takeIf { it.accountId == ready.accountId }?.passport ?: ready.displayName
        persistUserInfo(ready.accountId, fallbackName, info, revision)
    }

    private fun isCurrent(accountId: String, generation: Long) = manager.generation == generation &&
        (manager.state.value as? SessionState.Ready)?.accountId == accountId

    private fun accountChanged() = ApiResult.NetworkError("账户已切换，已忽略旧账户的信息")

    /** 重登失败原样转成 getUserInfo 的结果类型（仅失败分支可达）。 */
    private fun asUserInfoFailure(relogin: ApiResult<String>): ApiResult<UserInfoDto> = when (relogin) {
        is ApiResult.ApiError -> ApiResult.ApiError(relogin.code, relogin.message)
        is ApiResult.NetworkError -> ApiResult.NetworkError(relogin.message)
        is ApiResult.ParseError -> ApiResult.ParseError(relogin.message)
        ApiResult.SessionExpired -> ApiResult.SessionExpired
        is ApiResult.Success -> error("重登成功结果不应进入失败分支")
    }

    private companion object {
        const val EMPTY_PASSPORT_MESSAGE = "请输入账号"
        const val NO_SAVED_CREDENTIAL_MESSAGE = "该账户没有保存的登录凭据"
    }

    // ---- M7 QR 登录----
    //
    // 协议事实：`.reference/123pan/src/app/api/session.py:453-623`（generate / poll / _qr_headers）
    // 与 `tasks/qr_login_tasks.py:80-130`（QRLoginVerifyTask 确认后编排）。

    /**
     * 生成扫码登录会话：`GET {LOGIN_BASE_URL}/api/user/qr-code/generate`
     * （session.py:465-509），成功返回 uniID + 页面地址 url；失败映射为用户可读文案。
     * 二维码请求不需要会话，也不改设备指纹（loginuuid 由 PanQrApiClient 直取持久化身份）。
     */
    suspend fun qrStart(): QrStartOutcome {
        val qr = qrApi ?: return QrStartOutcome.Failed(QR_UNAVAILABLE_MESSAGE)
        return when (val result = qr.qrGenerate()) {
            is ApiResult.Success -> {
                val data = result.data
                val page = data.url.toHttpUrlOrNull()?.takeIf { it.scheme == "https" && data.uniId.isNotBlank() }
                if (page == null) {
                    QrStartOutcome.Failed("二维码数据无效，请刷新重试")
                } else {
                    // qr_login_page.py:158-162: the page URL alone is not a login QR code.
                    val content = page.newBuilder()
                        .setQueryParameter("env", "production")
                        .setQueryParameter("uniID", data.uniId)
                        .setQueryParameter("source", "123pan")
                        .setQueryParameter("type", "login")
                        .build().toString()
                    QrStartOutcome.Started(data.uniId, content)
                }
            }
            else -> QrStartOutcome.Failed(AuthMessages.loginFailure(result))
        }
    }

    /**
     * 轮询扫码状态透传：登录页 ViewModel 以 2s 节拍驱动，仓库不持有轮询
     * 生命周期。失败以 [ApiResult] 原样上抛，停轮询还是继续等待由 ViewModel 决策。
     */
    suspend fun qrPoll(uniId: String): ApiResult<QrPollDto> {
        val qr = qrApi ?: return ApiResult.NetworkError(QR_UNAVAILABLE_MESSAGE)
        return qr.qrPoll(uniId)
    }

    /**
     * 扫码确认后的验证与落库，对应参考源 QRLoginVerifyTask（qr_login_tasks.py:88-130）：
     * token → `Bearer ` 前缀 → getUserInfo 验证 → 与 [login] 相同的落库链
     * （凭据 save + onLoginSuccess + persistUserInfo）。
     *
     * 与密码登录的两点差异（有意为之）：
     * - accountId 用 `"qr:<uid>"` 派生而非 passport：扫码没有账号名；token 又是每次登录都
     *   轮换的 JWT，不能当身份（否则同用户每次扫码都生成新账户行， 多账户去重失效，
     *   且 [relogin] 的 password 重登语义对 token 也不成立）；uid 才是服务端稳定标识，
     *   同一用户重复扫码合并到同一账户，元数据与云端缓存跨会话复用；`qr:` 前缀与 passport
     *   命名空间隔离防碰撞（SecureCredentialStore.accountIdFor 对输入做 lowercase，uid 为纯数字）。
     * - password 存空串：扫码登录没有可重登密码，会话过期时 [relogin] 必然失败并登出，
     *   用户重新扫码即可；不伪造可重登凭据（数据安全优先）。
     *
     * 验证只在该请求携带候选 token，不切换当前会话。账户元数据与凭据保存完成后才发布
     * Ready，保证文件缓存观察者启动时 accounts 外键已存在；验证失败保留原账户。
     */
    suspend fun qrVerify(token: String): QrVerifyOutcome {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return QrVerifyOutcome.Failed(QR_NO_CREDENTIAL_MESSAGE)  // qr_login_tasks.py:105-111
        // 等启动恢复结束再验证，避免恢复流程晚于验证完成时覆盖会话状态（与 [login] 同理）。
        manager.awaitRestored()
        val identity = identityStore.loadOrCreate()
        manager.updateProfile(identity)
        val generation = manager.generation
        val authorization = "Bearer $trimmed"
        val info = api.getUserInfo(authorization)
        if (manager.generation != generation) return QrVerifyOutcome.Failed("账户已切换，请重新扫码")
        if (info !is ApiResult.Success) {
            val userMessage = AuthMessages.loginFailure(info)
            logger.w(LogSource.AUTH, "扫码登录验证失败：$userMessage")
            return QrVerifyOutcome.Failed(userMessage)
        }
        val uid = info.data.uid
        val passport = QR_PASSPORT_PREFIX + uid
        val accountId = SecureCredentialStore.accountIdFor(passport)
        // 昵称为空回退 uid 字符串（qr_login_tasks.py:128：nickname or str(uid)）。
        val displayName = info.data.nickname.ifBlank { uid.toString() }
        metadata.upsert(userInfoEntity(accountId, passport, info.data))
        if (manager.generation != generation) return QrVerifyOutcome.Failed("账户已切换，请重新扫码")
        credentials.save(
            PlainCredential(
                accountId = accountId,
                passport = passport,
                password = "",
                authorization = authorization,
                identity = identity,
            ),
        )
        manager.onLoginSuccess(accountId, displayName = displayName, uid = uid.toString(), authorization)
        lastSync = SyncStamp(manager.generation, metadataRevision.get(), clock(), successful = true)
        logger.i(LogSource.AUTH, "扫码登录成功")
        return QrVerifyOutcome.Accepted(displayName)
    }

}

// ---- M7 QR 登录：结果契约----

sealed interface QrStartOutcome {
    data class Started(val uniId: String, val qrUrl: String) : QrStartOutcome
    data class Failed(val userMessage: String) : QrStartOutcome
}

sealed interface QrVerifyOutcome {
    data class Accepted(val displayName: String) : QrVerifyOutcome
    data class Failed(val userMessage: String) : QrVerifyOutcome
}

// 文件级私有常量（不入 companion，保持对既有区块零改动）。
private const val QR_PASSPORT_PREFIX = "qr:"
private const val ACCOUNT_STORAGE_MESSAGE = "无法保存账户信息，请稍后重试"
private const val QR_NO_CREDENTIAL_MESSAGE = "登录失败：未获取到凭证"
private const val QR_UNAVAILABLE_MESSAGE = "扫码登录暂不可用，请使用账号密码登录"

// ---- M7 多账户----

/** 账户切换结果：失败只携带用户可读文案，不透出异常细节。 */
sealed interface SwitchOutcome {
    data class Switched(val displayName: String) : SwitchOutcome
    data class Failed(val userMessage: String) : SwitchOutcome
}
