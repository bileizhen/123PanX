package io.github.bileizhen.pan123x.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.data.settings.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val received: Long, val total: Long) : UpdateDownloadState
    data class Ready(val file: File) : UpdateDownloadState
    data class Failed(val message: String) : UpdateDownloadState
}
data class UpdateUiState(
    val checking: Boolean = false,
    val result: UpdateResult? = null,
    val visible: Boolean = false,
    val source: UpdateSource = UpdateSource.GITHUB,
    val download: UpdateDownloadState = UpdateDownloadState.Idle,
    val installing: Boolean = false,
    val permissionRequired: Boolean = false,
    val installMessage: String? = null,
)
class UpdateViewModel(private val repository: UpdateChecker, private val downloader: UpdateDownload, private val installer: UpdateInstall) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var startupChecked = false
    private var downloadJob: Job? = null

    fun checkAtStartup(enabled: Boolean) {
        if (startupChecked) return
        startupChecked = true
        if (enabled) check(automatic = true)
    }

    fun check(automatic: Boolean = false) {
        if (!automatic) mutableState.update { it.copy(visible = true) }
        if (mutableState.value.checking) return
        if (mutableState.value.download is UpdateDownloadState.Downloading || mutableState.value.installing) return
        mutableState.update { it.copy(checking = true, installMessage = null) }
        viewModelScope.launch {
            try {
                val result = repository.check()
                mutableState.update {
                    val same = (it.result as? UpdateResult.Available)?.release?.sha256 == (result as? UpdateResult.Available)?.release?.sha256
                    it.copy(result = result, visible = it.visible || result is UpdateResult.Available,
                        download = if (same) it.download else UpdateDownloadState.Idle, permissionRequired = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(result = UpdateResult.Failed("检查失败，请稍后重试")) } }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun selectSource(source: UpdateSource) {
        if (state.value.download is UpdateDownloadState.Downloading || state.value.download is UpdateDownloadState.Ready || state.value.installing) return
        mutableState.update { it.copy(source = source) }
    }

    fun downloadUpdate() {
        val release = (state.value.result as? UpdateResult.Available)?.release ?: return
        if (state.value.checking || state.value.installing || downloadJob?.isActive == true || state.value.download is UpdateDownloadState.Ready) return
        val source = state.value.source
        mutableState.update { it.copy(download = UpdateDownloadState.Downloading(0, release.size), installMessage = null) }
        downloadJob = viewModelScope.launch {
            try {
                val file = downloader.download(release, source) { received, total ->
                    mutableState.update { it.copy(download = UpdateDownloadState.Downloading(received, total)) }
                }
                mutableState.update { it.copy(download = UpdateDownloadState.Ready(file)) }
                installDownloaded()
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(download = UpdateDownloadState.Idle) }
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(download = UpdateDownloadState.Failed("下载或校验失败，请重试或切换下载源")) }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    fun installDownloaded() {
        val file = (state.value.download as? UpdateDownloadState.Ready)?.file ?: return
        val release = (state.value.result as? UpdateResult.Available)?.release ?: return
        if (state.value.installing) return
        mutableState.update { it.copy(installing = true, installMessage = null) }
        viewModelScope.launch {
            try {
                when (installer.request(file, release)) {
                    UpdateInstallResult.STARTED -> mutableState.update { it.copy(visible = false, permissionRequired = false) }
                    UpdateInstallResult.PERMISSION_REQUIRED -> mutableState.update { it.copy(permissionRequired = true, installMessage = "请允许安装未知应用；返回后会继续请求系统安装") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(permissionRequired = false, installMessage = error.message ?: "无法请求系统安装，请重试") } }
            finally { mutableState.update { it.copy(installing = false) } }
        }
    }

    fun onResume() {
        if (state.value.permissionRequired && installer.canInstall()) installDownloaded()
    }

    fun dismiss() {
        if (!state.value.checking && state.value.download !is UpdateDownloadState.Downloading && !state.value.installing)
            mutableState.update { it.copy(visible = false) }
    }
}
