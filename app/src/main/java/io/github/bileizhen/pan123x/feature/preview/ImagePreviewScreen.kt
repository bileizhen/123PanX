// Image preview base : Coil3 AsyncImage over the resolved CDN direct URL.
// Visual structure follows bileizhen/LeiFetch (GPL-3.0) — Miuix surfaces for chrome states.
package io.github.bileizhen.pan123x.feature.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** 图片预览状态分派（冻结公开签名）。 */
@Composable
fun ImagePreviewScreen(state: StateFlow<PreviewState>) {
    val current by state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().testTag("image_preview")) {
        when (val value = current) {
            PreviewState.Loading -> PreviewCenterLoading("正在准备预览…")
            is PreviewState.Failed -> PreviewFailureCard(value)
            is PreviewState.Ready -> ReadyImage(value)
        }
    }
}

/** 黑底居中直链图（查看器黑底突出内容）；Coil 自带网络栈，不走 API 客户端。 */
@Composable
private fun ReadyImage(ready: PreviewState.Ready) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    var scale by remember(ready.file.fileId) { mutableStateOf(1f) }
    var pan by remember(ready.file.fileId) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, offset, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        val next = pan + offset
        pan = Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black).clipToBounds().onSizeChanged { viewport = it },
        contentAlignment = Alignment.Center,
    ) {
        // Coil3 的 AsyncImage 必须显式传 ImageLoader：取进程级单例（coil-network-okhttp 经
        // ServiceLoader 注册网络栈），全 app 共享内存 / 磁盘缓存，避免预览页私建缓存。
        // SingletonImageLoader.get 是纯 getter（进程级单例），直接读 Composable 上下文即可——
        // LocalPlatformContext.current 不能在 remember 的非 Composable lambda 里调用。
        val imageLoader = SingletonImageLoader.get(LocalPlatformContext.current)
        var phase by remember(ready.url) { mutableStateOf(ImagePhase.LOADING) }
        AsyncImage(
            model = ready.url,
            contentDescription = ready.file.fileName,
            imageLoader = imageLoader,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().transformable(transform).pointerInput(ready.file.fileId) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero })
            }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
            onState = { value ->
                phase = when (value) {
                    is AsyncImagePainter.State.Success -> ImagePhase.LOADED
                    is AsyncImagePainter.State.Error -> ImagePhase.FAILED
                    else -> ImagePhase.LOADING
                }
            },
        )
        when (phase) {
            ImagePhase.LOADING -> CircularProgressIndicator(Modifier.size(40.dp))
            ImagePhase.FAILED -> Column(
                Modifier.testTag("preview_error").padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(uiText("图片加载失败"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    uiText("网络不稳定或链接已过期，请返回重试。"),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }

            ImagePhase.LOADED -> Unit
        }
    }
}

private enum class ImagePhase { LOADING, LOADED, FAILED }

/** 居中加载态（图片 / 文本共用；顶部返回由 PanXApp 的 ScreenFrame 统一提供）。 */
@Composable
internal fun PreviewCenterLoading(hint: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(36.dp))
            Text(hint, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/**
 * 失败态提示卡（Miuix 语言，"文件名 + 文案"）：文案来自
 * [PreviewState.Failed.userMessage]（已脱敏的中文），绝不展示 stacktrace / URL。
 */
@Composable
internal fun PreviewFailureCard(failed: PreviewState.Failed) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Card(
            Modifier.fillMaxWidth().testTag("preview_error"),
            cornerRadius = 24.dp,
            insideMargin = PaddingValues(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uiText("无法预览"), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                if (failed.fileName.isNotBlank()) {
                    Text(
                        failed.fileName,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    failed.userMessage,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
