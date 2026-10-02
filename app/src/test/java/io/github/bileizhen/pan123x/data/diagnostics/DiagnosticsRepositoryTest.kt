// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.data.diagnostics

import io.github.bileizhen.pan123x.core.logging.AppLogger
import io.github.bileizhen.pan123x.core.logging.LogSource
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * DiagnosticsRepository 行为测试（tmp 目录 + 真 AppLogger + 假 source，
 *  纯 JVM、不触网、不构造 Room）。
 *
 * 走 File 主构造注入临时 cacheDir 与固定版本号；Context 构造仅在设备上由 AppContainer 走通，
 * 单测环境 android.jar 为 stub（isReturnDefaultValues=true），不参与被测路径。
 */
class DiagnosticsRepositoryTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    /** 计数假源：直接回填一张表，替代 Room（DiagnosticsSource 接缝，见接口 KDoc）。 */
    private class FakeSource(var counts: Map<String, Int> = emptyMap()) : DiagnosticsSource {
        override suspend fun taskCounts(): Map<String, Int> = counts
    }

    // 固定时钟：日志行时间戳可预测（HH:mm:ss 段由时区决定，断言只看正则 + 尾部内容）。
    private val logger = AppLogger(clock = { 1_700_000_000_000L })

    private fun repository(
        cacheDir: File = tmp.root,
        source: DiagnosticsSource = FakeSource(),
    ): DiagnosticsRepository = DiagnosticsRepository(
        cacheDir = cacheDir,
        appVersion = "test-1.2.3",
        source = source,
        logger = logger,
    )

    private fun write(file: File, bytes: Int) {
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    @Test
    fun snapshotCollectsEnvironmentCacheTasksAndLogTail() = runTest {
        // previews 两层 + 缓存根散文件：cacheBytes 是全量、previewCacheBytes 只算 previews 子树。
        write(File(tmp.root, "previews/a.pdf"), 100)
        write(File(tmp.root, "previews/sub/b.pdf"), 27)
        write(File(tmp.root, "other.bin"), 50)
        val source = FakeSource(mapOf("RUNNING" to 2, "QUEUED" to 1))
        logger.i(LogSource.APP, "hello")
        logger.e(LogSource.API, "boom")

        val snapshot = repository(source = source).snapshot()

        assertEquals("test-1.2.3", snapshot.appVersion)
        // 单测环境 stub 值为 0；真机上是真实 API 级别——只验证取到非负值与设备串非空。
        assertTrue(snapshot.sdkInt >= 0)
        assertTrue(snapshot.deviceModel.isNotEmpty())
        assertEquals(177L, snapshot.cacheBytes)
        assertEquals(127L, snapshot.previewCacheBytes)
        assertEquals(mapOf("RUNNING" to 2, "QUEUED" to 1), snapshot.taskCounts)

        assertEquals(2, snapshot.logTail.size)
        // 格式 "HH:mm:ss LEVEL [SOURCE] message"；时间与顺序（旧→新）一并校验。
        assertTrue(snapshot.logTail[0].matches(Regex("\\d{2}:\\d{2}:\\d{2} INFO \\[APP\\] hello")))
        assertTrue(snapshot.logTail[1].matches(Regex("\\d{2}:\\d{2}:\\d{2} ERROR \\[API\\] boom")))
    }

    @Test
    fun logTailIsCappedAt100Entries() = runTest {
        repeat(150) { index -> logger.i(LogSource.APP, "msg-$index") }

        val snapshot = repository().snapshot()

        assertEquals(100, snapshot.logTail.size)
        // takeLast 保序：末尾必须是最新一条，且更早的 msg-49 被截掉。
        assertTrue(snapshot.logTail.last().endsWith("msg-149"))
        assertFalse(snapshot.logTail.any { it.contains("msg-49 ") || it.endsWith("msg-49") })
    }

    @Test
    fun missingPreviewsReportZeroAndClearReturnsZero() = runTest {
        write(File(tmp.root, "other.bin"), 50)

        val repository = repository()
        val snapshot = repository.snapshot()

        assertEquals(0L, snapshot.previewCacheBytes)
        assertEquals(0L, repository.clearPreviewCache())
        // 清除对无关文件零影响。
        assertTrue(File(tmp.root, "other.bin").isFile)
    }

    @Test
    fun clearRemovesOnlyPreviewsContentAndReturnsFreedBytes() = runTest {
        write(File(tmp.root, "previews/a.pdf"), 100)
        write(File(tmp.root, "previews/nested/deep/b.pdf"), 27)
        write(File(tmp.root, "previews/stale.pdf.part"), 13)
        write(File(tmp.root, "keep.bin"), 50)

        val repository = repository()
        val freed = repository.clearPreviewCache()

        // 三个普通文件全部计入；previews 目录本身保留（清空内容），子目录连带删除。
        assertEquals(140L, freed)
        val previews = File(tmp.root, "previews")
        val remaining = previews.listFiles()
        assertNotNull(remaining)
        assertTrue(remaining!!.isEmpty())
        assertFalse(File(tmp.root, "previews/nested").exists())
        // 缓存根下其他文件不动（数据安全优先）。
        assertTrue(File(tmp.root, "keep.bin").isFile)
        assertEquals(50L, File(tmp.root, "keep.bin").length())

        // 清除后再取快照：预览缓存归零、总缓存只剩根散文件。
        val snapshot = repository.snapshot()
        assertEquals(0L, snapshot.previewCacheBytes)
        assertEquals(50L, snapshot.cacheBytes)
    }

    @Test
    fun deepSizeSkipsSymbolicLinks() = runTest {
        if (!supportsSymlinks()) return@runTest
        write(File(tmp.root, "outside.bin"), 64)
        write(File(tmp.root, "real.txt"), 10)
        Files.createSymbolicLink(
            File(tmp.root, "previews").toPath(),
            tmp.root.toPath(),
        )

        val repository = repository()
        val snapshot = repository.snapshot()

        // previews 是指向缓存根的符号链接：视同不存在——不求和其目标（防重复/逃逸），清除也不深入。
        assertEquals(0L, snapshot.previewCacheBytes)
        assertEquals(74L, snapshot.cacheBytes) // 只算根下两个真实文件
        assertEquals(0L, repository.clearPreviewCache())
        assertTrue(File(tmp.root, "outside.bin").isFile)
        assertTrue(File(tmp.root, "real.txt").isFile)
    }

    private fun supportsSymlinks(): Boolean = try {
        val probe = File(tmp.root, "symlink-probe")
        Files.createSymbolicLink(probe.toPath(), tmp.root.toPath())
        probe.delete()
        true
    } catch (t: Throwable) {
        false // Windows 无开发者模式的测试机不允许建链，跳过该防护用例。
    }
}
