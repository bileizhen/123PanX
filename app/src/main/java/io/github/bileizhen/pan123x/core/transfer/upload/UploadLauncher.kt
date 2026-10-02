package io.github.bileizhen.pan123x.core.transfer.upload

import android.content.Context
import android.net.Uri
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.transfer.upload.engine.ConflictPolicy
import io.github.bileizhen.pan123x.data.transfer.TransferRepository
import kotlinx.coroutines.CancellationException

/**
 * 上传发起结果：成功只回传文件名（进度看传输工作台 / 通知），失败回传用户可读文案。
 * 与下载的 [io.github.bileizhen.pan123x.core.transfer.download.LaunchOutcome] 对称。
 */
sealed interface UploadEnqueueOutcome {
    data class Queued(val fileName: String) : UploadEnqueueOutcome
    data class Failed(val userMessage: String) : UploadEnqueueOutcome
}

/**
 * 文件页"上传"入口的窄接口（分层约束：UI / ViewModel 不得直接依赖 SAF、S3 或 Room）。
 *
 * 抽成接口的理由与 `DownloadLauncher` 相同：`FilesViewModel` 单测只需注入假实现，无需构造
 * 真实的 `TransferRepository`。参数用 uri **字符串**而非 `Uri`，让 ViewModel 保持纯 JVM 可测。
 */
fun interface UploadLauncher {

    /** 把一个本地 SAF uri 加入上传队列；实现负责解析来源与登记后台执行外壳。 */
    suspend fun launch(uriString: String, parentDirId: Long): UploadEnqueueOutcome
}

/**
 * 生产实现：SAF uri → [ContentUriUploadSource]（MD5 / 分片读都在引擎里，此处只解析元数据）
 * → [TransferRepository.enqueueUpload]（内部已含"先落库再执行"与后台外壳登记）。
 *
 * 冲突策略固定 [ConflictPolicy.ASK]：同名冲突转 WAITING_USER 后由传输工作台的弹窗让用户选择
 * 保留两者 / 覆盖（duplicate=0 语义），文件页不就地打断用户。
 */
class DefaultUploadLauncher(
    private val context: Context,
    private val repository: TransferRepository,
    private val logger: AppLogger,
) : UploadLauncher {

    override suspend fun launch(uriString: String, parentDirId: Long): UploadEnqueueOutcome {
        return try {
            val source = ContentUriUploadSource.create(context, Uri.parse(uriString))
                ?: return UploadEnqueueOutcome.Failed(UploadMessages.SOURCE_UNREADABLE)
            repository.enqueueUpload(source, parentDirId, ConflictPolicy.ASK)
            logger.i(LogSource.UPLOAD, "已加入上传队列：${source.displayName}")
            UploadEnqueueOutcome.Queued(source.displayName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IllegalStateException) {
            // enqueueUpload 在未登录时抛 IllegalStateException(UploadMessages.NOT_LOGGED_IN)
            logger.w(LogSource.UPLOAD, "加入上传队列失败：未登录")
            UploadEnqueueOutcome.Failed(error.message ?: UploadMessages.NOT_LOGGED_IN)
        } catch (error: Exception) {
            logger.e(LogSource.UPLOAD, "加入上传队列失败：${error.javaClass.simpleName}")
            UploadEnqueueOutcome.Failed(UploadMessages.ENQUEUE_FAILED)
        }
    }
}
