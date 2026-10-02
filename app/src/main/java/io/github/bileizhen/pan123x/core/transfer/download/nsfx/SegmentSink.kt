// Kotlin adaptation of Hanabi-Download-Manager-X NSFX via bileizhen/LeiFetch. SPDX-License-Identifier: GPL-3.0-only
//
// 有意偏离 LeiFetch（偏离 3）：
// LeiFetch 引擎直接用 `RandomAccessFile.seek + write` 写盘，与具体存储介质强耦合。
// 这里抽出 [SegmentSink] 定位写接缝：既支持应用内文件（[FileSegmentSink]），也支持
// SAF ParcelFileDescriptor（`FileChannel.write(ByteBuffer, position)`），
// 且定位写天然并发安全——多个分段 worker 可同时对同一介质写不同区间。
package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * NSFX 与存储介质之间的唯一接缝：定位写 + 刷盘 + 当前长度。
 *
 * **实现必须支持并发定位写**：多个分段 worker 会并行调用 [writeAt]，各自写入互不重叠的
 * 区间。实现不得使用「先 seek 再 write」这类依赖共享文件指针的方式（共享指针会被并发破坏），
 * 而应使用 `FileChannel.write(ByteBuffer, position)` 或 `pwrite` 之类的定位写。
 */
interface SegmentSink : java.io.Closeable {

    /**
     * 把 [buffer] 的前 [length] 字节写入绝对偏移 [offset] 处。
     *
     * 允许从任意偏移写入（不要求顺序），且必须可在多线程下并发调用。
     * @throws java.io.IOException 写入失败时抛出。
     */
    fun writeAt(offset: Long, buffer: ByteArray, length: Int)

    /** 将已写入的数据落盘（`fsync`）。 */
    fun sync()

    /** 返回介质当前长度；无法获知时返回 -1（例如部分 SAF 描述符）。 */
    fun currentLength(): Long
}

/**
 * 应用内目录的 [SegmentSink]：`FileChannel` 定位写（线程安全），创建时按 [totalSize] 预置长度。
 *
 * 预置长度的意义：多分段下载时，各 worker 直接向最终偏移写，无需等待前段；文件长度在开始时
 * 即等于最终大小，`currentLength` 可稳定用于断点一致性校验。
 *
 * @param file 目标文件。
 * @param totalSize 期望的最终文件长度；<0 表示不预置。
 */
class FileSegmentSink(private val file: File, private val totalSize: Long) : SegmentSink {

    private val raf: RandomAccessFile = RandomAccessFile(file, "rw").apply {
        if (totalSize >= 0) setLength(totalSize)
    }
    private val channel: FileChannel = raf.channel

    @Volatile
    private var closed = false

    override fun writeAt(offset: Long, buffer: ByteArray, length: Int) {
        require(length >= 0 && length <= buffer.size) { "写入长度越界" }
        if (length == 0) return
        val bb = ByteBuffer.wrap(buffer, 0, length)
        var position = offset
        // FileChannel 定位写不改变通道自身的 position，可被多线程并发调用。
        while (bb.hasRemaining()) {
            position += channel.write(bb, position)
        }
    }

    override fun sync() {
        if (!closed) channel.force(false)
    }

    override fun currentLength(): Long =
        if (closed) -1L else runCatching { channel.size() }.getOrDefault(-1L)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
        runCatching { raf.close() }
    }
}
