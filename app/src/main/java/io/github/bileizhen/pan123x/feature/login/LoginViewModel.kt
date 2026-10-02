package io.github.bileizhen.pan123x.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.PanQrApiClient
import io.github.bileizhen.pan123x.core.network.QrPollDto
import io.github.bileizhen.pan123x.data.auth.AuthMessages
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.auth.LoginOutcome
import io.github.bileizhen.pan123x.data.auth.QrStartOutcome
import io.github.bileizhen.pan123x.data.auth.QrVerifyOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 登录页顶部 Tab 下标（M7 双 Tab）。 */
const val LOGIN_TAB_PASSWORD = 0
const val LOGIN_TAB_QR = 1

/**
 * 扫码登录状态机：`Idle→Generating→Ready(url)→轮询(2s)→Verifying→Success/Failed`；
 * 拒绝 / 过期 / 失败停在终态，由用户「刷新二维码」重新生成。二维码位图仅依赖 [qrUrl]。
 */
enum class QrPhase { IDLE, GENERATING, READY, VERIFYING, SUCCESS, REJECTED, EXPIRED, FAILED }

/**
 * 登录表单状态：
 * - 失败只携带仓库给出的用户可读文案，不透出 code / 异常细节；
 * - [loggedIn] 是单次"登录成功"信号，由 UI 用 LaunchedEffect 消费后返回上一页；
 * - 密码 Tab 字段与扫码 Tab 字段互不干扰（对 C 的同款约束）。
 */
