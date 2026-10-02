// 离线下载 Tab 状态容器（协议真源 .reference/123pan service/offline_service.py:51-98）。
package io.github.bileizhen.pan123x.feature.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.network.OfflineResolvedItem
import io.github.bileizhen.pan123x.data.offline.OfflineRepositoryApi
import io.github.bileizhen.pan123x.data.offline.OfflineState
import io.github.bileizhen.pan123x.data.offline.OfflineSubmitSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch



/** 离线 Tab 的页面状态（显式区分空 / 加载 / 错误，错误均带用户可读文案）。 */
data class OfflineUiState(
    val input: String = "",
    /** 最近一次解析的资源卡（Submitting/Done 期间沿用 lastItems 展示，避免卡片闪没）。 */
    val items: List<OfflineResolvedItem> = emptyList(),
    /** resourceId → 已勾选 fileId 集合；默认全选，仅对带文件清单（files.size > 1）的资源存在。 */
    val selection: Map<Long, Set<Long>> = emptyMap(),
    val resolving: Boolean = false,
    val submitting: Boolean = false,
    /** 本地校验提示（输入为空 / 无可提交资源）。 */
    val notice: String? = null,
    /** 仓库 Error 态的用户可读文案（解析失败等）。 */
    val error: String? = null,
    /** 提交完成（Done）的结果汇总文案，展示 [OfflineViewModel.RESULT_CLEAR_MILLIS] 后自动清空回 Idle。 */
    val resultMessage: String? = null,
    val canSubmit: Boolean = false,
) {
    /** 解析 / 提交任一在途即为 busy；UI 据此禁用「解析」「提交」防重复触发。 */
    val busy: Boolean get() = resolving || submitting
}

/**
 * 离线下载 Tab 的 ViewModel。
 *
 * 职责边界：仓库（B）负责 resolve/submit 的真实网络与状态机推进；本 VM 只做
 * 输入持有、勾选维护、提交 selections 组装、busy 防重与 Done 后的自动复位，
 * 不触碰 OkHttp / DTO 解析。纯 JVM 可测（注入 [OfflineRepositoryApi] 替身）。
 *
 * 提交组装语义（与 B 契约"空 = 整个资源"对齐，见 [buildSelections]）：
 * 全选 → 空列表（整个资源）；部分勾选 → 仅选中 fileId；全部取消勾选 → 跳过该资源。
 */
