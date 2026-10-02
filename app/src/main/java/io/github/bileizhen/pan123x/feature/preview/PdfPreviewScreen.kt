// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.feature.preview

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.transfer.preview.PdfTooLargeException
import io.github.bileizhen.pan123x.ui.util.formatBytes
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 打开后的 PDF 文档句柄：持有 [PdfRenderer] 与其文件描述符，屏幕内共享一个实例。
 *
 * 为什么需要 [renderLock]：PdfRenderer **非线程安全**，而 LazyColumn 会同时组合
 * 2~3 个页 item、各自在 IO 线程发起渲染，不串行化会直接 native 崩溃；
 * 串行化的代价（相邻页渲染排队）对按需逐页渲染的 v1 完全可接受。
 */
internal class PdfDocumentHandle internal constructor(
    val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
) {
    val pageCount: Int get() = renderer.pageCount

    private val renderLock = Mutex()

    /** 在 IO 线程串行执行一段渲染；[block] 抛出的异常原样向上传播。 */
    suspend fun <T> render(block: (PdfRenderer) -> T): T = withContext(Dispatchers.IO) {
        renderLock.withLock { block(renderer) }
    }

    /**
     * 关闭顺序：renderer 先于 fd（renderer.close 内部还会触达 fd）。
     * 两者都幂等（runCatching + close 可重复调用），屏幕退出与换 URL 重开都安全。
     */
    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

/** 单页位图像素上限：8M 像素 ≈ 32MB ARGB_8888，覆盖 A4 屏宽渲染绰绰有余。 */
private const val MAX_PAGE_PIXELS = 8_388_608L

/**
 * 单页位图尺寸（纯函数，可 JVM 复核）：宽固定 [targetWidthPx]，高按页面点数（1/72 英寸）比例换算；
 * 超过 [MAX_PAGE_PIXELS] 时等比缩小——竖长页按屏宽渲染可能生成数万行像素（约百 MB 级
 * ARGB_8888 分配），预缩放比事后 OOM 恢复可控得多。
 */
internal fun pageBitmapSize(pageWidthPt: Int, pageHeightPt: Int, targetWidthPx: Int): Pair<Int, Int> {
    if (pageWidthPt <= 0 || pageHeightPt <= 0 || targetWidthPx <= 0) return 0 to 0
    var width = targetWidthPx
    var height = ((pageHeightPt.toLong() * width + pageWidthPt / 2) / pageWidthPt).toInt().coerceAtLeast(1)
    val pixels = width.toLong() * height
    if (pixels > MAX_PAGE_PIXELS) {
        val scale = sqrt(MAX_PAGE_PIXELS.toDouble() / pixels)
        width = (width * scale).toInt().coerceAtLeast(1)
        height = (height * scale).toInt().coerceAtLeast(1)
    }
    return width to height
}

/**
 * PDF 预览页。
 *
 * [loader] 契约：由应用 PanXApp 接线处闭包 [io.github.bileizhen.pan123x.core.transfer.preview.PdfPreviewCache.ensure]——
 * ```kotlin
 * PdfPreviewScreen(
 *     state = previewViewModel.state,
 *     maxBytes = 1_048_576,
 *     loader = { url, size -> container.pdfPreviewCache.ensure(fileId, url, size, 1_048_576) },
 * )
 * ```
 * 为什么用挂起函数参数而不是把 PdfPreviewCache 直接传进来：Compose 组件无 DI，
 * 内部自建缓存需要 Context 与 OkHttp，会让纯 UI 无法脱离 AppContainer 复用/测试；
 * 收 loader 后本组件只见 `suspend (url, size) -> File`，测试可传假加载器。
 */
@Composable
fun PdfPreviewScreen(
    state: StateFlow<PreviewState>,
    maxBytes: Long,
    loader: suspend (url: String, expectedSize: Long) -> File,
) {
    val current by state.collectAsStateWithLifecycle()
    when (val preview = current) {
        PreviewState.Loading -> PdfMessage("正在加载预览…")
        is PreviewState.Failed -> PdfFailure(preview.fileName, preview.userMessage)
        is PreviewState.Ready -> PdfContentLoaded(preview.file, preview.url, maxBytes, loader)
    }
}

/** Ready 态：下载/取缓存（loader）→ 打开 PdfRenderer → 逐页渲染。 */
@Composable
private fun PdfContentLoaded(
    file: CloudFileEntity,
    url: String,
    maxBytes: Long,
    loader: suspend (url: String, expectedSize: Long) -> File,
) {
    var pdfState by remember(file.fileId) { mutableStateOf<PdfUiState>(PdfUiState.Loading) }
    LaunchedEffect(file.fileId, url) {
        // 换直链重开（signed URL 刷新）时先关旧句柄，再进入 Loading；
        // 正在进行的页渲染会因 renderer 已关闭收到占位降级（见 [renderPdfPage]），不会崩溃。
        val previous = pdfState
        pdfState = PdfUiState.Loading
        (previous as? PdfUiState.Opened)?.handle?.close()
        pdfState = try {
            PdfUiState.Opened(openPdfDocument(loader(url, file.size)))
        } catch (e: PdfTooLargeException) {
            PdfUiState.Error(tooLargeMessage(maxBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络/损坏文件/渲染器初始化失败统一转中文文案。
            PdfUiState.Error("无法打开 PDF 预览，文件可能已损坏或网络不可用")
        }
    }
    DisposableEffect(file.fileId) {
        onDispose { (pdfState as? PdfUiState.Opened)?.handle?.close() }
    }
    when (val pdf = pdfState) {
        PdfUiState.Loading -> PdfLoading()
        is PdfUiState.Error -> PdfFailure(file.fileName, pdf.userMessage)
        is PdfUiState.Opened -> PdfPages(pdf.handle)
    }
}

/** 竖向逐页 LazyColumn：页宽 = 可用宽度减去左右 16dp，进入组合的页按需渲染（不预取）。 */
@Composable
private fun PdfPages(handle: PdfDocumentHandle) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageWidthPx = with(density) { (maxWidth - 32.dp).toPx().roundToInt() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(handle.pageCount, key = { it }) { index ->
                PdfPageItem(handle, index, pageWidthPx)
            }
        }
    }
}

/**
 * 单页 item：`remember(pageIndex)` 缓存位图，进入组合即渲染一次。
 *
 * 回收策略（两案择一，选**不主动 recycle、交给 GC**）：minSdk 26 起 Bitmap 像素数据
 * 由 NativeAllocationRegistry 自动回收，不 recycle 只损失一点点时机；而手动 recycle
 * 若与最后一帧绘制竞争会出现 "trying to use a recycled bitmap" 崩溃——
 * 可靠性优先，v1 不做位图池。
 */
@Composable
private fun PdfPageItem(handle: PdfDocumentHandle, pageIndex: Int, pageWidthPx: Int) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(handle, pageIndex, pageWidthPx) {
        bitmap = renderPdfPage(handle, pageIndex, pageWidthPx)
    }
    Column(Modifier.fillMaxWidth()) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "第 ${pageIndex + 1} 页",
                modifier = Modifier.fillMaxWidth().testTag("pdf_page_$pageIndex"),
            )
        } else {
            // 渲染占位：A4 常见比例（1:sqrt(2)），只为避免占位框在渲染完成时跳动。
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio = 1f / 1.4142f)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "正在渲染第 ${pageIndex + 1} 页…",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(
            "${pageIndex + 1} / ${handle.pageCount}",
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            // testTag 只标首页页脚：Compose 测试的 onNodeWithTag 不允许多节点同名命中。
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .then(if (pageIndex == 0) Modifier.testTag("pdf_page_count") else Modifier),
        )
    }
}