data class LoginUiState(
    val passport: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
    val selectedTab: Int = LOGIN_TAB_PASSWORD,
    val qrPhase: QrPhase = QrPhase.IDLE,
    /** 二维码内容（服务端下发的 url）；null = 当前无码（生成中 / 失败 / 未进入）。 */
    val qrUrl: String? = null,
    /** 对端已扫码待确认（loginStatus==1）。 */
    val qrScanned: Boolean = false,
    /** 扫码流程的用户可读提示（失败原因 / 微信拒绝文案）。 */
    val qrMessage: String? = null,
)

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {

    private val mutableState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = mutableState.asStateFlow()

    /** 扫码轮询任务：切回密码 Tab / 重新生成 / 页面销毁（viewModelScope 取消）即停。 */
    private var qrPollJob: Job? = null
    private var qrStartJob: Job? = null

    /** 输入变化时清掉上一轮错误，避免旧报错压在新输入上。 */
    fun onPassportChange(value: String) = mutableState.update { it.copy(passport = value, error = null) }

    fun onPasswordChange(value: String) = mutableState.update { it.copy(password = value, error = null) }

    fun onTogglePassword() = mutableState.update { it.copy(showPassword = !it.showPassword) }

    fun consumeError() = mutableState.update { it.copy(error = null) }

    fun submit() {
        val current = mutableState.value
        // submitting / 已成功期间防重复提交；空输入由按钮 disabled 兜底，这里再挡一层。
        if (current.submitting || current.loggedIn) return
        if (current.passport.isBlank() || current.password.isEmpty()) return
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                when (val outcome = auth.login(current.passport.trim(), current.password)) {
                    is LoginOutcome.Failure -> mutableState.update { it.copy(submitting = false, error = outcome.userMessage) }
                    // 成功后不在这里清表单：会话状态由 AccountManager 单点维护，
                    // UI 消费 loggedIn 返回上一页，表单随页面销毁自然丢弃。
                    LoginOutcome.Success -> mutableState.update { it.copy(submitting = false, loggedIn = true) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 仓库已把可预期失败转成 LoginOutcome，这里只兜底真正的意外异常。
                mutableState.update { it.copy(submitting = false, error = "登录失败，请稍后重试") }
            }
        }
    }

    // ---- M7 扫码登录----

    /** 切换 Tab：进入扫码页即开始生成并轮询，离开即停（轮询生命周期跟随页面可见性）。 */
    fun selectTab(index: Int) {
        if (index == mutableState.value.selectedTab) return
        mutableState.update { it.copy(selectedTab = index) }
        if (index == LOGIN_TAB_QR) startQr() else stopQr()
    }

    /** 「刷新二维码」入口：取消旧轮询，重新生成（拒绝 / 过期 / 失败后复用同一入口）。 */
    fun refreshQr() = startQr()

    /** 离开扫码页 / 重置扫码状态：取消轮询并复位，避免后台继续打轮询接口。 */
    fun stopQr() {
        qrStartJob?.cancel()
        qrStartJob = null
        qrPollJob?.cancel()
        qrPollJob = null
        mutableState.update { it.copy(qrPhase = QrPhase.IDLE, qrUrl = null, qrScanned = false, qrMessage = null) }
    }

    private fun startQr() {
        stopQr()
        mutableState.update { it.copy(qrPhase = QrPhase.GENERATING) }
        qrStartJob = viewModelScope.launch {
            try {
                when (val outcome = auth.qrStart()) {
                    is QrStartOutcome.Started -> {
                        mutableState.update { it.copy(qrPhase = QrPhase.READY, qrUrl = outcome.qrUrl, qrScanned = false) }
                        startPolling(outcome.uniId)
                    }
                    is QrStartOutcome.Failed -> failQr(outcome.userMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 仓库已把可预期失败折进 QrStartOutcome，这里只兜底真正的意外异常。
                failQr(QR_UNEXPECTED_MESSAGE)
            }
        }
    }

    /**
     * 轮询节拍：Ready 后每 2s 一次；页面销毁 / 切 Tab / 重新生成时
     * viewModelScope 或 qrPollJob 取消，delay 处立即退出，不会多发请求。
     */
    private fun startPolling(uniId: String) {
        qrPollJob = viewModelScope.launch {
            while (isActive) {
                delay(QR_POLL_INTERVAL_MS)
                if (!pollOnce(uniId)) return@launch
            }
        }
    }

    /** 单次轮询；返回 false 表示已到终态（拒绝 / 过期 / 失败 / 进入验证），轮询停止。 */
    private suspend fun pollOnce(uniId: String): Boolean {
        val result = try {
            auth.qrPoll(uniId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failQr(QR_UNEXPECTED_MESSAGE)
            return false
        }
        if (result !is ApiResult.Success) {
            // 参考源 QRPollTask 对轮询异常即报错停止（qr_login_tasks.py:70-73），此处一致：
            // 停止轮询给出可读文案，由用户刷新重试，不做静默自动重试。
            failQr(AuthMessages.loginFailure(result))
            return false
        }
        return when (result.data.loginStatus) {
            PanQrApiClient.LOGIN_STATUS_SCANNED -> {
                mutableState.update { it.copy(qrScanned = true) }
                true
            }
            PanQrApiClient.LOGIN_STATUS_REJECTED -> {
                mutableState.update { it.copy(qrPhase = QrPhase.REJECTED) }
                false
            }
            PanQrApiClient.LOGIN_STATUS_EXPIRED -> {
                mutableState.update { it.copy(qrPhase = QrPhase.EXPIRED) }
                false
            }
            PanQrApiClient.LOGIN_STATUS_CONFIRMED -> {
                onConfirmed(result.data)
                false
            }
            // 0=等待扫码；-1 等未知值按参考源默认（session.py:574 缺省 -1）继续等待。
            else -> true
        }
    }

    /**
     * 确认分支（session.py:545-559 + qr_login_tasks.py:88-130）：有 token 走仓库验证落库；
     * 无 token 且来源是微信 → 直接拒绝。这里不发 `qr_wx_code`：参考源 qr_login_tasks.py:91-102
     * 取到 wxCode 后仍然只提示"改用 123云盘 App"，该调用对拒绝流程无收益，省一次注定失败
     * 的往返。无 token 且非微信按参考源 :105-111 报"未获取到凭证"。
     */
    private fun onConfirmed(dto: QrPollDto) {
        when {
            dto.token.isNotBlank() -> verifyQr(dto.token)
            dto.scanPlatform == PanQrApiClient.SCAN_PLATFORM_WECHAT -> failQr(QR_WECHAT_UNSUPPORTED_MESSAGE)
            else -> failQr(QR_NO_CREDENTIAL_MESSAGE)
        }
    }

    private fun verifyQr(token: String) {
        mutableState.update { it.copy(qrPhase = QrPhase.VERIFYING) }
        viewModelScope.launch {
            try {
                when (val outcome = auth.qrVerify(token)) {
                    is QrVerifyOutcome.Accepted ->
                        // 会话就绪由 AccountManager 单点维护；loggedIn 驱动 UI 返回上一页。
                        mutableState.update { it.copy(qrPhase = QrPhase.SUCCESS, loggedIn = true) }
                    is QrVerifyOutcome.Failed -> failQr(outcome.userMessage)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failQr(QR_UNEXPECTED_MESSAGE)
            }
        }
    }

    private fun failQr(message: String) {
        qrPollJob?.cancel()
        qrPollJob = null
        mutableState.update { it.copy(qrPhase = QrPhase.FAILED, qrMessage = message) }
    }

    class Factory(private val auth: AuthRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LoginViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return LoginViewModel(auth) as T
        }
    }

    private companion object {
        /** 轮询间隔 2s（冻结值，参考源 QRPollTask 同节拍）。 */
        const val QR_POLL_INTERVAL_MS = 2_000L
    }
}

// 文件级私有文案（全部为用户可读中文，不含 code / 异常细节）。
private const val QR_WECHAT_UNSUPPORTED_MESSAGE = "微信登录暂不支持，请使用 123云盘 App 扫码"
private const val QR_NO_CREDENTIAL_MESSAGE = "登录失败：未获取到凭证"
private const val QR_UNEXPECTED_MESSAGE = "登录失败，请稍后重试"