class OfflineViewModel(
    private val repository: OfflineRepositoryApi,
    /** Done 结果提示的展示时长；测试注入短时长配合虚拟调度器验证自动复位。 */
    private val resultClearMillis: Long = RESULT_CLEAR_MILLIS,
) : ViewModel() {

    /** VM 侧本地状态：输入、勾选、上次解析结果（Submitting/Done 期间保持展示）。 */
    private data class Local(
        val input: String = "",
        val selection: Map<Long, Set<Long>> = emptyMap(),
        val lastItems: List<OfflineResolvedItem> = emptyList(),
        val notice: String? = null,
    )

    private val local = MutableStateFlow(Local())

    /** Done 自动复位任务；用户在窗口期内重新解析 / 提交时取消，避免复位打断新动作。 */
    private var autoResetJob: Job? = null

    /**
     * 防重闸门：动作 launch 前同步置位，仓库调用返回（状态已落到终态）后复位。
     * 与 [busyNow]（仓库状态判忙）双保险，覆盖仓库置 Resolving/Submitting 前的双击窗口。
     */
    private var inFlight = false

    /** 已做过勾选初始化的 Resolved 列表哈希：同一份结果重放时不清掉用户的勾选改动。 */
    private var syncedItemsHash = 0

    val uiState: StateFlow<OfflineUiState> = combine(repository.state, local) { repo, local ->
        val busy = repo is OfflineState.Resolving || repo is OfflineState.Submitting
        val items = (repo as? OfflineState.Resolved)?.items ?: local.lastItems
        OfflineUiState(
            input = local.input,
            items = items,
            selection = local.selection,
            resolving = repo is OfflineState.Resolving,
            submitting = repo is OfflineState.Submitting,
            notice = local.notice,
            error = (repo as? OfflineState.Error)?.userMessage,
            resultMessage = (repo as? OfflineState.Done)?.let { summarize(it.summary) },
            canSubmit = !busy && items.any { item ->
                item.ok && (item.files.isEmpty() || local.selection[item.resourceId]?.isNotEmpty() == true)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OfflineUiState())

    init {
        // 订阅仓库状态机：Resolved 到来时初始化默认全选；Done 到来时安排自动复位回 Idle。
        viewModelScope.launch {
            repository.state.collect { state ->
                when (state) {
                    is OfflineState.Resolved -> syncSelection(state.items)
                    is OfflineState.Done -> scheduleAutoReset()
                    else -> Unit
                }
            }
        }
    }

    // ---- 用户动作 ----

    fun updateInput(text: String) {
        local.update { it.copy(input = text, notice = null) }
    }

    /** 解析输入的多行链接；busy 时忽略重复触发，空输入给本地校验提示且不触仓库。 */
    fun resolve() {
        if (inFlight || busyNow()) return
        val text = local.value.input.trim()
        if (text.isEmpty()) {
            local.update { it.copy(notice = "请输入至少一个链接") }
            return
        }
        autoResetJob?.cancel()
        clearResolvedLocals()
        inFlight = true
        viewModelScope.launch {
            try {
                repository.resolve(text)
            } finally {
                inFlight = false
            }
        }
    }

    /** 提交勾选结果；组装规则见 [buildSelections]，无可提交资源时给提示且不触仓库。 */
    fun submit() {
        if (inFlight || busyNow()) return
        autoResetJob?.cancel()
        val selections = buildSelections()
        if (selections.isEmpty()) {
            local.update { it.copy(notice = "没有可提交的资源，请先解析并至少保留一个有效资源") }
            return
        }
        inFlight = true
        viewModelScope.launch {
            try {
                repository.submit(selections)
            } finally {
                inFlight = false
            }
        }
    }

    /** 勾选 / 取消勾选资源清单中的单个文件；无清单的资源（无初始全选）安全 no-op。 */
    fun toggleFile(resourceId: Long, fileId: Long) {
        local.update { current ->
            val selected = current.selection[resourceId] ?: return@update current
            val next = if (fileId in selected) selected - fileId else selected + fileId
            current.copy(selection = current.selection + (resourceId to next))
        }
    }

    /** 整卡全选 / 全不选（"全不选"后该资源不随提交上送）。 */
    fun setAllFiles(resourceId: Long, checked: Boolean) {
        val files = local.value.lastItems.firstOrNull { it.resourceId == resourceId }?.files ?: return
        local.update { current ->
            val next = if (checked) files.mapTo(mutableSetOf()) { it.fileId } else mutableSetOf()
            current.copy(selection = current.selection + (resourceId to next))
        }
    }

    /** 手动清空回 Idle（输入 / 勾选 / 结果一并清理，并通知仓库复位）。 */
    fun reset() {
        autoResetJob?.cancel()
        local.update { it.copy(input = "", notice = null) }
        clearResolvedLocals()
        repository.reset()
    }

    // ---- 内部 ----

    private fun busyNow(): Boolean =
        repository.state.value is OfflineState.Resolving || repository.state.value is OfflineState.Submitting

    private fun clearResolvedLocals() {
        syncedItemsHash = 0
        local.update { it.copy(lastItems = emptyList(), selection = emptyMap()) }
    }

    /** Resolved 到来时初始化默认全选；同一份结果（内容相等）重放不覆盖用户的勾选改动。 */
    private fun syncSelection(items: List<OfflineResolvedItem>) {
        val hash = items.hashCode()
        if (hash == syncedItemsHash) return
        syncedItemsHash = hash
        local.update { current ->
            current.copy(
                lastItems = items,
                selection = items.associateTo(mutableMapOf()) { item ->
                    item.resourceId to item.files.mapTo(mutableSetOf()) { it.fileId }
                },
            )
        }
    }

    /**
     * 提交 selections 组装（测试锚点）：
     * - 失败资源（ok=false）一律不上送；
     * - 无文件清单的资源 → 空列表（B 契约：空 = 整个资源）；
     * - 文件清单全选 → 空列表（同上，走"整个资源"路径）；
     * - 部分勾选 → 仅选中的 fileId（保持清单顺序）；
     * - 全部取消勾选 → 跳过该资源（否则空列表语义会违背用户意图整资源上送）。
     */
    private fun buildSelections(): Map<Long, List<Long>> {
        val current = local.value
        return buildMap {
            for (item in current.lastItems) {
                if (!item.ok) continue
                if (item.files.isEmpty()) {
                    put(item.resourceId, emptyList())
                    continue
                }
                val selected = current.selection[item.resourceId] ?: continue
                when {
                    selected.isEmpty() -> Unit
                    selected.size >= item.files.size -> put(item.resourceId, emptyList())
                    else -> put(item.resourceId, selected.toList())
                }
            }
        }
    }

    /** Done 汇总文案（成功 N 个任务 / 失败附首个原因；载荷 = OfflineSubmitSummary）。 */
    private fun summarize(summary: OfflineSubmitSummary): String = when {
        summary.total == 0 -> summary.firstFailure?.let { "提交失败：$it" } ?: "服务器未返回任务结果"
        summary.failed == 0 -> "成功提交 ${summary.succeeded} 个任务"
        summary.succeeded == 0 -> "提交失败${summary.firstFailure?.let { "：$it" }.orEmpty()}"
        else -> "成功 ${summary.succeeded} 个任务，失败 ${summary.failed} 个${summary.firstFailure?.let { "：$it" }.orEmpty()}"
    }

    /** Done 展示 [resultClearMillis] 后自动清空回 Idle（清输入 / 勾选并复位仓库）。 */
    private fun scheduleAutoReset() {
        autoResetJob?.cancel()
        autoResetJob = viewModelScope.launch {
            delay(resultClearMillis)
            reset()
        }
    }

    private companion object {
        /** Done 结果提示展示时长，与文件页 opsMessage 的 3 秒同口径。 */
        const val RESULT_CLEAR_MILLIS = 3_000L
    }
}
