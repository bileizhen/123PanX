package io.github.bileizhen.pan123x.feature.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.data.settings.UpdateResult
import io.github.bileizhen.pan123x.data.settings.UpdateSource
import io.github.bileizhen.pan123x.BuildConfig
import io.github.bileizhen.pan123x.ui.util.formatBytes
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun UpdateDialog(viewModel: UpdateViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val available = state.result as? UpdateResult.Available
    val downloading = state.download as? UpdateDownloadState.Downloading
    val ready = state.download is UpdateDownloadState.Ready
    OverlayDialog(show = state.visible, title = available?.let { "${uiText("发现新版本")} v${it.release.version}" } ?: uiText("检查更新"), onDismissRequest = viewModel::dismiss) {
        val message = when (val result = state.result) {
            is UpdateResult.Available -> result.release.notes.ifBlank { "新版本已发布。" }
            UpdateResult.Current -> "当前版本已是最新正式版本"
            UpdateResult.Unpublished -> "暂无可访问的正式发布版本"
            is UpdateResult.Failed -> result.message
            null -> "正在检查更新…"
        }
        Text(if (state.checking) uiText("正在检查更新…") else if (available != null) message else uiText(message), modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("update_message"))
        if (available != null && !state.checking) {
            Spacer(Modifier.height(12.dp))
            OverlaySpinnerPreference(title = uiText("下载源"), items = UpdateSource.entries.map { DropdownItem(uiText(it.label)) },
                selectedIndex = state.source.ordinal, onSelectedIndexChange = { viewModel.selectSource(UpdateSource.entries[it]) },
                enabled = downloading == null && !ready && !state.installing, modifier = Modifier.testTag("update_source"))
            if (BuildConfig.DEBUG) Text(uiText("当前为调试版，正式版会独立安装，账户需重新登录。"))
            when (val download = state.download) {
                is UpdateDownloadState.Downloading -> {
                    Text("${formatBytes(download.received)} / ${formatBytes(download.total)}", modifier = Modifier.testTag("update_progress"))
                    LinearProgressIndicator(progress = (download.received.toDouble() / download.total.coerceAtLeast(1)).toFloat(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                is UpdateDownloadState.Failed -> Text(uiText(download.message), modifier = Modifier.testTag("update_download_error"))
                is UpdateDownloadState.Ready -> Text(uiText("下载完成，已校验安装包"))
                UpdateDownloadState.Idle -> Text("${uiText("安装包")} ${formatBytes(available.release.size)}")
            }
            state.installMessage?.let { Text(uiText(it), modifier = Modifier.testTag("update_install_message")) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText(if (downloading != null) "取消下载" else "关闭"), enabled = !state.checking && !state.installing,
                onClick = { if (downloading != null) viewModel.cancelDownload() else viewModel.dismiss() }, modifier = Modifier.weight(1f))
            if (available != null) Button(onClick = { if (ready) viewModel.installDownloaded() else viewModel.downloadUpdate() },
                enabled = !state.checking && downloading == null && !state.installing, colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f).testTag("update_download")) {
                Text(uiText(if (state.installing) "请求安装…" else if (ready) "请求安装" else if (state.download is UpdateDownloadState.Failed) "重试下载" else "下载更新"))
            }
        }
    }
}
