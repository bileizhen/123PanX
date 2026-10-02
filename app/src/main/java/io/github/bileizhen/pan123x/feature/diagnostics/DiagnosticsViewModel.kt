// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.data.diagnostics.DiagnosticsSnapshot
import io.github.bileizhen.pan123x.ui.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 诊断页 UI 状态（显式 Loading / Content / Error，不用布尔位互相暗示）。
 *
 * [snapshot] == null && [loading] = 首次加载中；snapshot != null = 内容态（刷新期间保留旧快照）。
 * [message] 是一次性提示（"已释放 X" / 失败文案）：UI 展示后调用 [DiagnosticsViewModel.consumeMessage]
 * 清除，避免提示常驻；新事件会整体覆盖旧提示。
 */
data class DiagnosticsUiState(
    val snapshot: DiagnosticsSnapshot? = null,
    val loading: Boolean = false,
    val clearing: Boolean = false,
    val message: String? = null,
)

/**
 * 诊断页 ViewModel。
 *
 * 为什么注入 suspend lambda 而不是 DiagnosticsRepository：仓库是具体类且携带 android Context /
 * File 依赖，而 VM 状态机必须在纯 JVM 单测中验证（PreviewViewModel 的 resolve
 * 注入先例）。两个 lambda 即仓库仅有的两个入口：进入页面自动 [refresh] 一次，「刷新」重取；
 * [clearPreviewCache] 清预览缓存后重取快照并提示释放量（不允许 UI 假成功）。
 *
 * @param snapshotProvider 取诊断快照；抛异常视为加载失败（保留旧快照 + 一次性错误提示）。
 * @param clearPreview 清预览缓存，返回释放字节数；抛异常视为清除失败。
 */
class DiagnosticsViewModel(
    private val snapshotProvider: suspend () -> DiagnosticsSnapshot,
    private val clearPreview: suspend () -> Long,
) : ViewModel() {

    private val state = MutableStateFlow(DiagnosticsUiState())

    val uiState: StateFlow<DiagnosticsUiState> = state.asStateFlow()

    init {
        // 进入页面即 snapshot 一次；后续由「刷新」按钮显式触发。
        refresh()
    }

    /** 重新采集快照；加载中/清除中忽略重复触发。失败保留旧快照并给一次性提示。 */
    fun refresh() {
        val current = state.value
        if (current.loading || current.clearing) return
        state.value = current.copy(loading = true)
        viewModelScope.launch {
            try {
                val snapshot = snapshotProvider()
                state.value = state.value.copy(loading = false, snapshot = snapshot)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                state.value = state.value.copy(loading = false, message = LOAD_FAILED)
            }
        }
    }

    /**
     * 清除预览缓存（UI 已二次确认）。成功后立即重取快照让缓存字段归零，并提示"已释放 X"；
     * 重取快照失败不吞掉释放反馈——保留旧快照、提示照常给出。
     */
    fun clearPreviewCache() {
        val current = state.value
        if (current.loading || current.clearing) return
        state.value = current.copy(clearing = true)
        viewModelScope.launch {
            try {
                val freed = clearPreview()
                val refreshed = runCatching { snapshotProvider() }.getOrNull()
                state.value = state.value.copy(
                    clearing = false,
                    snapshot = refreshed ?: state.value.snapshot,
                    message = "已释放 ${formatBytes(freed)}",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                state.value = state.value.copy(clearing = false, message = CLEAR_FAILED)
            }
        }
    }

    /** UI 展示完一次性提示后调用；此后 message 为 null 直到下一个事件。 */
    fun consumeMessage() {
        state.value = state.value.copy(message = null)
    }

    private companion object {
        const val LOAD_FAILED = "诊断信息加载失败，请稍后重试"
        const val CLEAR_FAILED = "清除预览缓存失败，请稍后重试"
    }
}
