// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.preview

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * PDF 超过预览大小上限（大文件避免一次性全量加载）。
 *
 * [maxBytes] 是调用方传入的上限值，UI 层用它生成"建议下载后打开"的文案；
 * 异常消息固定中文且**不含 URL**（signed URL 不进日志与文案）。
 */
class PdfTooLargeException(val maxBytes: Long) : IOException("PDF 超过预览大小上限")

/**
 * PDF 预览的本地缓存层（签名冻结）。
 *
 * 为什么 PDF 要落盘而图片/视频直接走直链：PdfRenderer 只能读本地 fd，
 * 无法像 Media3 那样流式输入，因此必须先拿到完整文件；缓存放在 `cacheDir/previews/`
 * 可随系统"清除缓存"一起回收，不占用户数据空间。
 *
 * 网络职责边界：请求用**构造注入的 client**——生产接线传 TransferClient，
 * 本类绝不添加 authorization / loginuuid 等 API 头，也不做 API 重试语义
 * （GET 单次执行，失败由调用方决定是否重试）。
 *
 * 纯 JVM 可测：只依赖 File + OkHttpClient，测试直接用 MockWebServer 真实客户端。
 *
 * @param cacheDir 缓存根目录；实际文件位于 `<cacheDir>/previews/<fileId>.pdf`。
 * @param client 传输专用客户端（生产接线传 TransferClient，不带 API 认证头）。
 */
class PdfPreviewCache(private val cacheDir: File, private val client: OkHttpClient) {

    /** 预览子目录。 */
    private val previewsDir: File get() = File(cacheDir, PREVIEWS_DIR)

    private fun finalFile(fileId: Long): File = File(previewsDir, "$fileId.pdf")

    /**
     * 已缓存的本地副本：**存在且大小与 [expectedSize] 一致**才返回。
     *
     * 大小是唯一廉价的一致性校验：云盘文件刷新后 fileId 可能复用而内容不同，
     * 用列表行携带的 size 比对可以挡住绝大多数陈旧缓存；更重的校验（etag/哈希）
     * 对"随时可清除的预览缓存"收益过低（可靠性优先于极致正确）。
     * `expectedSize <= 0`（未知大小）时永不命中，宁可重下也不返回可疑副本。
     */
    fun cachedFile(fileId: Long, expectedSize: Long): File? {
        val target = finalFile(fileId)
        return if (expectedSize > 0 && target.isFile && target.length() == expectedSize) target else null
    }

    /**
     * 取得可渲染的本地 PDF：命中缓存直接返回；否则下载到 `<cacheDir>/previews/<fileId>.pdf`。
     *
     * @param fileId 云端文件 id，同时是缓存文件名（id 在单账户内稳定）。
     * @param url 直链（短期 signed URL，只用于本次请求，绝不落盘/落日志）。
     * @param expectedSize 云端报告的大小；仅用于缓存命中校验与超限预判，
     *   与响应 Content-Length 不符时**以实际写入为准**（CDN 可能对大小口径不一致，
     *   因元数据差异丢弃一个完整可渲染的文件得不偿失）。
     * @param maxBytes 预览大小上限；超过立即中止。非正值视为不设限——
     *   防御设置项异常值（0/负数）导致所有 PDF 必然预览失败。
     * @return 可交给 [android.graphics.pdf.PdfRenderer] 的本地文件。
     * @throws PdfTooLargeException 超过 [maxBytes]（预判或边下边计数触发）。
     * @throws IOException 网络/HTTP/写盘失败；消息为中文且不含 URL。
     */
    suspend fun ensure(fileId: Long, url: String, expectedSize: Long, maxBytes: Long): File {
        cachedFile(fileId, expectedSize)?.let { return it }
        val limit = if (maxBytes > 0) maxBytes else Long.MAX_VALUE
        // 超限预判：云端已报告大小超限就不发任何请求，一字节流量都不浪费。
        if (expectedSize > limit) throw PdfTooLargeException(limit)
        return withContext(Dispatchers.IO) { download(fileId, url, limit) }
    }

    /**
     * 下载主体（已在 IO dispatcher 上）。
     *
     * 关键决策：
     * - **`.part` 临时名写完再 rename**：半截文件绝不会被 [cachedFile] 误判为已缓存；
     * - **边下边计数**：写入量超过上限立即抛 [PdfTooLargeException] 并断开连接，
     *   绝不"下载完再判断"（大文件不全量加载）；
     * - **协程取消传播**：注册 job 回调在取消时 cancel 在途 Call，解除阻塞中的 read
     *   （与 NsfxHttpClient 同款模式），并在每个 chunk 检查 [ensureActive]；
     * - **异常消息不含 URL**：signed URL 带 query 参数，禁止进异常/日志。
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun download(fileId: Long, url: String, maxBytes: Long): File {
        previewsDir.mkdirs()
        val target = finalFile(fileId)
        val partFile = File(previewsDir, "$fileId.pdf.part")
        // 上次异常退出可能残留 .part；FileOutputStream 会截断覆写，但先删干净更卫生。
        partFile.delete()
        // 纯净 GET：不加任何头（尤其 authorization/loginuuid），预览流量与 API 会话无关。
        val call = client.newCall(Request.Builder().url(url).build())
        // 取消时立刻断开在途连接，解除 IO 线程上阻塞的 read（InternalCoroutinesApi 与 NSFX 同款）。
        val cancellation = currentCoroutineContext().job.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("服务器返回 HTTP ${response.code}，无法加载 PDF 预览")
                }
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val body = response.body ?: throw IOException("服务器未返回内容，无法加载 PDF 预览")
                var written = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > maxBytes) throw PdfTooLargeException(maxBytes)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                // 有 Content-Length 时做完整性校验：不符说明连接被截断，
                // 半截 PDF 无法渲染，删除并报错（expectedSize 不参与此判定，见 [ensure]）。
                if (contentLength >= 0 && written != contentLength) {
                    throw IOException("下载不完整，请稍后重试")
                }
                if (!partFile.renameTo(target)) {
                    // 个别文件系统 rename 失败（目标被占用等）：清掉旧目标重试一次。
                    target.delete()
                    if (!partFile.renameTo(target)) throw IOException("无法保存 PDF 预览缓存")
                }
                return target
            }
        } catch (t: Throwable) {
            // 任何失败路径都清掉半成品；.part 永不参与缓存命中。
            partFile.delete()
            // 因协程取消中断时优先抛 CancellationException，保持取消语义。
            currentCoroutineContext().ensureActive()
            throw t
        } finally {
            cancellation.dispose()
        }
    }

    private companion object {
        const val PREVIEWS_DIR = "previews"
        const val BUFFER_BYTES = 64 * 1024
    }
}
