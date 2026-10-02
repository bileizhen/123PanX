package io.github.bileizhen.pan123x.core.account

import io.github.bileizhen.pan123x.core.network.AuthorizationProvider
import io.github.bileizhen.pan123x.core.network.DeviceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话内存状态：显式区分启动恢复中 / 未登录 / 就绪，
 * UI 只消费本状态，不解析 token 或凭据。
 */
sealed interface SessionState {
    /** 启动恢复中的初始态，restoreSession 结束后必定离开本状态。 */
    data object Restoring : SessionState

    data object LoggedOut : SessionState

    data class Ready(val accountId: String, val displayName: String, val uid: String) : SessionState
}

/**
 * 会话单点：内存中的 authorization、设备指纹与 [SessionState]。
 *
 * [AuthInterceptor][io.github.bileizhen.pan123x.core.network.AuthInterceptor] 与
 * [DeviceInterceptor][io.github.bileizhen.pan123x.core.network.DeviceInterceptor]
 * 分别通过 [current] 与 [deviceProfile] 读取请求头素材，因此写入顺序约定为
 * "先写 token / 指纹，再发布状态"，保证状态可见时请求头一定已就绪。线程安全依靠
 * volatile 字段与 StateFlow 的原子写，不加锁。
 */
class AccountManager(
    initialProfile: DeviceProfile = DeviceProfile.generate(),
) : AuthorizationProvider {

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private val sessionGeneration = AtomicLong()
    /** Changes only when credentials/session change, not when display metadata changes. */
    val generation: Long get() = sessionGeneration.get()

    @Volatile
    private var authorization: String? = null

    @Volatile
    private var profile: DeviceProfile = initialProfile

    /** AuthorizationProvider：返回完整 authorization 值（"Bearer xxx"），未登录为 null。 */
    override fun current(): String? = authorization

    fun deviceProfile(): DeviceProfile = profile

    /** 身份恢复后切换请求指纹：仅映射 osversion / devicetype / loginuuid 三字段，其余指纹字段保留。 */
    fun updateProfile(identity: DeviceIdentity) {
        profile = profile.copy(
            osVersion = identity.osVersion,
            deviceType = identity.deviceType,
            loginUuid = identity.loginUuid,
        )
    }

    /** 登录或重登成功：先写入 token 再置 Ready，此时起请求自动携带新 authorization。 */
    @Synchronized
    fun onLoginSuccess(accountId: String, displayName: String, uid: String, authorization: String) {
        sessionGeneration.incrementAndGet()
        this.authorization = authorization
        mutableState.value = SessionState.Ready(accountId, displayName, uid)
    }

    @Synchronized
    fun renewIfCurrent(expectedGeneration: Long, authorization: String): Boolean {
        val ready = mutableState.value as? SessionState.Ready ?: return false
        if (generation != expectedGeneration) return false
        onLoginSuccess(ready.accountId, ready.displayName, ready.uid, authorization)
        return true
    }

    @Synchronized
    fun logoutIfCurrent(expectedGeneration: Long) {
        if (generation == expectedGeneration) onLogout()
    }

    /** 用户信息补全后更新 Ready；非 Ready 状态下是安全的 no-op。 */
    fun onMetadata(displayName: String, uid: String, accountId: String? = null) {
        mutableState.update { current ->
            val ready = current as? SessionState.Ready
            if (ready != null && (accountId == null || ready.accountId == accountId)) ready.copy(displayName = displayName, uid = uid)
            else current
        }
    }

    /** 置 LoggedOut 并清 token；设备指纹保留，供下一次登录复用同一伪装身份。 */
    @Synchronized
    fun onLogout() {
        sessionGeneration.incrementAndGet()
        authorization = null
        mutableState.value = SessionState.LoggedOut
    }

    /** 挂起直到启动恢复结束（state != Restoring）并返回恢复后的状态；已完成时立即返回。 */
    suspend fun awaitRestored(): SessionState = state.first { it != SessionState.Restoring }
}
