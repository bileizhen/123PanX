package io.github.bileizhen.pan123x.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SecureCredentialStore
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.AccountEntity
import io.github.bileizhen.pan123x.data.auth.AuthRepository
import io.github.bileizhen.pan123x.data.auth.SwitchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 账户列表行：accounts 表行与凭据状态合并后的展示模型。 */
data class AccountRow(
    val accountId: String,
    val displayName: String,
    val uid: String,
    val avatarUri: String?,
    /** 本机保存了该账户的登录凭据：为 true 时可一键切换。 */
    val hasCredential: Boolean,
)

/** 账户管理页状态：列表 + 当前活跃账户 + busy 防重 + 一次性提示。 */
data class AccountsUiState(
    val rows: List<AccountRow> = emptyList(),
    val activeAccountId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * 多账户管理（M7）：列表 = [SecureCredentialStore.accountIds] 与 accounts 表
 * 观测流合并——有凭据的行排前并带"可切换"标记，无凭据的行只可移除；有凭据但元数据行
 * 尚未落库的账户以占位名补进列表（切换成功后由仓库的恢复链修复元数据）。
 * 切换 / 移除直接委托 [AuthRepository]，二次确认由 Screen 层负责。
 */
class AccountsViewModel(
    private val auth: AuthRepository,
    private val credentials: SecureCredentialStore,
    accounts: Flow<List<AccountEntity>>,
    manager: AccountManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            // accounts 表行删改、会话切换都会触发重算并重新枚举凭据集合，
            // 因此登录 / 移除 / 切换后列表自动跟随，无需手动刷新。
            combine(accounts, manager.state) { rows, session -> rows to session }
                .collect { (rows, session) ->
                    val credentialIds = runCatching { credentials.accountIds() }
                        // 凭据枚举失败按"都不可切换"降级展示，列表本身仍然可用。
                        .getOrDefault(emptySet())
                    val items = buildList {
                        rows.forEach { entity ->
                            add(
                                AccountRow(
                                    accountId = entity.accountId,
                                    displayName = entity.displayName,
                                    uid = entity.uid,
                                    avatarUri = entity.avatarUri,
                                    hasCredential = entity.accountId in credentialIds,
                                ),
                            )
                        }
                        credentialIds.filterNot { id -> rows.any { it.accountId == id } }.forEach { id ->
                            // 有凭据但元数据行缺失（如登录时用户信息失败）：以占位名补进列表，
                            // 切换过去后仓库的恢复链会补齐 accounts 行。
                            add(AccountRow(accountId = id, displayName = ORPHAN_DISPLAY_NAME, uid = "", avatarUri = null, hasCredential = true))
                        }
                    }.sortedWith(
                        compareByDescending<AccountRow> { it.hasCredential }.thenBy { it.displayName },
                    )
                    mutableState.update {
                        it.copy(rows = items, activeAccountId = (session as? SessionState.Ready)?.accountId)
                    }
                }
        }
    }

    /** 切换到已保存凭据的账户；成功后活跃徽标随会话状态自动移动。 */
    fun switchTo(accountId: String) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                when (val outcome = auth.switchTo(accountId)) {
                    is SwitchOutcome.Switched -> null
                    is SwitchOutcome.Failed -> outcome.userMessage
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SWITCH_FAILED_MESSAGE
            }
            mutableState.update { it.copy(busy = false, message = message) }
        }
    }

    /** 移除本地已保存的账户；行与凭据变化由订阅流自动反映到列表。 */
    fun removeAccount(accountId: String) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                if (auth.removeAccount(accountId)) null else REMOVE_MISSING_MESSAGE
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                REMOVE_FAILED_MESSAGE
            }
            mutableState.update { it.copy(busy = false, message = message) }
        }
    }

    /** 一次性提示消费：Screen 展示后调用，避免过期文案常驻。 */
    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    private companion object {
        const val ORPHAN_DISPLAY_NAME = "未命名账户"
        const val SWITCH_FAILED_MESSAGE = "切换账户失败，请稍后重试"
        const val REMOVE_MISSING_MESSAGE = "该账户不存在，可能已被移除"
        const val REMOVE_FAILED_MESSAGE = "移除账户失败，请稍后重试"
    }
}
