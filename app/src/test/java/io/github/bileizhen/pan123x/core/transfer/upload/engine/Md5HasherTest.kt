package io.github.bileizhen.pan123x.core.transfer.upload.engine

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Md5Hasher 对照测试：与 [MessageDigest] 逐字节对照，
 * 覆盖空文件、跨多个 1 MiB 读取块的大流、百分比单调性（0..99 变化时回调 + 结束补 100）、
 * 以及分块到达的字节流（`InputStream.read` 每次返回不足一块）不影响摘要。
 */
class Md5HasherTest {

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

    @Test
    fun emptyStreamMatchesMessageDigestAndReportsOnlyFinal100() = runTest {
        val percents = mutableListOf<Int>()

        val hash = Md5Hasher.hash(ByteArrayInputStream(ByteArray(0)), size = 0L) { percents += it }

        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hash)
        // 空文件不产生中间百分比（参考源 :111 仅在 fsize>0 时上报），只补一次 100
        assertEquals(listOf(100), percents)
    }

    @Test
    fun multiBlockStreamMatchesMessageDigest() = runTest {
        // > 2 MiB：至少跨 3 个 1 MiB 读取块，覆盖 while 循环与 readTotal 累计
        val data = ByteArray(2 * 1024 * 1024 + 12345) { index -> (index * 31 + 7).toByte() }
        val percents = mutableListOf<Int>()

        val hash = Md5Hasher.hash(ByteArrayInputStream(data), size = data.size.toLong()) { percents += it }

        assertEquals(md5Hex(data), hash)
        val intermediate = percents.dropLast(1)
        assertEquals("中间百分比必须严格递增（首块 1MiB 约占 49%）", intermediate.sorted(), intermediate)
        assertTrue("中间百分比必须在 0..99", intermediate.all { it in 0..99 })
        assertEquals("最后必须是收尾 100", 100, percents.last())
    }

    @Test
    fun percentIsMonotonicFromZeroToNinetyNineThenFinal100() = runTest {
        // 5 MiB：每读完 1 MiB 百分比恰好 +20，最后一块落到 99（min(99, 100)），再补 100
        val data = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
        val percents = mutableListOf<Int>()

        Md5Hasher.hash(ByteArrayInputStream(data), size = data.size.toLong()) { percents += it }

        assertEquals(listOf(20, 40, 60, 80, 99, 100), percents)
        val intermediate = percents.dropLast(1)
        assertEquals("中间百分比必须严格递增", intermediate.sorted(), intermediate)
        assertTrue("中间百分比必须在 0..99", intermediate.all { it in 0..99 })
    }

    @Test
    fun chunkedReadsDoNotAffectDigest() = runTest {
        val data = ByteArray(3 * 1024 * 1024 + 777) { index -> (index * 7).toByte() }
        // 每次最多返回 100 KiB 的窄流：摘要必须与整块读取一致（digest.update 部分块）
        val chunked = object : FilterInputStream(ByteArrayInputStream(data)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 100 * 1024))
        }

        assertEquals(md5Hex(data), Md5Hasher.hash(chunked, size = data.size.toLong()))
    }

    @Test
    fun digestIsLowercaseHex() = runTest {
        val data = "123PanX".toByteArray(Charsets.UTF_8)

        val hash = Md5Hasher.hash(ByteArrayInputStream(data), size = data.size.toLong())

        assertEquals(md5Hex(data), hash)
        assertTrue("必须是 32 位小写十六进制（参考源 :118 hexdigest）", hash.length == 32 && hash == hash.lowercase())
    }
}
