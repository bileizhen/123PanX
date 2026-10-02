package io.github.bileizhen.pan123x.core.transfer.upload

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileUploadSource行为测试：元数据、顺序流、定位读的
 * 精确区间与文件尾短读、多 worker 并发 [RandomAccessReader.readAt] 的线程安全、close 幂等。
 */
class FileUploadSourceTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { dir -> dir.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newFile(name: String, data: ByteArray): File {
        val dir = Files.createTempDirectory("panx-upload-source").toFile()
        tempDirs += dir
        return File(dir, name).apply { writeBytes(data) }
    }

    @Test
    fun exposesMetadataAndStreamsWholeFile() {
        val data = ByteArray(1000) { index -> (index % 251).toByte() }

        val source = FileUploadSource(newFile("sample.bin", data))

        assertEquals("sample.bin", source.displayName)
        assertEquals(1000L, source.size)
        assertTrue("普通文件必须能取到 lastModified（§1.5 mtime 校验依赖）", source.lastModified > 0L)
        source.openStream().use { stream -> assertArrayEquals(data, stream.readBytes()) }
    }

    @Test
    fun readAtReturnsExactRangesAndShortReadsAtEof() {
        val data = ByteArray(1000) { index -> (index % 251).toByte() }
        val reader = FileUploadSource(newFile("range.bin", data)).openRandomAccess()!!

        assertArrayEquals(data.copyOfRange(0, 10), reader.readAt(0, 10))
        assertArrayEquals(data.copyOfRange(500, 510), reader.readAt(500, 10))
        // 文件尾：请求 100 字节只返回剩余 10 字节（契约允许少于 length）
        assertArrayEquals(data.copyOfRange(990, 1000), reader.readAt(990, 100))
        assertEquals("越过文件尾返回空数组", 0, reader.readAt(5_000, 10).size)

        reader.close()
    }

    @Test
    fun readAtIsSafeForConcurrentWorkers() {
        val data = ByteArray(64 * 1024) { index -> (index % 199).toByte() }
        val reader = FileUploadSource(newFile("concurrent.bin", data)).openRandomAccess()!!
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(4)
        try {
            // 4 个 worker 读互不重叠的区间，模拟引擎的分片并发读取（接口契约要求线程安全）
            repeat(4) { worker ->
                pool.execute {
                    try {
                        val step = 257
                        var offset = worker * step
                        while (offset + 64 <= data.size) {
                            val expected = data.copyOfRange(offset, offset + 64)
                            val actual = reader.readAt(offset.toLong(), 64)
                            if (!expected.contentEquals(actual)) {
                                errors += "worker=$worker offset=$offset 内容不符"
                            }
                            offset += 4 * step
                        }
                    } catch (error: Exception) {
                        errors += "worker=$worker 异常：${error.message}"
                    }
                }
            }
        } finally {
            pool.shutdown()
            assertTrue("并发读取必须在 10s 内完成", pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertTrue("并发 readAt 必须线程安全，问题：$errors", errors.isEmpty())
        reader.close()
    }

    @Test
    fun closeIsIdempotent() {
        val reader = FileUploadSource(newFile("close.bin", ByteArray(16))).openRandomAccess()!!

        reader.close()
        reader.close() // 第二次关闭不得抛异常（引擎 finally 路径可能重复触发）
    }
}
