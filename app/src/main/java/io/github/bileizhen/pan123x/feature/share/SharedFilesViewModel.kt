package io.github.bileizhen.pan123x.feature.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.network.ApiResult
import io.github.bileizhen.pan123x.core.network.FileItemDto
import io.github.bileizhen.pan123x.core.network.SharedInfoDto
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

enum class SharedSort(val label: String) { NAME("名称"), SIZE("大小"), MODIFIED("修改时间") }

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
    val info: SharedInfoDto? = null,
    val infoLoading: Boolean = true,
    val infoError: String? = null,
    val search: String = "",
    val sort: SharedSort = SharedSort.NAME,
    val ascending: Boolean = true,
    val grid: Boolean = false,
    val total: Int = 0,
) {
    val visibleFiles: List<FileItemDto> get() {
        val order = when (sort) {
            SharedSort.NAME -> compareBy<FileItemDto> { it.fileName.lowercase(java.util.Locale.ROOT) }
            SharedSort.SIZE -> compareBy { it.size }
            SharedSort.MODIFIED -> compareBy { it.updateAt }
        }
        return files.filter { it.fileName.contains(search.trim(), ignoreCase = true) }
            .sortedWith(compareBy<FileItemDto> { !it.isFolder }.then(if (ascending) order else order.reversed()).thenBy { it.fileId })
    }
    val selectedFiles get() = files.filter { it.fileId in selected && it.available && info?.expired != true }
}

// Missing status means the API did not supply a verdict; never label it "valid".
internal val FileItemDto.available get() = status == null || status == 2
internal val FileItemDto.statusLabel get() = when (status) { null -> "状态未提供"; 2 -> "有效"; else -> "不可用" }

class SharedFilesViewModel(private val link: SharedLink, private val actions: SharedFilesActions) : ViewModel() {
    private val mutableState = MutableStateFlow(SharedFilesState(password = link.password))
    val state = mutableState.asStateFlow()
    private val key = URI(link.url).path.substringAfterLast('/').removeSuffix(".html")
    private var loadJob: Job? = null
    private var infoJob: Job? = null
    private val loadedMarkers = mutableSetOf<String>()
    init { refresh() }

    fun refreshInfo() {
        if (state.value.busy) return
        infoJob?.cancel()
        mutableState.update { it.copy(infoLoading = true, infoError = null) }
        infoJob = viewModelScope.launch {
            try {
                when (val result = actions.info(key)) {
                    is ApiResult.Success -> mutableState.update { it.copy(info = result.data, infoLoading = false,
                        infoError = if (result.data == null) "分享信息暂不可用" else null,
                        selected = if (result.data?.expired == true) emptySet() else it.selected) }
                    else -> mutableState.update { it.copy(infoLoading = false, infoError = sharedError(result)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(infoLoading = false, infoError = "分享信息暂不可用") } }
        }
    }

    fun search(value: String) { if (!state.value.busy) mutableState.update { it.copy(search = value, selected = emptySet()) } }
    fun sort(value: SharedSort, ascending: Boolean) { if (!state.value.busy) mutableState.update { it.copy(sort = value, ascending = ascending) } }
    fun toggleLayout() { if (!state.value.busy) mutableState.update { it.copy(grid = !it.grid) } }
    fun selectOnly(id: Long) { if (!state.value.busy && state.value.info?.expired != true && state.value.files.any { it.fileId == id && it.available }) mutableState.update { it.copy(selected = setOf(id)) } }
    fun consumeQueuedRevision() { mutableState.update { it.copy(queuedRevision = 0) } }

    fun password(value: String) { if (!state.value.busy) mutableState.update { it.copy(password = value.take(4)) } }
    fun refresh() { if (!state.value.busy) { refreshInfo(); load(more = false) } }
    fun more() { if (!state.value.busy && !state.value.loading && state.value.next != "-1") load(more = true) }
    fun enter(file: FileItemDto) {
        if (!file.isFolder || !file.available || state.value.busy || state.value.info?.expired == true) return
        mutableState.update { it.copy(trail = it.trail + (file.fileId to file.fileName), files = emptyList(), selected = emptySet(), search = "") }
        load(more = false)
    }
    fun ancestor(index: Int) {
        if (state.value.busy || index !in state.value.trail.indices || index == state.value.trail.lastIndex) return
        mutableState.update { it.copy(trail = it.trail.take(index + 1), files = emptyList(), selected = emptySet(), search = "") }
        load(more = false)
    }
    fun toggle(id: Long) { if (!state.value.busy && state.value.info?.expired != true && state.value.files.any { it.fileId == id && it.available }) mutableState.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }
    fun selectAll() { if (!state.value.busy && state.value.info?.expired != true) mutableState.update {
        val ids = it.visibleFiles.filter { f -> f.available }.map { f -> f.fileId }.toSet()
        it.copy(selected = if (ids.isNotEmpty() && ids.all { id -> id in it.selected }) emptySet() else ids)
    } }
    fun clearSelection() { if (!state.value.busy) mutableState.update { it.copy(selected = emptySet()) } }
    fun dismissFeedback() { mutableState.update { it.copy(error = null, message = null) } }

    private fun load(more: Boolean) {
        loadJob?.cancel()
        val snapshot = state.value
        if (!more) loadedMarkers.clear()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, message = null, selected = if (more) it.selected else emptySet()) }
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
                            total = result.data.total,
                            page = if (more) snapshot.page + 1 else 1, error = if (loop) "分享列表分页异常，请刷新" else null) }
                    }
                    else -> mutableState.update { it.copy(loading = false, error = sharedError(result)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, error = "无法加载分享，请重试") } }
        }
    }

    private fun selection() = state.value.selectedFiles
    fun save(targetId: Long) {
        if (state.value.busy || selection().isEmpty()) return
        val files = selection(); val password = state.value.password
        mutableState.update { it.copy(busy = true, error = null, message = "正在保存至云盘…") }
        viewModelScope.launch {
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
        mutableState.update { it.copy(busy = true, error = null, message = "正在加入下载队列…") }
        viewModelScope.launch {
            try {
                val result = actions.download(key, password, files, tree)
                mutableState.update { it.copy(busy = false, error = result.error, message = if (result.queued > 0) "已加入 ${result.queued} 个下载任务" else null,
                    queuedRevision = it.queuedRevision + if (result.queued > 0) 1 else 0) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(busy = false, error = "无法加入下载队列，请重试", message = null) } }
        }
    }
}
