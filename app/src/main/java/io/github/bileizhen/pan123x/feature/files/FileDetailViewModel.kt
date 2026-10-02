package io.github.bileizhen.pan123x.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.core.account.AccountManager
import io.github.bileizhen.pan123x.core.account.SessionState
import io.github.bileizhen.pan123x.core.database.CloudFileDao
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FileDetailUiState(
    val loading: Boolean = true,
    val file: CloudFileEntity? = null,
)

/**
 * 文件详情页状态（其他类型先落到详情）。详情从当前账户的 Room 缓存行读取
 * （允许 ViewModel 注入 dao 按 fileId 取数）；文件页能点进来的条目必有缓存行，
 * 缓存缺失（被刷新替换 / 未登录）时显示缺态而不是猜测数据。
 */
class FileDetailViewModel(
    private val fileId: Long,
    private val cloudFileDao: CloudFileDao,
    private val manager: AccountManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow(FileDetailUiState())
    val uiState: StateFlow<FileDetailUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = manager.awaitRestored()
            val accountId = (session as? SessionState.Ready)?.accountId
            val file = accountId?.let { cloudFileDao.get(it, fileId) }
            mutableState.update { it.copy(loading = false, file = file) }
        }
    }
}
