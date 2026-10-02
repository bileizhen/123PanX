// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.storage

import android.content.Context
import androidx.core.content.FileProvider
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import java.io.File

/**
 * 下载存储分派器——DownloadCoordinator 唯一需要认识的存储入口。
 *
 * 存在的意义是把「应用内目录」与「SAF 目录」两条完全不同的技术路径收敛成一组方法：
 * 协调器只持有 [DownloadDestination]，不需要知道什么时候该用 [File]、
 * 什么时候必须走 ContentResolver，也不需要知道应用内的 uri 需要经过 FileProvider。
 *
 * 应用内分支的 uri 会被改写成 `FileProvider.getUriForFile(context, "<packageName>.files", file)`：
 * `file://` uri 一旦跨进程（分享、通知、外部播放器）就会触发 FileUriExposedException，
 * 而 content:// uri 才能安全持久化与分享。
 * FileProvider 由应用在 manifest 中声明；若尚未接线，这里降级为 `file://` 并记一条警告，
 * 绝不因为一个配置缺失把整个下载流程炸掉（可靠性优先）。
 */
class DownloadStorage(private val context: Context, private val logger: AppLogger) {

    /** SAF 分支；对外暴露是为了让设置页/权限回调直接复用授权与目录校验逻辑。 */
    val saf: SafStorage = SafStorage(context, logger)

    private val internalStorage = LocalFileStorage(internalRoot())

    /**
     * 判断目标位置当前是否可写。
     *
     * 应用内目录恒为可用（[StorageCheck.Ok]）：它是保底落点，不需要任何运行时权限。
     * SAF 目标必须已持久化授权且可写，否则返回中文文案由 UI 直接展示。
     */
    fun check(destination: DownloadDestination): StorageCheck = when (destination) {
        is DownloadDestination.Internal -> StorageCheck.Ok
        is DownloadDestination.Tree -> saf.checkTree(destination.treeUri)
    }

    /**
     * 打开（或复用）目标文件用于随机写；失败返回 null。
     *
     * [existingUri] 只在 SAF 分支有意义：它来自上次持久化的 `targetUri`，
     * 用于断点续写。应用内分支忽略它——应用内目标路径由文件名唯一决定，
     * 天然就是「续写而不是新建」，因此重复传入同一个 uri 不会造成任何副作用。
     */
    fun open(
        destination: DownloadDestination,
        totalSize: Long,
        existingUri: String? = null,
    ): OpenedSink? = when (destination) {
        is DownloadDestination.Internal -> openInternal(destination.fileName, totalSize)
        is DownloadDestination.Tree -> saf.openTree(destination.treeUri, destination.fileName, existingUri)
    }

    /**
     * 完成下载并返回**可分享**的 uri 字符串；失败返回 null。
     *
     * 无论成功与否，[opened] 都会被关闭（各分支内部保证），调用方不需要再关闭一次。
     */
    fun complete(opened: OpenedSink, destination: DownloadDestination, size: Long): String? = when (destination) {
        is DownloadDestination.Internal -> completeInternal(opened, destination.fileName, size)
        is DownloadDestination.Tree -> saf.completeTree(opened, size)
    }

    /**
     * 放弃下载：关闭句柄并删除**半成品**。
     *
     * 只允许对未完成的目标调用；已完成的文件不会被本方法触及（协调器只在失败/取消路径调用它），
     * 这是 「数据安全优先」的直接体现——取消下载绝不能误删已下好的文件。
     */
    fun discard(opened: OpenedSink, destination: DownloadDestination) {
        when (destination) {
            is DownloadDestination.Internal -> internalStorage.discard(opened)
            is DownloadDestination.Tree -> saf.discardTree(opened)
        }
    }

    /**
     * 目标位置所在卷的可用字节数；无法确定时返回 -1。
     *
     * 上层用 -1 表示「没有余量信息」，只有当返回值 >= 0 且小于待下载大小时才判定磁盘不足，
     * 避免在 provider 不报余量时把正常下载误判成磁盘满。
     */
    fun freeSpace(destination: DownloadDestination): Long = when (destination) {
        is DownloadDestination.Internal -> internalStorage.freeSpace()
        is DownloadDestination.Tree -> saf.freeSpace(destination.treeUri)
    }

    /** 应用内下载根目录（`filesDir/downloads`）；供测试与诊断使用，不参与业务判断。 */
    fun internalRoot(): File = File(context.filesDir, "downloads")

    private fun openInternal(fileName: String, totalSize: Long): OpenedSink? {
        val opened = runCatching { internalStorage.open(fileName, totalSize) }.getOrElse {
            logger.w(LogSource.DOWNLOAD, "应用内下载目录不可用：${it.javaClass.simpleName}")
            return null
        }
        val file = opened.localFile ?: return opened
        // 改写 uri 但保留同一个 sink 与 close 动作：关闭新句柄等于关闭原句柄，不会泄漏 fd。
        return OpenedSink(shareUri(file), opened.sink, onClose = { opened.close() })
            .also { it.localFile = file }
    }

    private fun completeInternal(opened: OpenedSink, fileName: String, size: Long): String? {
        runCatching { internalStorage.complete(opened, size) }.getOrElse {
            logger.w(LogSource.DOWNLOAD, "应用内文件收尾失败：${it.javaClass.simpleName}")
            return null
        }
        val file = opened.localFile ?: internalStorage.pathFor(fileName)
        return shareUri(file)
    }

    /**
     * 把应用内文件转成可跨进程分享的 uri。
     * FileProvider 未声明时降级为 `file://`，并记警告——降级后仍能完成下载，只是无法分享。
     */
    private fun shareUri(file: File): String = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file).toString()
    }.getOrElse {
        logger.w(LogSource.DOWNLOAD, "FileProvider 未配置，应用内文件暂用本地标识：${it.javaClass.simpleName}")
        "file://" + file.absolutePath.replace('\\', '/')
    }
}
