// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.storage

import io.github.bileizhen.pan123x.core.transfer.download.nsfx.SegmentSink
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下载保存位置。
 *
 * 之所以用 sealed interface 而不是「可空 treeUri 字符串」，是为了让分派器在编译期穷举两条分支：
 * 应用内分支可以安全地使用 [java.io.File]，SAF 分支只能走 Uri / ContentResolver，
 * 二者不可能被写混。SAF 分支存在的前提是用户显式选过目录并授予了持久化权限；
 * 未授权时 [DownloadStorage.check] 会给出 [StorageCheck.Unavailable]，而不是静默回退。
 */
sealed interface DownloadDestination {

    /**
     * 应用内私有目录（`filesDir/downloads`）：默认目标，不需要任何运行时权限，也是 SAF 授权失效时的回退。
     * [fileName] 只是建议名，落盘前一定会经过 [LocalFileStorage.safeFileName] 净化。
     */
    data class Internal(val fileName: String) : DownloadDestination

    /**
     * 用户通过 SAF 选择的目录树。
     * [treeUri] 必须是已持久化授权的 `content://…/tree/…/document/…` uri；未授权时不可写入。
     */
    data class Tree(val treeUri: String, val fileName: String) : DownloadDestination
}

/**
 * 保存位置的可用性结论。
 *
 * 失败必须携带**用户可读的中文文案**：上层直接展示这句话，不允许把 SAF 异常、
 * 错误码或英文堆栈透传给用户。文案在这里统一给出，是为了让「权限失效」与
 * 「目录不可写」在所有调用点表现一致。
 */
sealed interface StorageCheck {
    /** 位置可用（应用内目录恒为可用；SAF 目录表示已授权且可写）。 */
    data object Ok : StorageCheck

    /** 位置不可用；[userMessage] 是可直接展示给用户的中文原因。 */
    data class Unavailable(val userMessage: String) : StorageCheck
}

/**
 * 已打开的随机写目标——NSFX 引擎与存储层之间传递的唯一句柄。
 *
 * [uri] 是最终（或待发布）文件的标识：应用内目标在 [DownloadStorage] 中被改写为 FileProvider uri
 * （可安全持久化与分享），SAF 目标就是 provider 返回的 document uri。
 * 恢复下载时，这个字符串会被写回 Room 的 `targetUri`，再作为 `existingUri` 传回 [DownloadStorage.open]。
 *
 * [onClose] 被设计成**幂等**：NSFX 引擎在成功、取消、异常三条路径上都会关闭 sink，
 * 重复关闭必须无害，否则会重复删除半成品或重复释放 fd。
 *
 * 构造函数严格保持接口约定的三个参数，因此应用内目标的真实文件
 * 通过 [localFile] 这个**内部可变属性**携带，而不是新增构造参数：分派器把 [uri] 改写成
 * content:// 之后就再也无法从 uri 反推出真实路径，而 [LocalFileStorage.complete] 需要真实
 * [File] 才能把文件截断到精确长度。它由存储层在构造后立刻写入、在交付给调用方之前完成赋值，
 * 用 `@Volatile` 保证多线程可见性。
 */
class OpenedSink(
    val uri: String,
    val sink: SegmentSink,
    private val onClose: () -> Unit,
) : Closeable {

    private val closed = AtomicBoolean(false)

    /** 应用内目标对应的真实文件；SAF 目标恒为 null。仅存储层内部使用，不进入对外契约。 */
    @Volatile
    internal var localFile: File? = null

    /** 关闭底层介质；多次调用只有第一次生效。 */
    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}
