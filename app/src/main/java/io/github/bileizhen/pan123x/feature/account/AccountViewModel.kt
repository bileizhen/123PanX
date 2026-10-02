package io.github.bileizhen.pan123x.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.auth.AuthMessages
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.LoginDeviceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 我的页状态：会话分支 + 当前账户的展示元数据。
 * session 是唯一身份事实来源；account 仅用于空间用量等增强信息，缺失不影响登录态展示。
 */
data class AccountUiState(
    val session: SessionState = SessionState.Restoring,
    val account: AccountEntity? = null,
    val refreshing: Boolean = false,
    val message: String? = null,
    val devices: List<LoginDeviceDto> = emptyList(),
    val devicesLoading: Boolean = false,
    val devicesLoaded: Boolean = false,
    val devicesError: String? = null,
)

class AccountViewModel(
    private val auth: AuthRepository,
    manager: AccountManager,
    accounts: Flow<List<AccountEntity>>,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = mutableState.asStateFlow()
    private var metadataJob: Job? = null
    private var devicesJob: Job? = null
    private var devicesLoadedAt = 0L

    init {
        viewModelScope.launch {
            combine(manager.state, accounts) { session, accounts ->
                session to (session as? SessionState.Ready)?.let { ready ->
                    accounts.firstOrNull { it.accountId == ready.accountId }
                }
            }.collect { (session, account) ->
                val previousId = (uiState.value.session as? SessionState.Ready)?.accountId
                val nextId = (session as? SessionState.Ready)?.accountId
                if (previousId != nextId) {
                    metadataJob?.cancel()
                    devicesJob?.cancel()
                    devicesLoadedAt = 0L
                    mutableState.value = AccountUiState(session = session, account = account)
                } else mutableState.update { it.copy(session = session, account = account) }
            }
        }
    }

    /** Entering this page syncs in the background; no manual metadata refresh action. */
    fun onVisible() {
        val ready = uiState.value.session as? SessionState.Ready ?: return
        if (uiState.value.refreshing) return
        mutableState.update { it.copy(refreshing = true, message = null) }
        metadataJob = viewModelScope.launch {
            val message = try {
                val result = auth.syncUserInfoIfStale()
                result?.takeUnless { it is ApiResult.Success }?.let(AuthMessages::loginFailure)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                REFRESH_FAILED
            }
            if ((uiState.value.session as? SessionState.Ready)?.accountId == ready.accountId) {
                mutableState.update { it.copy(refreshing = false, message = message) }
            }
        }
    }

    /** Cloud details load automatically; keep successful devices during transient failures. */
    fun onCloudInfoVisible(forceDevices: Boolean = false) {
        onVisible()
        val ready = uiState.value.session as? SessionState.Ready ?: return
        if (uiState.value.devicesLoading) return
        val now = System.nanoTime() / 1_000_000L
        if (!forceDevices && uiState.value.devicesLoaded && now - devicesLoadedAt in 0 until 30_000L) return
        mutableState.update { it.copy(devicesLoading = true, devicesError = null) }
        devicesJob = viewModelScope.launch {
            val result = try {
                auth.getLoginDevices()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ApiResult.NetworkError("登录设备信息获取失败")
            }
            if ((uiState.value.session as? SessionState.Ready)?.accountId != ready.accountId) return@launch
            when (result) {
                is ApiResult.Success -> {
                    devicesLoadedAt = System.nanoTime() / 1_000_000L
                    mutableState.update { it.copy(devices = result.data, devicesLoading = false, devicesLoaded = true, devicesError = null) }
                }
                else -> mutableState.update { it.copy(devicesLoading = false, devicesError = AuthMessages.loginFailure(result)) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                auth.logout()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 登出主要是本地清理，但 DataStore IO 仍可能失败：提示用户重试，
                // 会话状态按仓库实际发布的结果渲染，不在这里猜测成功与否。
                mutableState.update { it.copy(message = "退出登录失败，请重试") }
            }
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    private companion object {
        const val REFRESH_FAILED = "用户信息获取失败，请稍后重试"
    }
}
