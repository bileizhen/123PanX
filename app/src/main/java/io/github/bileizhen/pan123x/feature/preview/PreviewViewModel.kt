package io.github.bileizhen.pan123x.feature.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.transfer.download.DownloadSource
import io.github.bileizhen.pan123x.core.transfer.download.PanDownloadResolver
import io.github.bileizhen.pan123x.core.transfer.download.ResolveOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 五类预览共用的取数状态机（播放器 / PDF 屏同样消费；
 *  显式状态：Loading / Ready / Failed 三态足够，预览页不做增量加载态）。
 */
sealed interface PreviewState {
    data object Loading : PreviewState

    /** [file] 为 Room 缓存行（稳定身份）；[url] 为短期 CDN 直链，禁止持久化。 */
    data class Ready(val file: CloudFileEntity, val url: String) : PreviewState

    /** [fileName] 取缓存行的文件名（缓存缺失时为空串）；[userMessage] 已是用户可读中文。 */
    data class Failed(val userMessage: String, val fileName: String) : PreviewState
}

/**
 * 预览取数基座：Room 缓存行 → [PanDownloadResolver] 解析 CDN 直链（取链与
 * 下载/播放解耦； 分层）。图片 / 文本 / 播放器 / PDF 四屏共用本状态。
 *
 * 取数时序对齐 `FileDetailViewModel` 先例：`awaitRestored → dao.get(accountId, fileId)`；
 * 缓存行缺失（目录刷新被替换 / 未登录）时 Failed 而不是猜测数据（M2 起的既定语义）。
 *
 * 可测性说明：[PanDownloadResolver] 是具体类，为保纯 JVM 单测，这里把
 * 解析动作抽成带默认值的构造参数 [resolve]——生产接线只传前四个参数（缺省即
 * `resolver::resolve`，冻结调用形态不变），测试注入替身函数即可，无需真实取链。
 */
class PreviewViewModel(
    private val fileId: Long,
    private val cloudFileDao: CloudFileDao,
    private val manager: AccountManager,
    resolver: PanDownloadResolver,
    private val resolve: suspend (DownloadSource) -> ResolveOutcome = resolver::resolve,
) : ViewModel() {

    private val mutableState = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = manager.awaitRestored()
            val accountId = (session as? SessionState.Ready)?.accountId
            val file = accountId?.let { cloudFileDao.get(it, fileId) }
            if (file == null) {
                // 此刻调用方只持有 fileId，拿不到可信文件名，Failed.fileName 留空。
                mutableState.value = PreviewState.Failed("文件不存在或已刷新，请返回重试", "")
                return@launch
            }
            // resolve 内部已处理 code==2 重登一次（PanDownloadResolver），这里只映射结果。
            when (val outcome = resolve(file.toDownloadSource())) {
                is ResolveOutcome.Success -> mutableState.value = PreviewState.Ready(file, outcome.url)
                is ResolveOutcome.Failure ->
                    mutableState.value = PreviewState.Failed(outcome.userMessage, file.fileName)
            }
        }
    }

    /** 缓存行 → 下载来源：只带稳定字段，short signed URL 不参与身份。 */
    private fun CloudFileEntity.toDownloadSource() = DownloadSource(
        fileId = fileId,
        fileName = fileName,
        size = size,
        etag = etag,
        s3KeyFlag = s3KeyFlag,
        isFolder = isFolder,
    )
}
