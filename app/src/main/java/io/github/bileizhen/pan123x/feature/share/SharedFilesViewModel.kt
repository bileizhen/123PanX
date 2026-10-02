package io.github.bileizhen.pan123x.feature.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.share.SharedLink
import io.github.bileizhen.pan123x.data.share.SharedFilesActions
import io.github.bileizhen.pan123x.data.share.sharedError
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharedFilesState(
    val trail: List<Pair<Long, String>> = listOf(0L to "全部文件"),
    val files: List<FileItemDto> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val password: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val next: String = "-1",
    val page: Int = 1,
    val queuedRevision: Int = 0,
)

class SharedFilesViewModel(private val link: SharedLink, private val actions: SharedFilesActions) : ViewModel() {
    private val mutableState = MutableStateFlow(SharedFilesState(password = link.password))
    val state = mutableState.asStateFlow()
    private val key = URI(link.url).path.substringAfterLast('/').removeSuffix(".html")
    private var loadJob: Job? = null
    private val loadedMarkers = mutableSetOf<String>()
    init { refresh() }

    fun password(value: String) { if (!state.value.busy) mutableState.update { it.copy(password = value.take(4)) } }
    fun refresh() { load(more = false) }
    fun more() { if (!state.value.loading && state.value.next != "-1") load(more = true) }
    fun enter(file: FileItemDto) {
        if (!file.isFolder || state.value.busy) return
        mutableState.update { it.copy(trail = it.trail + (file.fileId to file.fileName), files = emptyList(), selected = emptySet()) }
        refresh()
    }
    fun ancestor(index: Int) {
        if (state.value.busy || index !in state.value.trail.indices || index == state.value.trail.lastIndex) return
        mutableState.update { it.copy(trail = it.trail.take(index + 1), files = emptyList(), selected = emptySet()) }
        refresh()
    }
    fun toggle(id: Long) { if (!state.value.busy) mutableState.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }
    fun selectAll() { if (!state.value.busy) mutableState.update { it.copy(selected = if (it.selected.size == it.files.size) emptySet() else it.files.map { f -> f.fileId }.toSet()) } }
    fun clearSelection() { if (!state.value.busy) mutableState.update { it.copy(selected = emptySet()) } }
    fun dismissFeedback() { mutableState.update { it.copy(error = null, message = null) } }

    private fun load(more: Boolean) {
        loadJob?.cancel()
        val snapshot = state.value
        if (!more) loadedMarkers.clear()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, message = null) }
            try {
                val result = actions.list(key, snapshot.password, snapshot.trail.last().first, if (more) snapshot.page + 1 else 1, if (more) snapshot.next else "0")
                when (result) {
                    is ApiResult.Success -> {
                        val next = result.data.next
                        val loop = more && next != "-1" && !loadedMarkers.add(next)
                        if (!more && next != "-1") loadedMarkers.add(next)
                        mutableState.update { it.copy(loading = false,
                            files = ((if (more) it.files else emptyList()) + result.data.infoList).filter { f -> f.fileId > 0 }.distinctBy { f -> f.fileId },
                            selected = if (more) it.selected else emptySet(), next = if (loop || result.data.infoList.isEmpty()) "-1" else next,
                            page = if (more) snapshot.page + 1 else 1, error = if (loop) "分享列表分页异常，请刷新" else null) }
                    }
                    else -> mutableState.update { it.copy(loading = false, error = sharedError(result)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, error = "无法加载分享，请重试") } }
        }
    }

    private fun selection() = state.value.files.filter { it.fileId in state.value.selected }
    fun save(targetId: Long) {
        if (state.value.busy || selection().isEmpty()) return
        val files = selection(); val password = state.value.password
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, message = "正在保存至云盘…") }
            try {
                val error = actions.save(key, password, files, targetId)
                mutableState.update { it.copy(busy = false, error = error, message = if (error == null) "已保存至云盘" else null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, error = "转存结果暂未确认，请检查目标目录", message = null) } }
        }
    }
    fun download(tree: String? = null) {
        if (state.value.busy || selection().isEmpty()) return
        val files = selection(); val password = state.value.password
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null, message = "正在加入下载队列…") }
            try {
                val result = actions.download(key, password, files, tree)
                mutableState.update { it.copy(busy = false, error = result.error, message = if (result.queued > 0) "已加入 ${result.queued} 个下载任务" else null,
                    queuedRevision = it.queuedRevision + if (result.queued > 0) 1 else 0) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, error = "无法加入下载队列，请重试", message = null) } }
        }
    }
}
