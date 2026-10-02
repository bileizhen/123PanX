package io.github.bileizhen.pan123x.feature.recycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.data.file.OpsOutcome
import io.github.bileizhen.pan123x.data.file.RecycleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 回收站页状态：items 为内存回收站快照（仓库保证不写 Room）；
 * selected / selectMode 为多选；busy 表示恢复 / 永久删除进行中（防重复提交）；
 * message 为短期结果反馈（UI 3 秒后 consume）；error 为最近一次刷新失败文案。
 */
data class RecycleUiState(
    val restoring: Boolean = true,
    val loggedOut: Boolean = false,
    val loading: Boolean = false,
    val items: List<CloudFileEntity> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val selectMode: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val empty: Boolean get() = items.isEmpty()
}

/** 多选 / 反馈等本地控制态，与仓库快照在 uiState 里合并。 */
private data class RecycleControls(
    val selected: Set<Long> = emptySet(),
    val selectMode: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * 回收站页状态机：确认交互留在 UI 层（删除二次确认），ViewModel 只在
 * 确认之后被调用。列表数据来自 [RecycleRepository] 的内存快照，恢复 / 永久删除
 * 委托仓库的 FileOpsRepository 契约，成功后由仓库本地移除条目。
 */
class RecycleViewModel(private val repository: RecycleRepository) : ViewModel() {

    private val controls = MutableStateFlow(RecycleControls())

    /** 已拉取过回收站的账户：防止同一账户反复自动刷新；换账户 / 重登后允许再来一次。 */
    private var loadedForAccount: String? = null

    val uiState: StateFlow<RecycleUiState> = combine(
        repository.state,
        repository.sessionState,
        controls,
    ) { recycle, session, controls ->
        RecycleUiState(
            restoring = session is SessionState.Restoring,
            loggedOut = session is SessionState.LoggedOut,
            loading = recycle.loading,
            items = recycle.items,
            error = recycle.error,
            selected = controls.selected,
            selectMode = controls.selectMode,
            busy = controls.busy,
            message = controls.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecycleUiState())

    /**
     * 首次进入（或换账户后再进）且会话就绪时自动拉取一次；失败不再循环重试，
     * 由错误态的重试按钮走手动 [refresh]。
     */
    fun refreshIfNeeded() {
        val current = uiState.value
        if (current.restoring || current.loggedOut || current.loading || current.busy) return
        val accountId = repository.accountIdOfSession ?: return
        if (loadedForAccount == accountId) return
        loadedForAccount = accountId
        refresh()
    }

    /** 手动刷新：进行中（刷新 / 操作）直接忽略。 */
    fun refresh() {
        val current = uiState.value
        if (current.loading || current.busy) return
        viewModelScope.launch { repository.refresh() }
    }

    /** 长按进入多选并选中该项。 */
    fun enterSelectMode(fileId: Long) {
        controls.update { it.copy(selectMode = true, selected = it.selected + fileId) }
    }

    /** 多选中点击条目切换选中；全部取消时自动退出多选。 */
    fun toggleSelect(fileId: Long) {
        controls.update { old ->
            val selected = if (fileId in old.selected) old.selected - fileId else old.selected + fileId
            if (selected.isEmpty()) old.copy(selectMode = false, selected = selected)
            else old.copy(selected = selected)
        }
    }

    /** 全选 / 取消全选（以当前内存列表为准）。 */
    fun toggleSelectAll() {
        controls.update { old ->
            val items = uiState.value.items
            if (old.selected.isNotEmpty() && old.selected.size >= items.size) {
                old.copy(selected = emptySet())
            } else {
                old.copy(selectMode = true, selected = items.mapTo(HashSet()) { it.fileId })
            }
        }
    }

    fun exitSelectMode() {
        controls.update { it.copy(selectMode = false, selected = emptySet()) }
    }

    /** 恢复选中项（UI 已在调用前完成入口确认）。 */
    fun restoreSelected() = performOperation(restore = true)

    /** 永久删除选中项（UI 已完成双重确认后才允许调用）。 */
    fun deleteForeverSelected() = performOperation(restore = false)

    /**
     * 清空回收站（M7 补全， 回收站完整体验）：全部条目永久删除。
     * 复用 [performOperation] 的结果聚合与 busy 语义——临时全选、执行、还原原选择，
     * 用户先前的多选状态不被破坏。
     */
    fun clearAll() {
        val current = uiState.value
        if (current.busy || current.loading || current.items.isEmpty()) return
        val previousSelection = current.selected
        val previousSelectMode = current.selectMode
        controls.update {
            it.copy(selectMode = true, selected = current.items.mapTo(mutableSetOf()) { it.fileId })
        }
        performOperation(restore = false)
        controls.update { it.copy(selectMode = previousSelectMode, selected = previousSelection) }
    }

    private fun performOperation(restore: Boolean) {
        val current = uiState.value
        if (current.busy || current.loading || current.selected.isEmpty()) return
        val targets = current.items.filter { it.fileId in current.selected }
        if (targets.isEmpty()) return
        val count = targets.size
        controls.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val outcome = if (restore) repository.restore(targets) else repository.deleteForever(targets)
                controls.update { old ->
                    when (outcome) {
                        is OpsOutcome.Success -> old.copy(
                            busy = false,
                            selectMode = false,
                            selected = emptySet(),
                            // 聚合结果（如"成功 N 个，失败 M 个"）优先用仓库文案，否则给默认提示。
                            message = outcome.message?.takeIf { it.isNotBlank() }
                                ?: if (restore) "已恢复 $count 个项目" else "已永久删除 $count 个项目",
                        )

                        is OpsOutcome.Failure -> old.copy(busy = false, message = outcome.userMessage)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 防御分支：仓库契约应把一切失败折成 OpsOutcome；意外异常时至少解除 busy，
                // 不给用户看 stacktrace。
                controls.update { it.copy(busy = false, message = "操作失败，请稍后重试") }
            }
        }
    }

    fun consumeMessage() = controls.update { it.copy(message = null) }

    class Factory(private val repository: RecycleRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RecycleViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return RecycleViewModel(repository) as T
        }
    }
}
