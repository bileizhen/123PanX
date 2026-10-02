// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.data.diagnostics

import android.content.Context
import android.os.Build
import io.github.bileizhen.pan123x.core.database.AppDatabase
import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogEntry
import io.github.bileizhen.pan123x.core.logging.LogSource
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断页快照（签名冻结）。
 *
 * 一次性采集的全量诊断数据：环境三项 + 缓存两项 + 传输任务计数 + 内存日志末尾。
 * [logTail] 已经过 [io.github.bileizhen.pan123x.core.logging.LogRedactor] 脱敏
 * （AppLogger.append 入口统一处理），可安全复制到剪贴板。
 */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val sdkInt: Int,
    val deviceModel: String,
    val cacheBytes: Long,
    val previewCacheBytes: Long,
    /** [io.github.bileizhen.pan123x.core.database.TransferState].name → 行数（口径见 [RoomDiagnosticsSource]）。 */
    val taskCounts: Map<String, Int>,
    /** 内存日志末尾 ~100 条，格式 "HH:mm:ss LEVEL [SOURCE] message"。 */
    val logTail: List<String>,
)

/**
 * 诊断页的任务计数数据接缝（仓库不直接依赖 DAO 细节，测试注入假实现）。
 *
 * 为什么不直接注入 AppDatabase：AppDatabase 是 Room 抽象类，纯 JVM 单测无法构造；
 * 收窄成"取一张状态计数表"后，测试用一个 Map 即可替身。接口定义在消费者侧（TransferTasksSource 先例）。
 */
interface DiagnosticsSource {
    /** 传输任务计数：TransferState.name → 行数。拿不到的状态不出现（UI 按 0 展示）。 */
    suspend fun taskCounts(): Map<String, Int>
}

/**
 * 生产数据源：Room transfer_tasks 表的活跃任务按状态分组计数。
 *
 * **统计口径（重要）**：[io.github.bileizhen.pan123x.core.database.TransferTaskDao.activeTasks]
 * 是唯一的跨账户快照查询，只覆盖四个活跃态（QUEUED / RESOLVING / RUNNING / COMPLETING）；
 * 停止态（PAUSED / WAITING_NETWORK / WAITING_USER / COMPLETED / FAILED / CANCELED）不进结果 map。
 * 这是在"禁止新增 DAO 抽象方法"（M7 冻结约束）下能拿到的最大口径——观察类查询
 * observeTasks(accountId) 需要按账户过滤且返回 Flow，不适合做全量诊断快照。
 */
class RoomDiagnosticsSource(private val database: AppDatabase) : DiagnosticsSource {
    override suspend fun taskCounts(): Map<String, Int> =
        database.transferTaskDao().activeTasks()
            .groupBy { it.state.name }
            .mapValues { (_, rows) -> rows.size }
}

/**
 * 诊断数据仓库：环境信息 + 缓存统计 + 任务计数 + 日志尾。
 *
 * 双构造的设计动机：主构造收 [File] + 已解析版本号，与 [io.github.bileizhen.pan123x.core.transfer.preview.PdfPreviewCache]
 * 的"直接收 cacheDir"先例一致，纯 JVM 单测可用临时目录；Context 构造是
 * 生产入口（AppContainer 接线用），只做两个解析动作后委托——版本号解析失败按 "unknown"
 * 处理，绝不因 PackageManager 异常拖垮诊断页（可靠性优先）。
 *
 * @param cacheDir 应用缓存根目录（生产接线传 `context.cacheDir`）；预览缓存在其 `previews/` 子目录。
 * @param appVersion 已解析的版本名（Context 构造内含 try-catch 回退）。
 * @param source 任务计数数据接缝（生产传 [RoomDiagnosticsSource]，测试传假实现）。
 * @param logger 内存日志；logTail 直接消费其 [AppLogger.entries] 当前值。
 */
