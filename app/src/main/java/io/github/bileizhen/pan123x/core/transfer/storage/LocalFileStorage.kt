// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.storage

import io.github.bileizhen.pan123x.core.transfer.download.nsfx.FileSegmentSink
import java.io.File
import java.io.RandomAccessFile

/**
 * 应用内私有目录的存储实现：`root` 由外部注入，因此整个类**不依赖任何 Android 类型**，
 * 可以在 JVM 单测里用临时目录把创建 / 续写 / 截断 / 丢弃 / 余量全部跑一遍。
 *
 * 为什么要有这条分支：SAF 授权可能被用户撤销、被系统回收，或者用户根本没选目录。
 * 应用内 `filesDir/downloads` 永远可写，是下载功能的保底落点，也是 M4 的默认目标。
 *
 * 与 LeiFetch `FilePublisher` 的关键差异：参考实现是「先下到暂存文件，再整体复制到目标」，
 * 会额外占用一份磁盘并且无法断点续写；这里让 NSFX 通过 [FileSegmentSink] **直接写进目标文件**，
 * 复制成本为零，半成品本身就是断点文件。
 */
class LocalFileStorage(private val root: File) {

    /**
     * 返回 [fileName] 净化后的目标文件；[root] 不存在时会惰性创建。
     *
     * 只暴露路径而不创建文件，是为了让调用方能在打开前先做余量检查，
     * 也方便测试断言「discard 之后文件确实不存在」。
     */
    fun pathFor(fileName: String): File {
        ensureRoot()
        return File(root, safeFileName(fileName))
    }

    /**
     * 打开（或复用）目标文件进行随机写。
     *
     * 复用而非截断是断点续传的前提：同名文件已存在时，
     * [FileSegmentSink] 只按 [totalSize] 预置长度，不会清空已下载的区间。
     * [totalSize] 为负数时按 0 处理，避免参考实现里 `setLength(-1)` 直接抛异常。
     */
    fun open(fileName: String, totalSize: Long): OpenedSink {
        val file = pathFor(fileName)
        file.parentFile?.mkdirs()
        val sink = FileSegmentSink(file, totalSize.coerceAtLeast(0L))
        return OpenedSink(fileUri(file), sink, onClose = { runCatching { sink.close() } })
            .also { it.localFile = file }
    }

    /**
     * 完成下载：把文件截断到精确的 [size]（部分引擎可能多写或少写尾字节），
     * 刷盘，关闭句柄，返回 `file://` uri（由 [DownloadStorage] 改写为 FileProvider uri）。
     *
     * `FileSegmentSink` 没有暴露截断能力，所以截断走一个独立的 [RandomAccessFile]，
     * 并且**在 sink 关闭之后**执行：句柄先释放再改长度，既不会与 sink 的通道争用，
     * 也保证任何一条异常路径都只留下已关闭的 fd（半开的 fd 泄漏比一次失败更糟）。
     */
    fun complete(opened: OpenedSink, size: Long): String {
        // 先刷盘再关闭：关闭之后句柄就不可用了，而 sync 失败意味着下载本身有问题，
        // 此时不应继续截断，直接向上抛出让协调器走失败流程。
        try {
            opened.sink.sync()
        } finally {
            opened.close()
        }
        val file = opened.localFile
        if (file != null && size >= 0) {
            // 截断在句柄关闭之后进行：Windows 上两个句柄同时操作同一文件会受共享模式约束，
            // 关闭后再截断既简单又不会与 sink 的通道争用。
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(size)
                // setLength 只改元数据，必须显式 fsync 才能保证断电后长度正确（数据安全）。
                raf.fd.sync()
            }
        }
        return opened.uri
    }

    /**
     * 放弃下载：关闭句柄并删除半成品文件。
     *
     * 只用于**未完成**的目标；调用方（DownloadCoordinator）不会对已完成的文件调用它。
     * 删除是尽力而为：文件可能已被外部清理或句柄被系统回收，任何失败都不向上抛，
     * 否则「取消下载」这个用户操作会因为清理失败而报错。
     */
    fun discard(opened: OpenedSink) {
        runCatching { opened.close() }
        val file = opened.localFile ?: return
        runCatching { if (file.exists()) file.delete() }
    }

    /**
     * 返回 [root] 所在卷的可用字节数；无法确定时返回 -1（未知不等于失败）。
     *
     * `usableSpace` 在卷不可用时返回 0，这里把 0 与负值统一归为「未知」，
     * 让上层能用同一个哨兵值判断「没有余量信息」而不是「磁盘真的满了」。
     */
    fun freeSpace(): Long {
        ensureRoot()
        val usable = runCatching { root.usableSpace }.getOrDefault(-1L)
        return if (usable > 0L) usable else -1L
    }

    private fun ensureRoot() {
        if (!root.exists()) root.mkdirs()
    }

    private fun fileUri(file: File): String = "file://" + file.absolutePath.replace('\\', '/')

    companion object {
        /** 兜底文件名：净化后为空时使用，保证目标永远有合法名字。 */
        const val DEFAULT_FILE_NAME = "download"

        /** 文件名单段上限。SAF provider（尤其 FAT/外置卡）对超长名字会直接拒绝创建。 */
        private const val MAX_NAME_LENGTH = 180

        /** 超过这个长度的「扩展名」不再视为扩展名，避免为了保住一长串后缀而把主体压到 1 个字符。 */
        private const val MAX_EXTENSION_LENGTH = 16

        private val RESERVED_CHARS = Regex("[/\\\\:*?\"<>|]")
        private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
        private val WHITESPACE = Regex("\\s+")

        /**
         * 把服务端文件名净化成 SAF 与应用内目录都接受的单段名字。
         *
         * 规则与理由：
         * - 删除 `/ \ : * ? " < > |` 与所有控制字符：既防路径穿越，
         *   也满足 FAT / SAF provider 的非法字符限制；
         * - 折叠连续空白并去掉首尾空白与点：Windows 与部分 provider 会静默丢弃结尾的点，
         *   导致「写完的文件名和记录不一致」，提前统一；
         * - 净化后为空或是 `.` / `..` 时回退到 [DEFAULT_FILE_NAME]：这两个名字在文件系统里是目录；
         * - 保留扩展名并整体截到 [MAX_NAME_LENGTH]：扩展名丢失会让系统无法选择正确的打开方式。
         */
        fun safeFileName(name: String): String {
            val cleaned = WHITESPACE.replace(
                CONTROL_CHARS.replace(RESERVED_CHARS.replace(name, ""), ""),
                " ",
            ).trim().trim('.', ' ')

            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return DEFAULT_FILE_NAME
            if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

            val dot = cleaned.lastIndexOf('.')
            val truncated = if (dot > 0 && cleaned.length - dot <= MAX_EXTENSION_LENGTH) {
                val extension = cleaned.substring(dot)
                val stem = cleaned.substring(0, MAX_NAME_LENGTH - extension.length).trimEnd('.', ' ')
                if (stem.isEmpty()) DEFAULT_FILE_NAME else stem + extension
            } else {
                cleaned.substring(0, MAX_NAME_LENGTH).trimEnd('.', ' ')
            }
            return truncated.ifEmpty { DEFAULT_FILE_NAME }
        }
    }
}
