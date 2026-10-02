package io.github.bileizhen.pan123x.core.transfer.upload.engine

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 流式 MD5。
 *
 * 逐块（1 MiB，参考源 `_MD5_READ_SIZE`，upload_service.py:24）喂给 `MessageDigest`，
 * 与参考源 `compute_file_md5`（upload_service.py:84-118）行为一致：
 * - 百分比 `pct = min(99, read_total * 100 / size)`，**仅在变化时**回调（:111-115），
 *   结束后无论文件大小补一次 100（:116-117）——因此空文件只回调一次 100；
 * - 返回值为 **小写** 32 位十六进制（:118 `hashlib.md5.hexdigest` 的等价物），
 *   它就是 `upload_request` 的 `etag`（:299），大小写必须与服务端比对习惯一致。
 *
 * 不切换调度器：阻塞读运行在调用方协程上下文上。生产环境引擎由协调器挂在应用级
 * IO 调度器 scope（见 UploadCoordinator KDoc），单测在 TestDispatcher 下完全确定。
 * 阻塞读无法被中断，靠每个 1 MiB 块之间的活性检查（[ensureActive]）响应取消；
 * [CancellationException] 直接传播，不吞不包装。
 */
object Md5Hasher {

    private const val READ_SIZE = 1024 * 1024

    /**
     * 消费 [stream]（读到 EOF，不负责关闭——调用方经 `use` 管理）并返回其 MD5。
     *
     * @param size 声明的来源大小，仅用于百分比计算；<= 0 时跳过中间百分比。
     * @param onPercent 百分比回调（0..99 变化时 + 结束补 100）。
     */
    suspend fun hash(stream: InputStream, size: Long, onPercent: (Int) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(READ_SIZE)
        var readTotal = 0L
        var lastPercent = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) {
                digest.update(buffer, 0, read)
                readTotal += read
                if (size > 0) {
                    // 参考源 :112：pct = min(99, read_total * 100 / fsize)，整数除法
                    val percent = (readTotal * 100 / size).toInt().coerceAtMost(99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onPercent(percent)
                    }
                }
            }
        }
        // 参考源 :116-117：结束后固定补一次 100
        onPercent(100)
        // 参考源 :118：hexdigest → 小写十六进制
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