class DiagnosticsRepository(
    private val cacheDir: File,
    private val appVersion: String,
    private val source: DiagnosticsSource,
    private val logger: AppLogger,
) {

    /** 生产接线入口。 */
    constructor(context: Context, source: DiagnosticsSource, logger: AppLogger) : this(
        cacheDir = context.cacheDir,
        appVersion = resolveAppVersion(context),
        source = source,
        logger = logger,
    )

    /**
     * 采集一次完整快照。IO 密集部分（缓存递归求和）在 IO dispatcher 上执行；
     * 任一子项失败只降级该子项（记警告、回退空值），不让整张快照失败。
     */
    suspend fun snapshot(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        DiagnosticsSnapshot(
            appVersion = appVersion,
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            cacheBytes = runCatching { deepSize(cacheDir) }.getOrElse {
                logger.w(LogSource.APP, "统计缓存大小失败：${it.javaClass.simpleName}")
                0L
            },
            previewCacheBytes = runCatching { deepSize(File(cacheDir, PREVIEWS_DIR)) }.getOrElse {
                logger.w(LogSource.APP, "统计预览缓存大小失败：${it.javaClass.simpleName}")
                0L
            },
            taskCounts = runCatching { source.taskCounts() }.getOrElse {
                logger.w(LogSource.DATABASE, "统计传输任务失败：${it.javaClass.simpleName}")
                emptyMap()
            },
            logTail = logger.entries.value.takeLast(LOG_TAIL_LIMIT).map { formatLogLine(it) },
        )
    }

    /**
     * 清空预览缓存（`cacheDir/previews` 的全部内容），返回实际释放的字节数。
     *
     * 语义边界：
     * - 目录不存在（从未生成过预览）直接返回 0；
     * - 只删 previews 子目录内容，缓存根下其他文件（OkHttp/Coil 磁盘缓存等）分毫不动；
     * - "释放字节数"按**成功删除的普通文件**累加，删除失败的文件不计入；
     * - previews 目录本身保留（清空内容），下次预览复用既有布局，无需重建。
     */
    suspend fun clearPreviewCache(): Long = withContext(Dispatchers.IO) {
        val previews = File(cacheDir, PREVIEWS_DIR)
        // 符号链接根视同不存在：不求和、不删除，杜绝经入口逃逸出 cacheDir。
        if (!previews.isDirectory || Files.isSymbolicLink(previews.toPath())) return@withContext 0L
        runCatching { deleteContents(previews) }.getOrElse {
            logger.w(LogSource.APP, "清除预览缓存失败：${it.javaClass.simpleName}")
            0L
        }
    }

    /**
     * 递归求和目录内普通文件字节数；根不是真实目录（缺失或符号链接）返回 0。
     *
     * 符号链接防护的理由：cacheDir 是应用私有目录（data/user/0/&lt;pkg&gt;/cache），第三方应用
     * 无法植入链接，正常情况下不存在逃逸面；但备份恢复、异常挂载等极端场景仍可能引入符号
     * 链接，一旦求和/删除跟随链接就会越出 cacheDir（数据安全优先）。因此入口与
     * 遍历都只认 `isDirectory` / `isFile` 判定的真实目录与普通文件，符号链接显式跳过
     * （java.nio 可用，minSdk 26）。
     */
    private fun deepSize(root: File): Long {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return 0L
        val queue = ArrayDeque<File>().apply { add(root) }
        var total = 0L
        while (queue.isNotEmpty()) {
            val children = queue.removeFirst().listFiles() ?: continue
            for (child in children) when {
                Files.isSymbolicLink(child.toPath()) -> Unit
                child.isDirectory -> queue.add(child)
                child.isFile -> total += child.length()
            }
        }
        return total
    }

    /** 删除目录全部内容（子目录连带删除，根目录保留），返回成功删除的普通文件总字节。 */
    private fun deleteContents(root: File): Long {
        var freed = 0L
        val children = root.listFiles() ?: return 0L
        for (child in children) when {
            // 符号链接只删链接本身，绝不深入目标（同 [deepSize] 的逃逸防护）。
            Files.isSymbolicLink(child.toPath()) -> child.delete()
            child.isDirectory -> {
                freed += deleteContents(child)
                child.delete()
            }

            // 长度必须在删除前读取：Windows 上已删除文件的 length 返回 0（POSIX 靠 inode 仍可读）。
            child.isFile -> {
                val size = child.length()
                if (child.delete()) freed += size
            }
            else -> child.delete()
        }
        return freed
    }

    private companion object {
        const val PREVIEWS_DIR = "previews"
        const val LOG_TAIL_LIMIT = 100

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

        /** 版本号解析失败（packageManager 不可用 / versionName 缺失）回退 "unknown"。 */
        fun resolveAppVersion(context: Context): String = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (t: Throwable) {
            "unknown"
        }

        /** "HH:mm:ss LEVEL [SOURCE] message"；级别/来源用枚举名，与日志页的来源列口径一致。 */
        fun formatLogLine(entry: LogEntry): String =
            "${TIME_FORMAT.format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()))} " +
                "${entry.level.name} [${entry.source.name}] ${entry.message}"
    }
}
