// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SegmentSink
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SAF 存储层中**不引用任何 Android 类型**的纯逻辑部分。
 *
 * 单独抽成顶层 object 而不是 [SafStorage] 的伴生对象，是为了让 JVM 单测只加载这个类：
 * [SafStorage] 本身依赖 Context / DocumentFile，单测环境没有可用的 ContentResolver；
 * 把这些纯函数隔离出来后，扩展名映射与目录 uri 校验文案可以在 `app/src/test` 直接断言。
 */
internal object SafStorageRules {

    /** 目录 uri 根本不是合法的 SAF 树 uri。 */
    const val MESSAGE_TREE_INVALID = "保存位置无效，请重新选择目录"

    /** 用户撤销了目录授权，或系统回收了持久化权限（明确要求转成中文文案）。 */
    const val MESSAGE_PERMISSION_LOST = "保存位置权限已失效，请重新选择目录"

    /** 授权还在，但 provider 已无法解析该目录（存储被拔出、账户被移除等）。 */
    const val MESSAGE_TREE_UNAVAILABLE = "所选目录不可用，请重新选择目录"

    /** 目录存在但只读（例如某些云盘 provider 的只读挂载）。 */
    const val MESSAGE_NOT_WRITABLE = "所选目录不可写，请更换目录"

    /** 未知类型；宁可让系统按二进制处理，也不要猜错导致用错误的 App 打开。 */
    const val MIME_DEFAULT = "application/octet-stream"

    /** APK 必须显式声明，否则部分 provider 会把它当普通二进制并禁止安装。 */
    const val MIME_APK = "application/vnd.android.package-archive"

    private val MIME_TYPES: Map<String, String> = mapOf(
        // 视频
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "m4v" to "video/mp4",
        "3gp" to "video/3gpp",
        // 音频
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "wav" to "audio/x-wav",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "aac" to "audio/aac",
        "opus" to "audio/opus",
        // 图片
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "heic" to "image/heic",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        // 文档 / 文本
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "json" to "application/json",
        "xml" to "text/xml",
        "csv" to "text/csv",
        // 压缩包 / 安装包
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to MIME_APK,
    )

    /**
     * 扩展名 → MIME。
     *
     * SAF 的 `createFile(displayName, mimeType)` 用 MIME 决定文件类型与默认打开方式，
     * 传错会让系统把视频当文本；拿不准时回退 [MIME_DEFAULT] 比乱猜安全。
     * 无扩展名或未知扩展名一律回退，不做「嗅探」。
     */
    fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return MIME_DEFAULT
        return MIME_TYPES[extension] ?: MIME_DEFAULT
    }

    /**
     * 目录 uri 的静态校验：只检查字符串形状，不触碰 ContentResolver。
     *
     * 返回 [StorageCheck.Ok] 仅表示「格式像一棵 SAF 目录树」，**不代表可写**——
     * 真正的权限与可写性必须由 [SafStorage.checkTree] 在设备上确认。
     * 这样拆分的好处是：明显非法的输入（空串、`file://`、缺 `/tree/…/document/…` 段）
     * 可以在 JVM 单测里直接覆盖，而不必搭一套 instrumentation 环境。
     */
    fun staticCheck(treeUri: String): StorageCheck {
        val trimmed = treeUri.trim()
        if (trimmed.isEmpty()) return StorageCheck.Unavailable(MESSAGE_TREE_INVALID)

        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0 || !trimmed.substring(0, schemeEnd).equals("content", ignoreCase = true)) {
            return StorageCheck.Unavailable(MESSAGE_TREE_INVALID)
        }

        val authorityEnd = trimmed.indexOf('/', schemeEnd + 3)
        val authority = if (authorityEnd < 0) trimmed.substring(schemeEnd + 3) else trimmed.substring(schemeEnd + 3, authorityEnd)
        if (authority.isBlank()) return StorageCheck.Unavailable(MESSAGE_TREE_INVALID)

        // SAF 目录树 uri 形如 content://<authority>/tree/<rootId>/document/<rootId>；
        // 缺少 /tree/ 或 /document/ 的 uri 无法被 DocumentFile.fromTreeUri 解析。
        val path = if (authorityEnd < 0) "" else trimmed.substring(authorityEnd)
        if (!path.startsWith("/tree/")) return StorageCheck.Unavailable(MESSAGE_TREE_INVALID)
        if (!path.contains("/document/")) return StorageCheck.Unavailable(MESSAGE_TREE_INVALID)
        return StorageCheck.Ok
    }
}

