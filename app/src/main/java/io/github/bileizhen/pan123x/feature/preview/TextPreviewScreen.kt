// Text preview : stream the resolved CDN direct URL with a size cap.
// Miuix visual language; truncation notice reuses ui.util.formatBytes wording.
package io.github.bileizhen.pan123x.feature.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.transfer.preview.TextContent
import io.github.bileizhen.pan123x.core.transfer.preview.TextPreviewLoader
import io.github.bileizhen.pan123x.ui.util.formatBytes
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文本预览：Ready 后经 [TextPreviewLoader] 限长拉直链文本
 * （Loader 内部已在 Dispatchers.IO 执行，避免多一层调度跳转），等宽字体竖向滚动，
 * 截断时底部常驻提示条。
 *
 * @param maxBytes 预览大小上限（设置项 `max_text_preview_bytes`）。
 * @param client 传输用 [OkHttpClient]（TransferClient：不带 API 认证头）。
 *   带默认值是为不破坏  的冻结调用形态 `TextPreviewScreen(state, maxBytes)`；
 *   缺省兜底每次新建客户端，接线处（PanXApp / AppContainer）应注入共享实例以复用连接池。
 */
@Composable
fun TextPreviewScreen(
    state: StateFlow<PreviewState>,
    maxBytes: Long,
    client: OkHttpClient = remember { OkHttpClient() },
) {
    val current by state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().testTag("preview_text")) {
        when (val value = current) {
            PreviewState.Loading -> Box(Modifier.weight(1f)) { PreviewCenterLoading("正在加载文本内容…") }
            is PreviewState.Failed -> Box(Modifier.weight(1f)) { PreviewFailureCard(value) }
            is PreviewState.Ready -> ReadyText(value, maxBytes, client)
        }
    }
}

private sealed interface TextResult {
    data object Loading : TextResult
    data object Failed : TextResult
    data class Done(val content: TextContent) : TextResult
}

/** 文本内容区：加载 / 失败占位在上，截断提示条固定在滚动区下方（不随内容滚动）。 */
@Composable
private fun ReadyText(ready: PreviewState.Ready, maxBytes: Long, client: OkHttpClient) {
    var result by remember(ready.url, maxBytes) { mutableStateOf<TextResult>(TextResult.Loading) }
    LaunchedEffect(ready.url, maxBytes, client) {
        result = try {
            TextResult.Done(TextPreviewLoader.load(ready.url, maxBytes, client))
        } catch (error: CancellationException) {
            // 取消必须继续传播（感知取消），不能被当作加载失败吞掉
            throw error
        } catch (error: IOException) {
            // 异常消息可能含主机名等诊断信息，固定中文文案进 UI
            TextResult.Failed
        }
    }
    val doneContent = (result as? TextResult.Done)?.content
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            // 本地快照：result 是委托属性（by collectAsStateWithLifecycle），无法在 when 分支
            // 智能转换，先取本地 val 才能收窄到 Done。
            val current = result
            when (current) {
                TextResult.Loading -> PreviewCenterLoading("正在加载文本内容…")
                TextResult.Failed -> TextLoadFailed()
                is TextResult.Done -> TextScrollArea(current.content)
            }
        }
        if (doneContent?.truncated == true) {
            TruncatedBar(doneContent.bytes)
        }
    }
}

@Composable
private fun TextScrollArea(content: TextContent) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            content.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

/** 截断提示（大文本不全量加载，告知用户仅展示了前多少内容）。 */
@Composable
private fun TruncatedBar(bytes: Long) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .testTag("preview_text_truncated"),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "仅预览前 ${formatBytes(bytes)}，完整内容请下载。",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun TextLoadFailed() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(
            Modifier.fillMaxWidth().testTag("preview_error"),
            cornerRadius = 24.dp,
            insideMargin = PaddingValues(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiText("文本内容加载失败"), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(
                    uiText("网络不稳定或链接已过期，请返回重试。"),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