/**
 * 渲染一页：openPage → 按页比例建 ARGB_8888 位图（白底，PDF 页通常默认白）→ 缩放矩阵渲染。
 * 失败一律降级为 null 占位：renderer 已关闭（快速滚动/返回的竞态）与位图分配超限（OOM）
 * 都是可恢复场景，绝不让整页浏览崩溃。
 */
private suspend fun renderPdfPage(handle: PdfDocumentHandle, pageIndex: Int, widthPx: Int): Bitmap? =
    try {
        handle.render { renderer ->
            val page = renderer.openPage(pageIndex)
            try {
                val (width, height) = pageBitmapSize(page.width, page.height, widthPx)
                if (width <= 0 || height <= 0) return@render null
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val matrix = Matrix()
                matrix.setScale(width.toFloat() / page.width, height.toFloat() / page.height)
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    } catch (e: CancellationException) {
        // 协程取消必须继续传播，不能被下面的降级吞掉。
        throw e
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

/** 打开本地 PDF：fd 只读 → PdfRenderer；失败时保证关闭 fd 不泄漏。 */
private suspend fun openPdfDocument(file: File): PdfDocumentHandle = withContext(Dispatchers.IO) {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        PdfDocumentHandle(PdfRenderer(descriptor), descriptor)
    } catch (t: Throwable) {
        runCatching { descriptor.close() }
        throw t
    }
}

private fun tooLargeMessage(maxBytes: Long): String =
    "文件超过 ${formatBytes(maxBytes)} 的预览上限，已停止下载，建议下载后打开"

/** 普通（非黑底）居中提示：Loading 态。 */
@Composable
private fun PdfMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
    }
}

/** 加载中：不定态指示器 + 文案（页数未可知，渲染就绪后直接进入逐页列表）。 */
@Composable
private fun PdfLoading() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(progress = null, size = 36.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                uiText("正在加载 PDF…"),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
        }
    }
}

/** Failed 态（PreviewState.Failed 与 PdfUiState.Error 共用）：文件名 + 用户可读文案。 */
@Composable
private fun PdfFailure(fileName: String, userMessage: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (fileName.isNotBlank()) {
                Text(
                    fileName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                userMessage,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("preview_error"),
            )
        }
    }
}

/** PDF 本地 UI 状态：比 [PreviewState] 多一层"下载/取缓存 → 打开渲染器"的阶段。 */
private sealed interface PdfUiState {
    data object Loading : PdfUiState
    data class Opened(val handle: PdfDocumentHandle) : PdfUiState
    data class Error(val userMessage: String) : PdfUiState
}
