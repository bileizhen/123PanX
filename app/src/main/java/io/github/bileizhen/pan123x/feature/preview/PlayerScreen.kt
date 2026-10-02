// SPDX-License-Identifier: GPL-3.0-only
// PlayerView / ExoPlayer.Builder 在 media3 属 @UnstableApi 面（1.5.1 实际稳定，但注解未撤）；
// 文件级标注覆盖 AndroidView factory lambda——lint 的 UnsafeOptInUsageError 不认 Kotlin
// @OptIn 对 lambda 的传播。
@file:androidx.media3.common.util.UnstableApi

package io.github.bileizhen.pan123x.feature.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.ui.util.FileKind
import io.github.bileizhen.pan123x.ui.util.fileKindOf
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.basic.Text

/**
 * 视频 / 音频在线播放页。
 *
 * 数据契约：只消费 [PreviewState]（PreviewViewModel，共享状态）；
 * 直链 [PreviewState.Ready.url] 是短期 signed URL——**绝不显示、不记录**，
 * 仅交给 ExoPlayer；取直链与拉流均走 TransferClient 链路，本层不做任何网络请求。
 *
 * 播放器生命周期：`remember(fileId)` 保证同一文件的重组/换 URL 不重建 ExoPlayer，
 * 离开页面在 `DisposableEffect` 里 `release`；切到后台（ON_STOP）暂停，避免后台外放。
 */
@Composable
fun PlayerScreen(state: StateFlow<PreviewState>, client: okhttp3.OkHttpClient = io.github.bileizhen.pan123x.core.network.PanHttpClientFactory.transferClient()) {
    val current by state.collectAsStateWithLifecycle()
    // 播放器页整体黑底：视频留黑边、音频封面居中，与系统播放器观感一致。
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val preview = current) {
            PreviewState.Loading -> PlayerMessage("正在加载预览…")
            is PreviewState.Failed -> PlayerFailure(preview.fileName, preview.userMessage)
            is PreviewState.Ready -> PlayerSurface(preview.file, preview.url, client)
        }
    }
}

/** Ready 态：构建 ExoPlayer 并挂到 PlayerView。 */
@Composable
private fun PlayerSurface(file: CloudFileEntity, url: String, client: okhttp3.OkHttpClient) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val context = LocalContext.current
    // 音频判定取舍（两个方案选一）：按文件名后缀走既有 fileKindOf 表——
    // 零监听开销、首帧前即可决定布局；代价是"视频文件却无视频轨"的罕见坏流会显示黑屏 + 控制器，
    // 可接受。监听 videoSize 的方案更精确但需要 Player.Listener + 状态同步，v1 收益不足。
    val isAudio = remember(file.fileId, file.fileName) {
        fileKindOf(isFolder = false, fileName = file.fileName) == FileKind.AUDIO
    }
    // player 创建放进 remember：旋转后 Activity 重建会整体重进组合，其余重组（如换 signed URL）
    // 复用同一实例，绝不重复 build。
    val streamClient = remember(client) { client.newBuilder().followRedirects(true).followSslRedirects(false).build() }
    val player = remember(file.fileId, streamClient) { ExoPlayer.Builder(context)
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(streamClient)))
        .build() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(player, url) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }
    // 用户切到后台立即暂停：PlayerView 只管渲染，不做生命周期感知；回到前台由用户手动继续。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    // FIT：视频按比例留黑边完整显示，绝不裁切（体验让位于正确）。
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize().testTag("player_surface"),
        )
        if (isAudio) {
            // 音频无画面：文件名居中 + "正在播放"提示；控制条仍由 PlayerView 内建 controller 提供。
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        file.fileName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(uiText("正在播放"), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                }
            }
        }
    }
}

/** 黑底居中提示（Loading 态；此时还没有文件名可显示）。 */
@Composable
private fun PlayerMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** Failed 态：文件名 + 用户可读文案（不透出 stacktrace / URL）。 */
@Composable
private fun PlayerFailure(fileName: String, userMessage: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (fileName.isNotBlank()) {
                Text(
                    fileName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                userMessage,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("preview_error"),
            )
        }
    }
}
