package io.github.bileizhen.pan123x.feature.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.network.ShareItemDto
import io.github.bileizhen.pan123x.data.share.ShareActions
import io.github.bileizhen.pan123x.data.share.ShareListStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch



/**
 * 分享行的用户可见链接：三级回退——协议优先 shareLink（shareLinkList.list[0]，可能缺），
 * 其次 shareUrl，两者皆空时按 ShareKey 拼规范形式
 * （file_service.py:415-416：链接 = "https://www.123pan.cn/s/" + ShareKey）。
 */
fun shareDisplayLink(item: ShareItemDto): String = when {
    item.shareLink.isNotBlank() -> item.shareLink
    item.shareUrl.isNotBlank() -> item.shareUrl
    else -> "https://www.123pan.cn/s/" + item.shareKey
}

/**
 * 分享页 ViewModel（M6 真实 API 替换 M0 本地演示）。
 *
 * 为什么 copy 是必填构造参数：剪贴板是 Android 系统服务，VM 若直接触碰 ClipboardManager
 * 就带上 android.* 依赖，纯 JVM 单测无法运行（android.jar 为 stub）。接线处 PanXApp 传
 * 转发系统剪贴板的 lambda，测试注入收集器，VM 保持零 android 依赖。
 */
class ShareViewModel(
    private val repository: ShareActions,
    private val copy: (String) -> Unit,
    private val session: StateFlow<SessionState>? = null,
    autoRefresh: Boolean = true,
) : ViewModel() {

    data class UiState(
        val shares: List<ShareItemDto> = emptyList(),
        val status: ShareListStatus = ShareListStatus.LOADING,
        val error: String? = null,
        /**
         * 是否显示"加载更多"。UI 层保守分页：协议游标（data.Next）在仓库内部、接口约定未暴露
         * hasMore，VM 拿不到权威值。策略 = refresh 后允许"试一页"；loadMore 返回后列表没有
         * 增长即视为无更多，隐藏按钮（仓库对"无更多"本就是 no-op，多试无害）。
         * M7 若需要精确分页，再扩展 ShareActions 暴露游标。
         */
        val canLoadMore: Boolean = false,
        val revokingId: Long? = null,     // 撤销确认弹窗目标
        val copiedId: Long? = null,       // "链接已复制"一次性提示
        val loggedOut: Boolean = false,
        val restoring: Boolean = false,
        val accountId: String? = null,
    )

    /** UI 层本地控制态（撤销确认目标、复制提示），与仓库三流在 uiState 合并。 */
    private data class Controls(
        val revokingId: Long? = null,
        val copiedId: Long? = null,
    )

    private val controls = MutableStateFlow(Controls())
    private data class SessionControls(val controls: Controls, val state: SessionState?)
    private val sessionControls = session?.let { source ->
        combine(controls, source) { local, state -> SessionControls(local, state) }
    } ?: controls.map { SessionControls(it, null) }
    private val loadMoreEnabled = MutableStateFlow(true)

    /** 重入保护：仅主线程访问（UI 事件 / viewModelScope 均在 Main），无需原子化。 */
    private var refreshBusy = false
    private var loadMoreBusy = false

    val uiState: StateFlow<UiState> = combine(
        repository.shares,
        repository.status,
        repository.error,
        sessionControls,
        loadMoreEnabled,
    ) { shares, status, error, local, canLoadMore ->
        UiState(
            shares = shares,
            status = status,
            error = error,
            canLoadMore = canLoadMore,
            revokingId = local.controls.revokingId,
            copiedId = local.controls.copiedId,
            loggedOut = local.state is SessionState.LoggedOut,
            restoring = local.state is SessionState.Restoring,
            accountId = (local.state as? SessionState.Ready)?.accountId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState(
        loggedOut = session?.value is SessionState.LoggedOut,
        restoring = session?.value is SessionState.Restoring,
        accountId = (session?.value as? SessionState.Ready)?.accountId,
    ))

    init {
        // 进入页面主动拉一次：仓库初始 status = LOADING 且不自动加载，不触发则页面永远停在加载态。
        if (autoRefresh) refresh()
    }

    /** 拉首页整体替换。失败语义（保留旧列表 + ERROR + 文案）由仓库负责，VM 不重复处理。 */
    fun refresh() {
        if (refreshBusy) return
        refreshBusy = true
        // 刷新会把仓库游标重置回首页，旧列表的"没有更多"结论失效，重新允许尝试一次。
        loadMoreEnabled.value = true
        viewModelScope.launch {
            try {
                repository.refresh()
            } finally {
                refreshBusy = false
            }
        }
    }

    /**
     * 追加下一页。仓库对"无更多 / 已在加载"是 no-op：返回后列表没有增长 → canLoadMore
     * 置 false，按钮自动消失；有增长 → 保持显示。页面仅在列表非空时渲染按钮，
     * 因此这里不再额外判断空态。
     */
    fun loadMore() {
        if (loadMoreBusy) return
        val before = repository.shares.value.size
        loadMoreBusy = true
        viewModelScope.launch {
            try {
                repository.loadMore()
                loadMoreEnabled.value = repository.shares.value.size > before
            } finally {
                loadMoreBusy = false
            }
        }
    }

    fun requestRevoke(id: Long) {
        controls.update { it.copy(revokingId = id) }
    }

    fun dismissRevoke() {
        controls.update { it.copy(revokingId = null) }
    }

    /**
     * 撤销（二次确认之后才允许调用）。VM 只转发、不消费返回值：
     * 成功时仓库把该行从列表移除（列表流自动刷新 UI）；失败时仓库写 error
     * （ERROR 态 / 列表上方横幅），VM 不需要自己镜像结果。
     */
    fun confirmRevoke() {
        val id = controls.value.revokingId ?: return
        controls.update { it.copy(revokingId = null) }
        viewModelScope.launch { repository.revoke(id) }
    }

    /** 复制分享链接（三级回退见 [shareDisplayLink]），并置一次性"链接已复制"提示。 */
    fun copyLink(item: ShareItemDto) {
        copy(shareDisplayLink(item))
        controls.update { it.copy(copiedId = item.shareId) }
        scheduleCopyFeedbackExpiry(item.shareId)
    }

    /** 复制提示 3 秒自动清除；期间用户复制了另一条链接，则只保留最新一条的提示。 */
    private fun scheduleCopyFeedbackExpiry(id: Long) {
        viewModelScope.launch {
            delay(COPY_FEEDBACK_MILLIS)
            controls.update { current -> if (current.copiedId == id) current.copy(copiedId = null) else current }
        }
    }

    private companion object {
        /** "链接已复制"提示展示时长；与文件页 opsMessage / 回收站 message 的 3 秒同口径。 */
        const val COPY_FEEDBACK_MILLIS = 3_000L
    }
}