/**
 * SAF 目标的 [SegmentSink] 实现。
 *
 * 为什么必须这么绕：SAF 只给出 [ParcelFileDescriptor]，没有真实路径，也不允许退化成
 * `/storage/emulated/0/...`。定位写用 `FileChannel.write(ByteBuffer, position)`，
 * 它不依赖通道自身的文件指针，因此多个分段 worker 可以并发写同一文件的不同区间。
 *
 * [currentLength] 优先取通道自身的 `size`（写入后会更新），再兜底 `pfd.statSize`
 * （打开时的快照）。两者都不可得时按契约返回 -1 表示「无法获知」，
 * 由上层退化为单连接，而不是误判为断点不一致。
 */
private class SafSegmentSink(
    private val descriptor: ParcelFileDescriptor,
    private val stream: FileOutputStream,
    private val channel: FileChannel,
) : SegmentSink {

    private val closed = AtomicBoolean(false)

    override fun writeAt(offset: Long, buffer: ByteArray, length: Int) {
        require(length >= 0 && length <= buffer.size) { "写入长度越界" }
        if (length == 0) return
        val byteBuffer = ByteBuffer.wrap(buffer, 0, length)
        var position = offset
        while (byteBuffer.hasRemaining()) {
            val written = channel.write(byteBuffer, position)
            // 定位写返回 0 说明介质不再推进；继续循环会变成死循环，直接失败让引擎重试。
            if (written <= 0) throw IOException("SAF 定位写入未取得进展")
            position += written
        }
    }

    override fun sync() {
        if (!closed.get()) channel.force(false)
    }

    override fun currentLength(): Long {
        if (closed.get()) return -1L
        // 优先用通道自身的 size：它是文件此刻的真实长度，会随写入推进；
        // pfd.statSize 是**打开时的快照**，写完不会更新，只适合作为兜底。
        val channelSize = runCatching { channel.size() }.getOrDefault(-1L)
        if (channelSize > 0L) return channelSize
        val statSize = runCatching { descriptor.statSize }.getOrDefault(-1L)
        return if (statSize > 0L) statSize else -1L
    }

    /** 尝试把文件截断到 [size]；provider 不支持时返回 false，由调用方降级为「只校验不截断」。 */
    fun truncate(size: Long): Boolean =
        runCatching {
            channel.truncate(size)
            true
        }.getOrDefault(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 顺序：通道 → 流 → 描述符。任一步失败都不能阻断后续释放，否则会泄漏 fd。
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * SAF（Storage Access Framework）存储层：把用户选择的目录树变成可随机写的下载目标。
 *
 * 设计要点与理由：
 * - **只用 Uri / ContentResolver / ParcelFileDescriptor / DocumentFile**，
 *   绝不把 `content://` 还原成真实路径去读写——那在 Android 10+ 上既不可靠也会触发 scoped storage 限制；
 * - 权限是**持久化**的：进程被杀后重新启动，授权仍在 `persistedUriPermissions` 里，
 *   因此恢复下载不需要用户重新选目录；一旦被撤销，[checkTree] 必须给出中文文案而不是抛异常；
 * - 同名文件**复用而不新建**：provider 的 `createFile` 遇到重名会生成 `name (1)`，
 *   断点续写会因此写到新文件、用户目录里也会堆满半成品；
 * - 所有失败路径返回 null 并记一条**不含 uri 查询参数**的警告日志，
 *   由上层统一转成用户可读文案。
 */
class SafStorage(private val context: Context, private val logger: AppLogger) {

    /**
     * 判断目录树是否「已持久化授权且可写」。
     *
     * 这是进入下载前的第一道闸门：只有拿到 [StorageCheck.Ok] 才允许写盘，
     * 否则返回的 [StorageCheck.Unavailable.userMessage] 会被上层直接展示。
     */
    fun checkTree(treeUri: String): StorageCheck {
        val static = SafStorageRules.staticCheck(treeUri)
        if (static is StorageCheck.Unavailable) return static

        val uri = runCatching { Uri.parse(treeUri.trim()) }.getOrNull()
            ?: return StorageCheck.Unavailable(SafStorageRules.MESSAGE_TREE_INVALID)
        if (!hasPermission(treeUri)) return StorageCheck.Unavailable(SafStorageRules.MESSAGE_PERMISSION_LOST)

        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return StorageCheck.Unavailable(SafStorageRules.MESSAGE_TREE_UNAVAILABLE)
        if (!root.exists() || !root.isDirectory) {
            return StorageCheck.Unavailable(SafStorageRules.MESSAGE_TREE_UNAVAILABLE)
        }
        if (!root.canWrite()) return StorageCheck.Unavailable(SafStorageRules.MESSAGE_NOT_WRITABLE)
        return StorageCheck.Ok
    }

    /**
     * 该目录树是否仍持有**读+写**的持久化授权。
     *
     * 下载必须同时具备读写：只读授权下 `openFileDescriptor(uri, "rw")` 会直接失败，
     * 提前判定可以避免把失败拖到下载中途。
     */
    fun hasPermission(treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri.trim()) }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.isWritePermission && permission.uri == uri
            }
        }.getOrDefault(false)
    }

    /**
     * 把 Activity 回调里拿到的授权**持久化**，使其跨进程重启有效。
     *
     * 逐个尝试「读写 / 仅写 / 仅读」三种 flag 组合：`takePersistableUriPermission` 要求
     * 传入的 flag 与 intent 实际授予的 flag 完全匹配，写死组合会让部分机型（只授予写）
     * 静默失败。全部失败时返回 false，由 UI 提示用户重新选择目录。
     */
    fun persistPermission(uri: Uri): Boolean {
        val resolver = context.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (flags in intArrayOf(read or write, write, read)) {
            val granted = runCatching {
                resolver.takePersistableUriPermission(uri, flags)
                true
            }.getOrDefault(false)
            if (granted) return true
        }
        logger.w(LogSource.DOWNLOAD, "保存目录授权持久化失败，请重新选择目录")
        return false
    }

    /**
     * 当前仍然**可写**的持久化目录树列表。
     *
     * 只返回可写项：设置页用它判断「上次选的保存位置是否还能用」，只读授权出现在列表里
     * 会让用户误以为可以保存。uri 只在内存中使用，不写日志。
     */
    fun persistedTrees(): List<String> = runCatching {
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { it.uri.toString() }
    }.getOrDefault(emptyList())

    /**
     * 估算目录树所在卷的可用字节数；无法确定时返回 -1。
     *
     * SAF 没有公开 API 能从 `content://` 目录直接读出余量，因此这里退一步：
     * 从 tree documentId（形如 `primary:Download`）解析出**卷名**，再用该卷上的应用外部目录做
     * [StatFs] 采样。这条路径只用于容量估算，**绝不用于文件读写**——读写始终走 ContentResolver。
     * 解析不出来（云盘类 provider）时返回 -1，上层按「未知」处理而不是判定磁盘已满。
     */
    fun freeSpace(treeUri: String): Long {
        val uri = runCatching { Uri.parse(treeUri.trim()) }.getOrNull() ?: return -1L
        val path = runCatching { volumePathFor(uri) }.getOrNull() ?: return -1L
        val available = runCatching { StatFs(path).availableBytes }.getOrDefault(-1L)
        return if (available > 0L) available else -1L
    }

    /**
     * 在目录树中打开（或复用）目标文件用于随机写；任何失败返回 null。
     *
     * [existingUri] 非空且**仍属于同一棵树、名字一致、可写**时直接复用，这是断点续写的路径。
     * 之所以要校验「同一棵树」：用户在恢复前可能改了保存位置，若仍按旧 uri 续写，
     * 数据会被写到用户已经放弃的目录里，用户看到的是「下载完成但目录里没有文件」。
     * 复用失败（权限失效 / 文件被删 / 换了目录）时不报错，而是退回到当前树里新建或复用同名文件。
     */
    fun openTree(treeUri: String, fileName: String, existingUri: String? = null): OpenedSink? {
        val safeName = LocalFileStorage.safeFileName(fileName)

        if (!existingUri.isNullOrBlank()) {
            openExistingDocument(treeUri, existingUri, safeName)?.let { return it }
        }

        val root = treeRoot(treeUri)
        if (root == null) {
            logger.w(LogSource.DOWNLOAD, "保存目录不可用，无法创建目标文件")
            return null
        }
        // 同名文件已存在时复用它，而不是让 provider 生成 "name (1)"。
        val existing = runCatching { root.findFile(safeName) }.getOrNull()
        val document = existing ?: runCatching { root.createFile(mimeFor(safeName), safeName) }.getOrNull()
        if (document == null || !document.exists()) {
            logger.w(LogSource.DOWNLOAD, "保存目录无法创建目标文件")
            return null
        }
        return openDocument(document)
    }

    /**
     * 收尾：尽力把文件截断到精确 [size]，刷盘并关闭句柄，成功返回可分享的 document uri。
     *
     * 截断是**尽力而为**的：少数 provider（尤其云盘类）不支持 `FileChannel.truncate`，
     * 此时按计划降级为「只校验长度」，不把「不支持截断」当成下载失败——
     * 数据已经写完，因为一次元数据操作而判定失败并删除文件才是真正的事故（数据安全优先）。
     * 无论哪条路径都会关闭 [opened]；关闭或刷盘失败时返回 null，由上层决定是否提示重试。
     */
    fun completeTree(opened: OpenedSink, size: Long): String? {
        var failed = false
        try {
            val sink = opened.sink
            if (sink is SafSegmentSink && size >= 0) {
                if (!sink.truncate(size)) {
                    val actual = sink.currentLength()
                    if (actual >= 0 && actual != size) {
                        logger.w(LogSource.DOWNLOAD, "保存位置不支持截断，文件长度与预期不一致")
                    }
                }
            }
            opened.sink.sync()
        } catch (e: IOException) {
            failed = true
            logger.w(LogSource.DOWNLOAD, "保存位置收尾失败：${e.javaClass.simpleName}")
        } finally {
            opened.close()
        }
        return if (failed) null else opened.uri
    }

    /**
     * 放弃下载：关闭句柄并删除半成品 document。
     *
     * 只用于**未完成**的目标；调用方不会对已完成文件调用它。删除是尽力而为，
     * 永不抛出——「取消下载」不应该因为清理失败而给用户报错。
     */
    fun discardTree(opened: OpenedSink) {
        runCatching { opened.close() }
        val uri = runCatching { Uri.parse(opened.uri) }.getOrNull() ?: return
        runCatching {
            val document = DocumentFile.fromSingleUri(context, uri) ?: return@runCatching
            if (document.exists()) document.delete()
        }.onFailure {
            logger.w(LogSource.DOWNLOAD, "残留文件清理失败：${it.javaClass.simpleName}")
        }
    }

    /**
     * 文件扩展名对应的 MIME：`createFile` 需要它才能让系统正确归类文件。
     * 纯逻辑委托给 [SafStorageRules]，因此映射表本身可在 JVM 单测里断言。
     */
    fun mimeFor(fileName: String): String = SafStorageRules.mimeTypeFor(fileName)

    /** 解析目录树为 [DocumentFile]；非法或不可用时返回 null，由调用方转成中文文案。 */
    private fun treeRoot(treeUri: String): DocumentFile? {
        val uri = runCatching { Uri.parse(treeUri.trim()) }.getOrNull() ?: return null
        if (!runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) return null
        return runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    }

    /** 复用断点目标；uri 不属于 [treeUri]、名字不符或不可写时返回 null（由调用方回退到新建）。 */
    private fun openExistingDocument(treeUri: String, existingUri: String, safeName: String): OpenedSink? {
        val uri = runCatching { Uri.parse(existingUri) }.getOrNull() ?: return null
        if (!belongsToTree(treeUri, uri)) return null
        val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull() ?: return null
        if (!document.isFile || !document.exists() || !document.canWrite()) return null
        if (document.name != safeName) return null
        return openDocument(document)
    }

    /** 判断 [documentUri] 是否位于 [treeUri] 这棵树内：authority 与 tree documentId 都必须一致。 */
    private fun belongsToTree(treeUri: String, documentUri: Uri): Boolean {
        val tree = runCatching { Uri.parse(treeUri.trim()) }.getOrNull() ?: return false
        if (tree.authority != documentUri.authority) return false
        val expected = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        val actual = runCatching { DocumentsContract.getTreeDocumentId(documentUri) }.getOrNull() ?: return false
        return expected == actual
    }

    /** 以 "rw" 打开 document 并包成 [SafSegmentSink]；描述符或流建立失败时关闭已获取的资源并返回 null。 */
    private fun openDocument(document: DocumentFile): OpenedSink? {
        val uri = document.uri
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (descriptor == null) {
            logger.w(LogSource.DOWNLOAD, "保存位置无法以读写方式打开目标文件")
            return null
        }
        val stream = runCatching { FileOutputStream(descriptor.fileDescriptor) }.getOrElse {
            runCatching { descriptor.close() }
            logger.w(LogSource.DOWNLOAD, "保存位置无法建立写入通道")
            return null
        }
        val sink = SafSegmentSink(descriptor, stream, stream.channel)
        return OpenedSink(uri.toString(), sink, onClose = { sink.close() })
    }

    /**
     * 把 tree documentId 还原成「卷上的应用外部目录」，仅用于 [StatFs] 余量采样。
     * 返回 null 表示无法定位卷（云盘 provider、非标准 documentId），调用方按 -1 处理。
     */
    private fun volumePathFor(uri: Uri): String? {
        if (!runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0) return null
        val volume = documentId.substring(0, separator)
        val directories = runCatching { context.getExternalFilesDirs(null) }.getOrNull() ?: return null
        val candidates = directories.filterNotNull()
        return if (volume.equals("primary", ignoreCase = true)) {
            candidates.firstOrNull()?.absolutePath
        } else {
            candidates.firstOrNull { it.absolutePath.contains("/$volume/") }?.absolutePath
        }
    }
}
