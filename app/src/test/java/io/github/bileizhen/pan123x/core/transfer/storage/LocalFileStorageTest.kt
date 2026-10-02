// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.storage

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [LocalFileStorage] 的 JVM 单测：注入临时目录即可完整覆盖
 * 创建 / 续写 / 截断 / 丢弃 / 余量 / 文件名净化，不需要设备或 ContentResolver。
 */
class LocalFileStorageTest {

    private lateinit var root: File
    private lateinit var storage: LocalFileStorage

    @Before
    fun setUp() {
        // 刻意指向一个尚不存在的多级目录，顺带验证「惰性创建 root」。
        root = File(Files.createTempDirectory("local-file-storage").toFile(), "downloads")
        storage = LocalFileStorage(root)
    }

    @After
    fun tearDown() {
        root.parentFile?.deleteRecursively()
    }

    @Test
    fun sanitisesUnsafeFileNames() {
        val cases = mapOf(
            // 路径分隔符被删除，避免路径穿越
            "report/final.docx" to "reportfinal.docx",
            "report\\final.docx" to "reportfinal.docx",
            // 保留字符与控制字符
            "a:b*c?d\"e<f>g|h.bin" to "abcdefgh.bin",
            "\u0000\u0001con\u007Ftrol.bin" to "control.bin",
            // 空白折叠与首尾修剪
            "  spaced   name  .txt" to "spaced name .txt",
            // 空名 / 纯点名的兜底
            "" to LocalFileStorage.DEFAULT_FILE_NAME,
            "   " to LocalFileStorage.DEFAULT_FILE_NAME,
            "..." to LocalFileStorage.DEFAULT_FILE_NAME,
            ".." to LocalFileStorage.DEFAULT_FILE_NAME,
            "." to LocalFileStorage.DEFAULT_FILE_NAME,
            // 结尾的点会被部分 provider 静默丢弃，提前统一
            "trailing." to "trailing",
            // 扩展名必须保留
            "video.MP4" to "video.MP4",
            "archive.tar.gz" to "archive.tar.gz",
        )
        cases.forEach { (input, expected) ->
            assertEquals("输入 <$input>", expected, LocalFileStorage.safeFileName(input))
        }
    }

    @Test
    fun capsLongNamesWhileKeepingTheExtension() {
        val sanitised = LocalFileStorage.safeFileName("a".repeat(400) + ".mp4")
        assertEquals(180, sanitised.length)
        assertTrue(sanitised.endsWith(".mp4"))
        assertEquals(176, sanitised.substringBeforeLast('.').length)
    }

    @Test
    fun capsLongNamesWithoutExtension() {
        val sanitised = LocalFileStorage.safeFileName("b".repeat(400))
        assertEquals(180, sanitised.length)
        assertFalse(sanitised.contains('.'))
    }

    @Test
    fun pathForCreatesRootLazilyAndSanitisesTheName() {
        val path = storage.pathFor("sub/dir.bin")
        assertEquals("subdir.bin", path.name)
        assertTrue(root.exists())
    }

    @Test
    fun openWriteAndCompleteProduceExactlyTheRequestedSize() {
        val payload = ByteArray(32) { it.toByte() }

        val opened = storage.open("video.mp4", payload.size.toLong())
        opened.sink.writeAt(0, payload, payload.size)
        val uri = storage.complete(opened, payload.size.toLong())

        val file = storage.pathFor("video.mp4")
        assertTrue(file.exists())
        assertEquals(payload.size.toLong(), file.length())
        assertArrayEquals(payload, file.readBytes())
        assertTrue("应用内 uri 必须带 file:// 前缀，供分派器改写", uri.startsWith("file://"))
    }

    @Test
    fun completeTruncatesALongerPartial() {
        val opened = storage.open("partial.bin", 64)
        opened.sink.writeAt(0, ByteArray(64) { 0x5A }, 64)

        storage.complete(opened, 10)

        assertEquals(10L, storage.pathFor("partial.bin").length())
    }

    @Test
    fun reopeningTheSameNameResumesInsteadOfTruncating() {
        val first = storage.open("resume.bin", 8)
        first.sink.writeAt(0, "AAAA".toByteArray(), 4)
        first.close()

        // 重新打开同名文件后，先前写入的字节必须还在（断点续传的前提）。
        val resumed = storage.open("resume.bin", 8)
        val head = storage.pathFor("resume.bin").readBytes()
        assertTrue(head.size >= 4)
        assertEquals("AAAA", String(head, 0, 4))

        resumed.sink.writeAt(4, "BBBB".toByteArray(), 4)
        storage.complete(resumed, 8)
        assertEquals("AAAABBBB", String(storage.pathFor("resume.bin").readBytes(), 0, 8))
    }

    @Test
    fun discardClosesAndDeletesThePartialFile() {
        val opened = storage.open("cancel.bin", 16)
        opened.sink.writeAt(0, ByteArray(16), 16)
        val file = storage.pathFor("cancel.bin")
        assertTrue(file.exists())

        storage.discard(opened)

        assertFalse(file.exists())
    }

    @Test
    fun discardIsSilentWhenTheFileIsAlreadyGone() {
        val opened = storage.open("gone.bin", 4)
        // Windows 不允许删除仍被句柄占用的文件，因此先关闭再删除，模拟「外部已清理」的场景。
        opened.close()
        assertTrue(storage.pathFor("gone.bin").delete())

        // 取消下载不应该因为清理失败而报错。
        storage.discard(opened)
        assertFalse(storage.pathFor("gone.bin").exists())
    }

    @Test
    fun freeSpaceReportsAPositiveValueForAUsableRoot() {
        assertTrue(storage.freeSpace() > 0L)
    }
}
