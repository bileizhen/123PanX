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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun UpdateDialog(viewModel: UpdateViewModel, openLink: (String) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val available = state.result as? UpdateResult.Available
    OverlayDialog(show = state.checking || state.result != null, title = available?.let { "发现新版本 v${it.release.version}" } ?: "检查更新", onDismissRequest = viewModel::dismiss) {
        val message = when (val result = state.result) {
            is UpdateResult.Available -> result.release.notes.ifBlank { "新版本已发布，可在项目发布页查看并下载安装。" }
            UpdateResult.Current -> "当前版本已是最新正式版本"
            UpdateResult.Unpublished -> "暂无可访问的正式发布版本"
            is UpdateResult.Failed -> result.message
            null -> "正在检查更新…"
        }
        Text(message, modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).testTag("update_message"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("关闭"), enabled = !state.checking, onClick = viewModel::dismiss, modifier = Modifier.weight(1f))
            if (available != null) TextButton(uiText("查看版本"), onClick = { openLink(available.release.url); viewModel.dismiss() }, modifier = Modifier.weight(1f))
        }
    }
}
