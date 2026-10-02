package io.github.bileizhen.pan123x.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.data.settings.AppSettings
import io.github.bileizhen.pan123x.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(val settings: AppSettings = AppSettings(), val error: String? = null,
    val proxy: io.github.bileizhen.pan123x.core.network.ProxyConfig = io.github.bileizhen.pan123x.core.network.ProxyConfig(),
    val busy: Boolean = false, val message: String? = null, val proxySavedRevision: Long = 0)

private data class SettingsActionState(val error: String? = null, val busy: Boolean = false, val message: String? = null, val proxyRevision: Long = 0)

class SettingsViewModel(private val repository: SettingsRepository, private val logger: AppLogger,
    private val proxies: io.github.bileizhen.pan123x.data.settings.ProxySettingsRepository? = null,
    private val maintenance: io.github.bileizhen.pan123x.data.settings.CacheMaintenanceRepository? = null,
) : ViewModel() {
    private val actions = MutableStateFlow(SettingsActionState())
    val uiState: StateFlow<SettingsUiState> = combine(repository.state, actions,
        proxies?.state ?: kotlinx.coroutines.flow.flowOf(io.github.bileizhen.pan123x.core.network.ProxyConfig()),
        proxies?.error ?: kotlinx.coroutines.flow.flowOf(null)) { settings, action, proxy, proxyError ->
        SettingsUiState(settings, action.error ?: proxyError, proxy, action.busy, action.message, action.proxyRevision)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(repository.state.value))

    fun edit(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                repository.edit(change)
                actions.value = actions.value.copy(error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actions.value = actions.value.copy(error = "无法保存设置，请稍后重试")
                logger.e(LogSource.APP, "无法写入外观设置")
            }
        }
    }

    fun saveProxy(config: io.github.bileizhen.pan123x.core.network.ProxyConfig) {
        config.validationError()?.let { actions.value = actions.value.copy(error = it); return }
        runAction {
            val repository = proxies ?: throw IllegalStateException("代理配置不可用")
            repository.save(config)
            actions.value = actions.value.copy(proxyRevision = actions.value.proxyRevision + 1)
            "代理设置已保存，新请求将使用所选代理"
        }
    }

    fun clearCache() = runAction {
        val freed = requireNotNull(maintenance).clearCache()
        "已清理预览与图片缓存，释放 ${io.github.bileizhen.pan123x.ui.util.formatBytes(freed)}"
    }
    fun refreshFileLists() = runAction { requireNotNull(maintenance).refreshFileLists(); "文件列表已失效，浏览时会重新同步" }
    fun resetFileLists() = runAction { requireNotNull(maintenance).resetFileLists(); "当前账户的文件缓存已清除，将重新同步云端列表" }
    fun dismissMessage() { actions.value = actions.value.copy(message = null, error = null) }

    private fun runAction(block: suspend () -> String) {
        if (actions.value.busy) return
        actions.value = actions.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try {
                val message = block()
                actions.value = actions.value.copy(message = message)
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                actions.value = actions.value.copy(error = if (error is IllegalStateException) error.message else "操作失败，请检查存储权限后重试")
                logger.w(LogSource.APP, "设置操作失败：${error.javaClass.simpleName}")
            } finally { actions.value = actions.value.copy(busy = false) }
        }
    }
}
