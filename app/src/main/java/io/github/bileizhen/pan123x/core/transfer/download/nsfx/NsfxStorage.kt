// Kotlin adaptation of Hanabi-Download-Manager-X NSFX via bileizhen/LeiFetch. SPDX-License-Identifier: GPL-3.0-only
//
// 有意偏离 LeiFetch：
//   偏离 1：`org.json` → `kotlinx.serialization`。org.json 在 JVM 单测下只是桩实现，
//           无法真正解析，改用项目既有依赖后才能让断点日志逻辑被单元测试覆盖。
//   偏离 2：`android.util.AtomicFile` → 纯 JVM 的「写 `<name>.tmp` + rename」原子写
//           （见 [NsfxStorage.atomic]），使断点读写可在 JVM 单测中验证。
//   偏离 4（行为性修正，真机验证后做出）：LeiFetch 的断点身份含 URL，本项目按
//           「不要把短期 CDN signed URL 当任务身份」改为只取 size + validator（见类注释）。
package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一个下载分段。`start` 与 `index` 在生命周期内不变；`end` 可因动态尾段拆分而收缩；
 * `downloaded` 表示从 `start` 起已连续写入的字节数（因此 [remaining] 总是可续传的尾部区间）。
 */
class Segment(val index: Int, val start: Long, @Volatile var end: Long, @Volatile var downloaded: Long = 0) {
    @Volatile var speed = 0.0
    @Volatile var lastProgressNs = System.nanoTime()
    var lastSplitNs = 0L
    val size get() = end - start
    val remaining get() = (size - downloaded).coerceAtLeast(0)
}

/**
 * 下载资源身份信息。[validator] 为断点续传的身份要素之一。
 *
 * @param url 本次解析出的 CDN 直链（短时签名 URL，恢复时需重新取链后比对）。
 * @param size 文件总大小。
 * @param etag 强 ETag（形如 `"abc"`，弱标签或缺失时为空）。
 * @param lastModified 合法的 RFC 1123 Last-Modified，缺失时为空。
 * @param supportsRange 服务器是否支持 Range。
 */
data class FileInfo(
    val url: String, val size: Long, val etag: String, val lastModified: String,
    val supportsRange: Boolean,
) {
    /** RFC 9110: 优先使用强 ETag，缺失时才用 HTTP 日期作 If-Range 校验器。 */
    val validator: String get() = etag.ifEmpty { lastModified }
}

/**
 * NSFX 断点存储：分段日志 `segments.json` + 每段偏移标记 `<index>.offset`。
 *
 * **恢复身份 = size + validator（强 ETag 优先，回退 Last-Modified）**，刻意**不包含 URL**：
 * 123pan 的 CDN 直链是短时签名地址，每次重新取链都会变（「不要把短期 CDN signed URL
 * 当任务身份；恢复时重新向 123pan 获取 URL，并以 FileId / size / etag 校验」）。若把 URL 计入身份，
 * 暂停后续传必然被判失效并从头重下，断点续传形同虚设。
 *
 * URL 仍会记入日志（`journal.url`），但只用于诊断，不参与相等性判断。内容变更由两道防线兜住：
 * ① 本层的 size + validator 比对；② 分段请求携带 `If-Range: <validator>`，服务端会在内容已变时
 * 回退成整文件 200 而非 206，由 `NsfxHttpClient` 的 Range 校验拦下。
 *
 * @param dir 该任务的断点目录（由调用方创建）。
 */
class NsfxStorage(private val dir: File) {

    private val journalFile = File(dir, "segments.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 目录中是否存在任何断点文件。 */
    fun hasState(): Boolean = dir.listFiles()?.isNotEmpty() == true

    /** 清空断点目录（删除全部子文件）。 */
    fun reset() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * 加载断点分段。
     *
     * 返回 null 的情形（任一不满足即视为不可续传）：
     * - 日志缺失或损坏；
     * - [FileInfo.validator] 为空，或 [FileInfo.supportsRange] 为 false；
     * - 记录的 size / validator 与 [info] 不符（内容已变，旧分片不能拼接）。
     *
     * URL 变化**不**影响可续传性（见类注释）。
     */
    fun load(info: FileInfo): MutableList<Segment>? = runCatching {
        require(info.validator.isNotEmpty() && info.supportsRange)
        require(journalFile.isFile)
        val journal = json.decodeFromString(Journal.serializer(), journalFile.readText())
        // 兼容旧版只保存 ETag 的断点日志。
        val savedValidator = journal.validator.ifEmpty { journal.etag }
        require(savedValidator == info.validator && journal.size == info.size)
        require(journal.segments.size in 1..256)
        val list = journal.segments.map { dto ->
            Segment(dto.id, dto.start, dto.end).also { segment ->
                val offset = markerFile(segment.index).takeIf { it.isFile }
                    ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
                segment.downloaded = offset?.takeIf { it in 0..segment.size } ?: 0
            }
        }.toMutableList()
        verifyCoverage(list, info.size)
        list
    }.getOrNull()

    /** 保存分段计划（覆盖写入日志），并校验覆盖度。 */
    fun save(info: FileInfo, segments: List<Segment>) {
        verifyCoverage(segments, info.size)
        val journal = Journal(
            url = info.url,
            etag = info.etag,
            lastModified = info.lastModified,
            validator = info.validator,
            size = info.size,
            segments = segments.map { JournalSegment(it.index, it.start, it.end) },
        )
        atomic(journalFile, json.encodeToString(Journal.serializer(), journal))
    }

    /** 记录某分段的已下载偏移（原子写）。 */
    fun checkpoint(segment: Segment) = atomic(markerFile(segment.index), segment.downloaded.toString())

    private fun markerFile(id: Int) = File(dir, "$id.offset")

    companion object {

        /**
         * 校验分段集合恰好覆盖 `[0, size)`：索引唯一、无重叠、无缺口、`end > start`。
         * @throws IllegalArgumentException 覆盖不合法时抛出。
         */
        fun verifyCoverage(segments: List<Segment>, size: Long) {
            require(segments.map { it.index }.distinct().size == segments.size) { "分段索引重复" }
            var position = 0L
            segments.sortedBy { it.start }.forEach {
                require(it.index in 0..255 && it.start == position && it.end > it.start && it.end <= size) { "分段存在重叠或缺口" }
                position = it.end
            }
            require(position == size) { "分段未覆盖整个文件" }
        }

        /**
         * 纯 JVM 原子写：先写 `<name>.tmp` 再 `renameTo`，成功即无 `.tmp` 残留。
         * Android/Linux 上 rename 覆盖是原子的；Windows 上目标存在时 rename 会失败，
         * 退化为「删除目标后重命名」（仅测试环境会走到）。
         */
        fun atomic(file: File, value: String) {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(value)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw IOException("原子写入失败：${file.name}")
                }
            }
        }
    }
}

/** 日志序列化模型（偏离 1：替代 org.json）；internal 以便 kotlinx.serialization 生成序列化器。 */
@Serializable
internal data class JournalSegment(val id: Int, val start: Long, val end: Long)

@Serializable
internal data class Journal(
    val url: String,
    val etag: String = "",
    val lastModified: String = "",
    val validator: String = "",
    val size: Long,
    val segments: List<JournalSegment>,
)
