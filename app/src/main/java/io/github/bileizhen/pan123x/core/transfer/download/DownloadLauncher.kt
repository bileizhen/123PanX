package io.github.bileizhen.pan123x.core.transfer.download

import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadDestination
import io.github.bileizhen.pan123x.core.transfer.storage.DownloadStorage
import kotlinx.coroutines.CancellationException

/**
 * 下载发起结果：成功只回传文件名（进度看传输工作台 / 通知），失败回传用户可读文案。
 */
sealed interface LaunchOutcome {
    data class Queued(val fileName: String) : LaunchOutcome
    data class Failed(val userMessage: String) : LaunchOutcome
}

/**
 * 文件页"下载"入口的窄接口（分层约束：UI / ViewModel 不得直接依赖 NSFX、SAF 或 Room）。
 *
 * 抽成接口的第二个理由是可测性：`FilesViewModel` 单测只需注入一个假实现，无需构造真实的
 * `DownloadCoordinator`（它需要 Room DAO 与 Android Context）。
 */
fun interface DownloadLauncher {

    /** 把一个云端文件加入下载队列；实现负责映射来源与选择保存位置。 */
    suspend fun launch(file: CloudFileEntity): LaunchOutcome
    suspend fun launch(file: CloudFileEntity, tree: String): LaunchOutcome = launch(file)
}

/** `CloudFileEntity` → 取链所需的稳定来源字段（不带短期 CDN 地址）。 */
fun CloudFileEntity.toDownloadSource(): DownloadSource = DownloadSource(
    fileId = fileId,
    fileName = fileName,
    size = size,
    etag = etag,
    s3KeyFlag = s3KeyFlag,
    isFolder = isFolder,
)

/**
 * 生产实现：`CloudFileEntity` → [DownloadSource] → 默认保存位置 → [DownloadCoordinator.enqueue]。
 *
 * 保存位置策略：优先读取设置中的 SAF 目录；空字符串明确选择应用内目录。
 * 未提供设置的兼容调用者才使用第一个持久授权的 SAF 目录。
 * [DownloadDestination.Internal] 写入应用内 `filesDir/downloads`，完成后经 FileProvider
 *   暴露给系统"打开方式"（不依赖真实 `/storage/emulated/0/...` 路径）。
 *
 * 文件夹下载在 123pan 协议里是打包 zip（`batch_download_info`），因此目标名补 `.zip` 后缀，
 * 否则 SAF 目录里会出现一个没有扩展名、系统无法识别的文件。
 */
class DefaultDownloadLauncher(
    private val coordinator: DownloadCoordinator,
    private val storage: DownloadStorage,
    private val logger: AppLogger,
    private val preferredTree: () -> String? = { null },
) : DownloadLauncher {

    override suspend fun launch(file: CloudFileEntity): LaunchOutcome {
        return launch(file, preferredTree() ?: storage.saf.persistedTrees().firstOrNull().orEmpty())
    }

    override suspend fun launch(file: CloudFileEntity, tree: String): LaunchOutcome {
        return try {
            coordinator.enqueue(file.toDownloadSource(), destinationFor(file, tree))
            logger.i(LogSource.DOWNLOAD, "已加入下载队列：${file.fileName}")
            LaunchOutcome.Queued(file.fileName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IllegalStateException) {
            // enqueue 在未登录时抛 IllegalStateException(DownloadMessages.NOT_LOGGED_IN)
            logger.w(LogSource.DOWNLOAD, "加入下载队列失败：未登录")
            LaunchOutcome.Failed(error.message ?: DownloadMessages.NOT_LOGGED_IN)
        } catch (error: Exception) {
            logger.e(LogSource.DOWNLOAD, "加入下载队列失败：${error.javaClass.simpleName}")
            LaunchOutcome.Failed(DownloadMessages.ENQUEUE_FAILED)
        }
    }

    /** 目标文件名 + 默认保存位置（见类注释）。 */
    private fun destinationFor(file: CloudFileEntity, tree: String): DownloadDestination {
        val fileName = if (file.isFolder) "${file.fileName}$ZIP_SUFFIX" else file.fileName
        return if (tree.isBlank()) {
            DownloadDestination.Internal(fileName)
        } else {
            DownloadDestination.Tree(tree, fileName)
        }
    }

    /**
     * 分享文件下载入口：来源已带 shareKey，目标为 [DownloadSource]（分享文件不属于
     * 当前账户云盘，无法映射成 CloudFileEntity）。tree 为空时走默认保存位置策略。
     */
    suspend fun launch(source: DownloadSource, tree: String?): LaunchOutcome {
        val fileName = source.fileName
        val destination = if (tree.isNullOrBlank()) DownloadDestination.Internal(fileName) else DownloadDestination.Tree(tree, fileName)
        return try {
            coordinator.enqueue(source, destination)
            logger.i(LogSource.DOWNLOAD, "已加入下载队列：${source.fileName}")
            LaunchOutcome.Queued(source.fileName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IllegalStateException) {
            logger.w(LogSource.DOWNLOAD, "加入下载队列失败：未登录")
            LaunchOutcome.Failed(error.message ?: DownloadMessages.NOT_LOGGED_IN)
        } catch (error: Exception) {
            logger.e(LogSource.DOWNLOAD, "加入下载队列失败：${error.javaClass.simpleName}")
            LaunchOutcome.Failed(DownloadMessages.ENQUEUE_FAILED)
        }
    }

    private companion object {
        const val ZIP_SUFFIX = ".zip"
    }
}
