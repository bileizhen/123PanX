package io.github.bileizhen.pan123x.core.transfer.upload

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 上传来源的 SAF 实现。
 *
 * 为什么包一层而不是直接把 Uri 传给引擎：[UploadSource] 的冻结接口不暴露 Uri，engine 只见
 * InputStream / 定位读句柄，因此可在纯 JVM 全量单测；Android 侧的 uri 细节被隔离在本类。
 * [UploadSourceLocator] 让 [UploadCoordinator] 能把 uri 持久化到 `transfer_tasks.targetUri`，
 * 供进程重启后由 TransferRepository 经 `ContentUriUploadSource.create` 重建来源（断点续传）。
 *
 * [lastModified] 查不到时为 0：协调器按"0 = 不校验 mtime"处理（，宁可重算 MD5 也不因
 * 拿不到 mtime 拒绝续传）。SAF 文档的 lastModified 精度与可得性因 provider 而异，不能作为强依赖。
 *
 * [openRandomAccess] 基于 `Os.pread`：**无状态偏移读**，天然并发安全——多个分片 worker 同时
 * readAt 不同区间互不干扰，满足 [RandomAccessReader] 的线程安全契约；fd 打不开时返回 null，
 * 由引擎降级为单线程顺序上传（"不支持随机读降级"）。
 */
class ContentUriUploadSource(
    private val context: Context,
    val uri: Uri,
    override val displayName: String,
    override val size: Long,
    override val lastModified: Long,
) : UploadSource, UploadSourceLocator {

    override val sourceUri: String
        get() = uri.toString()

    /** 顺序读（MD5 校验用）；provider 打不开时抛 [FileNotFoundException]，上层映射为"来源不可读"。 */
    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("provider 返回空流：$displayName")

    /** 定位读句柄（分片并行 PUT 用）；SAF 拒绝 fd 时返回 null → 引擎降级单线程顺序上传。 */
    override fun openRandomAccess(): RandomAccessReader? {
        val pfd: ParcelFileDescriptor? = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (failure: Exception) {
            // 文件被删 / 权限回收 / provider 不支持 fd：按"不支持随机读"降级，不抛给引擎。
            null
        }
        return pfd?.let(::PfdRandomAccessReader)
    }

    /** [Os.pread] 是无状态偏移读，天然并发安全（线程安全契约）。 */
    private class PfdRandomAccessReader(private val pfd: ParcelFileDescriptor) : RandomAccessReader {

        override fun readAt(offset: Long, length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val buffer = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = try {
                    Os.pread(pfd.fileDescriptor, buffer, filled, length - filled, offset + filled)
                } catch (errno: ErrnoException) {
                    // ErrnoException 不是 IOException：统一包装，让引擎按 IO 失败走重试/失败映射。
                    throw IOException("分片定位读失败：${errno.message}", errno)
                }
                if (read <= 0) break // 到达文件尾：允许短读，调用方以返回长度为准
                filled += read
            }
            return if (filled == length) buffer else buffer.copyOf(filled)
        }

        override fun close() {
            pfd.close()
        }
    }

    companion object {

        /**
         * 解析 SAF uri 为上传来源：query [OpenableColumns.DISPLAY_NAME]/[SIZE] 取名称与大小，
         * [DocumentsContract] 元数据取 lastModified；**不可读返回 null**（size 兜底也拿不到时）。
         *
         * IO（query / openFileDescriptor）在 [Dispatchers.IO] 上执行，避免调用方（主线程的
         * enqueue / resume 入口）触发磁盘 binder 调用。
         */
        suspend fun create(context: Context, uri: Uri): ContentUriUploadSource? =
            withContext(Dispatchers.IO) { resolve(context, uri) }

        private fun resolve(context: Context, uri: Uri): ContentUriUploadSource? {
            val resolver = context.contentResolver
            var queriedName: String? = null
            var queriedSize = -1L
            val name = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0) queriedName = cursor.getString(nameColumn)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) queriedSize = cursor.getLong(sizeColumn)
                        }
                    }
                queriedName?.takeIf { it.isNotBlank() }
            } catch (failure: Exception) {
                // 部分 provider 不支持 OpenableColumns 投影：名称回退 lastPathSegment，大小走 statSize 兜底。
                null
            } ?: uri.lastPathSegment

            val lastModified = queryLastModified(context, uri)
            val size = if (queriedSize >= 0) queriedSize else statSize(resolver, uri) ?: return null
            val displayName = name ?: return null
            return ContentUriUploadSource(context, uri, displayName, size, lastModified)
        }

        /**
         * 文档 uri 的 lastModified；非文档 uri（如 MediaStore / file uri）或查询失败一律返回 0
         * （：0 = 不校验 mtime，宁可重算 MD5 也不拒绝续传）。
         */
        private fun queryLastModified(context: Context, uri: Uri): Long {
            if (!DocumentsContract.isDocumentUri(context, uri)) return 0L
            return try {
                var value = 0L
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (column >= 0 && !cursor.isNull(column)) value = cursor.getLong(column)
                    }
                }
                value
            } catch (failure: Exception) {
                // provider 不支持 LAST_MODIFIED 列：按拿不到 mtime 处理（0）。
                0L
            }
        }

        /** size 缺失（-1/null）时的 statSize 兜底；fd 打不开即视为不可读，返回 null。 */
        private fun statSize(resolver: ContentResolver, uri: Uri): Long? = try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        } catch (failure: Exception) {
            null
        }
    }
}
