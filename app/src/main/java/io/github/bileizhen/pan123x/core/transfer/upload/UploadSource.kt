package io.github.bileizhen.pan123x.core.transfer.upload

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 上传来源抽象：把"字节从哪里来"与"怎么传"解耦，
 * `engine` 包只见本接口，因此 `Uri` / `ContentResolver` 不会泄漏进
 * `core/transfer/upload/engine`（PLAN  硬约束），引擎可在纯 JVM 下全量单测。
 *
 * - [openStream]：顺序读，供 MD5 校验（的"校验"步骤）；调用方负责关闭。
 * - [openRandomAccess]：定位读句柄，供分片并发读取；返回 null 表示该来源不支持随机读
 *   （部分 SAF provider 如此），引擎降级为单 worker 顺序上传。
 *
 * [lastModified] 取不到时返回 0（0 视为"不校验 mtime"，
 * 宁可重算 MD5 也不能因为拿不到 mtime 就拒绝续传）。
 */
interface UploadSource {

    /** 展示用文件名（不含路径），写入 `upload_request` 的 `fileName`（upload_service.py:300）。 */
    val displayName: String

    /** 字节总数；空文件为 0（：0 片，跳过分片循环直接走 complete 流程）。 */
    val size: Long

    /** 来源修改时间（毫秒）；取不到返回 0（：0 = 不校验 mtime）。 */
    val lastModified: Long

    /** 顺序读（MD5 用）；调用方负责关闭。 */
    fun openStream(): InputStream

    /** 定位读句柄；null = 不支持随机读，引擎降级单线程顺序上传。 */
    fun openRandomAccess(): RandomAccessReader?
}

/**
 * 定位读接缝。实现**必须线程安全**：多个分片 worker 会并发 [readAt] 互不重叠的区间
 * 。文件尾允许读不满 [length]。
 */
interface RandomAccessReader : Closeable {

    /**
     * 从 [offset] 起读**最多** [length] 字节，返回实际读到的字节；
     * 只有文件尾可少于 [length]，中途不足视为来源损坏，由调用方判为失败。
     */
    fun readAt(offset: Long, length: Int): ByteArray
}

/**
 * [UploadSource] 的 JVM 实现（本地 [File]，供单元测试与文件路径来源；Android 侧 SAF 的
 * `ContentUriUploadSource` 由数据层另行提供—— 禁止依赖真实
 * `/storage/emulated/0/...` 路径作为主来源，本类刻意不引用任何 Android API）。
 *
 * [RandomAccessReader] 用单个 `RandomAccessFile`：`seek + read` 非原子，故 [readAt] 内
 * synchronized 串行化——并发 worker 的读会排队，但语义安全；5 MiB 分片（固定
 * `UploadPartPlan.BLOCK_SIZE`，upload_service.py:267）的本地读耗时远小于网络 PUT，不构成瓶颈。
 */
class FileUploadSource(private val file: File) : UploadSource {

    override val displayName: String get() = file.name

    override val size: Long get() = file.length()

    /** `File.lastModified` 在取不到（如文件已不存在）时本就返回 0，符合  "0 = 不校验"。 */
    override val lastModified: Long get() = file.lastModified()

    override fun openStream(): InputStream = file.inputStream()

    override fun openRandomAccess(): RandomAccessReader = SingleFileReader(file)

    private class SingleFileReader(file: File) : RandomAccessReader {
        private val handle = RandomAccessFile(file, "r")
        private val lock = Any()
        private val closed = AtomicBoolean(false)

        override fun readAt(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0) { "非法读取区间：offset=$offset, length=$length" }
            synchronized(lock) {
                handle.seek(offset)
                if (length == 0) return ByteArray(0)
                val out = ByteArray(length)
                var filled = 0
                while (filled < length) {
                    val read = handle.read(out, filled, length - filled)
                    if (read < 0) break // 文件尾：契约允许少于 length
                    filled += read
                }
                return if (filled == length) out else out.copyOf(filled)
            }
        }

        override fun close() {
            // 关闭必须幂等：引擎在 finally 中关闭，失败路径可能触发多次
            if (closed.compareAndSet(false, true)) {
                handle.close()
            }
        }
    }
}
