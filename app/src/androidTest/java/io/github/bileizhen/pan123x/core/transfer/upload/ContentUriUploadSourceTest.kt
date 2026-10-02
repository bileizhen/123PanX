package io.github.bileizhen.pan123x.core.transfer.upload

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ContentUriUploadSource] instrumentation 测试（androidTest 行）：
 * 随机读（`Os.pread` 定位读）与顺序读（`openInputStream`）结果一致性，含跨线程并发读；
 * 另覆盖 create 的 statSize 兜底与"不可读返回 null"。
 *
 * 用 [androidx.test.platform.app.InstrumentationRegistry] 的 targetContext 在 cacheDir 写临时
 * 文件后经 `Uri.fromFile` 读取——ContentResolver 对 file scheme 原生支持 openInputStream /
 * openFileDescriptor，无需注册 provider 即可驱动真实读取路径。
 */
@RunWith(AndroidJUnit4::class)
class ContentUriUploadSourceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun randomReadMatchesSequentialRead() = runBlocking {
        // 3 MB + 尾数：覆盖跨块偏移与"末段不足整读"的短读路径
        val payload = ByteArray(3 * 1024 * 1024 + 777) { (it % 251).toByte() }
        val file = File(context.cacheDir, "upload-source-test-${System.nanoTime()}.bin")
        file.writeBytes(payload)
        try {
            val uri = Uri.fromFile(file)
            val source = ContentUriUploadSource.create(context, uri)
            assertNotNull("file:// uri 应可解析（query 失败时走 statSize 兜底）", source)
            source!!

            assertEquals(payload.size.toLong(), source.size)
            assertEquals(file.name, source.displayName)
            assertEquals(uri.toString(), source.sourceUri)

            // 顺序读（MD5 路径）与原始字节一致
            val sequential = source.openStream().use { it.readBytes() }
            assertArrayEquals(payload, sequential)

            // 随机读（分片并行路径）：块首/块尾/跨块/接近 EOF 逐段对照顺序读
            val reader = source.openRandomAccess()
            assertNotNull("file uri 必须支持随机读", reader)
            val random = reader!!
            random.use {
                for (offset in longArrayOf(0L, 1L, 1_048_576L, payload.size - 10L)) {
                    assertArrayEquals(
                        "offset=$offset 处的随机读必须与顺序读一致",
                        payload.sliceArray(offset.toInt() until payload.size),
                        random.readAt(offset, payload.size - offset.toInt()),
                    )
                }
                // 固定长度读：文件尾允许短读
                assertArrayEquals(
                    payload.sliceArray((payload.size - 10) until payload.size),
                    random.readAt(payload.size - 10L, 100),
                )
                assertEquals("越过 EOF 应得到空数组", 0, random.readAt(payload.size.toLong(), 8).size)

                // 多分片 worker 并发 readAt 不同区间：无状态偏移读必须互不干扰
                val failures = java.util.concurrent.atomic.AtomicInteger(0)
                val threads = (0 until 4).map { worker ->
                    Thread {
                        val base = worker * 100_000L
                        val expected = payload.sliceArray(base.toInt() until base.toInt() + 50_000)
                        val actual = random.readAt(base, 50_000)
                        if (!expected.contentEquals(actual)) failures.incrementAndGet()
                    }.apply { start() }
                }
                threads.forEach { it.join() }
                assertEquals("并发 readAt 不得相互干扰", 0, failures.get())
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun missingFileYieldsNullSource() = runBlocking {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.bin")
        assertNull("不可读的来源必须返回 null（statSize 兜底也拿不到大小）", ContentUriUploadSource.create(context, Uri.fromFile(missing)))
        // 按契约，缺文件不应被创建出来
        assertTrue(!missing.exists())
    }
}
