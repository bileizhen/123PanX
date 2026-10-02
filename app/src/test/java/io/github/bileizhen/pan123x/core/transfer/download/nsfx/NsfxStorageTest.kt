package io.github.bileizhen.pan123x.core.transfer.download.nsfx

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 断点存储回归测试（JVM 临时目录， 不触真实网络）。
 * 覆盖保存/加载往返、恢复身份校验、损坏日志、覆盖度校验、重置与原子写。
 */
class NsfxStorageTest {

    private val size = 1000L
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("nsfx-storage").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun info(
        url: String = "https://cdn.example.com/f",
        etag: String = "\"v1\"",
        lastModified: String = "",
        size: Long = this.size,
    ) = FileInfo(url, size, etag, lastModified, supportsRange = true)

    private fun plan(size: Long) = mutableListOf(
        Segment(0, 0, size / 2),
        Segment(1, size / 2, size),
    )

    @Test
    fun saveThenLoadRoundTripsPlanAndOffsets() {
        val storage = NsfxStorage(dir)
        val segments = plan(size)
        segments[0].downloaded = 100
        segments[1].downloaded = 50
        storage.save(info(), segments)
        storage.checkpoint(segments[0])
        storage.checkpoint(segments[1])

        val loaded = NsfxStorage(dir).load(info())

        assertNotNull(loaded)
        assertEquals(2, loaded!!.size)
        assertEquals(listOf(0, 1), loaded.map { it.index })
        assertEquals(listOf(0L, size / 2), loaded.map { it.start })
        assertEquals(listOf(size / 2, size), loaded.map { it.end })
        assertEquals(listOf(100L, 50L), loaded.map { it.downloaded })
    }

    /**
     * ：短期 CDN signed URL 不是任务身份。重新取链后 URL 必然变化，此时只要
     * size + validator（强 ETag）一致就必须仍可续传——否则暂停后续传永远从头重下。
     */
    @Test
    fun loadAcceptsChangedUrlBecauseSignedUrlIsNotIdentity() {
        NsfxStorage(dir).save(info(), plan(size))

        val loaded = NsfxStorage(dir).load(info(url = "https://cdn.example.com/other?sign=fresh"))

        assertNotNull(loaded)
        assertEquals(2, loaded!!.size)
    }

    @Test
    fun loadRejectsValidatorAndSizeMismatch() {
        NsfxStorage(dir).save(info(), plan(size))

        assertNull(NsfxStorage(dir).load(info(etag = "\"v2\"")))
        assertNull(NsfxStorage(dir).load(info(size = size + 1)))
    }

    /** 回退校验器（无强 ETag 时用 Last-Modified）同样必须匹配。 */
    @Test
    fun loadRejectsLastModifiedMismatchWhenEtagAbsent() {
        val base = FileInfo("https://cdn.example.com/f", size, "", "Wed, 21 Oct 2015 07:28:00 GMT", true)
        NsfxStorage(dir).save(base, plan(size))

        assertNull(
            NsfxStorage(dir).load(
                base.copy(lastModified = "Thu, 22 Oct 2015 07:28:00 GMT", url = "https://cdn.example.com/f?x=1"),
            ),
        )
        assertNotNull(NsfxStorage(dir).load(base.copy(url = "https://cdn.example.com/f?x=1")))
    }

    @Test
    fun loadRejectsEmptyValidatorOrNoRange() {
        NsfxStorage(dir).save(info(), plan(size))

        assertNull(NsfxStorage(dir).load(FileInfo("https://cdn.example.com/f", size, "", "", supportsRange = true)))
        assertNull(NsfxStorage(dir).load(FileInfo("https://cdn.example.com/f", size, "\"v1\"", "", supportsRange = false)))
    }

    @Test
    fun loadReturnsNullWhenJournalMissingOrCorrupt() {
        assertNull(NsfxStorage(dir).load(info()))

        NsfxStorage(dir).save(info(), plan(size))
        File(dir, "segments.json").writeText("{ not json")

        assertNull(NsfxStorage(dir).load(info()))
    }

    @Test
    fun verifyCoverageRejectsGapOverlapAndWrongTotal() {
        NsfxStorage.verifyCoverage(plan(size), size)

        assertIllegalArgument { NsfxStorage.verifyCoverage(mutableListOf(Segment(0, 0, 400), Segment(1, 500, 1000)), 1000) }
        assertIllegalArgument { NsfxStorage.verifyCoverage(mutableListOf(Segment(0, 0, 600), Segment(1, 500, 1000)), 1000) }
        assertIllegalArgument { NsfxStorage.verifyCoverage(mutableListOf(Segment(0, 0, 900)), 1000) }
        assertIllegalArgument { NsfxStorage.verifyCoverage(mutableListOf(Segment(0, 0, 500), Segment(0, 500, 1000)), 1000) }
    }

    @Test
    fun resetClearsDirectory() {
        val storage = NsfxStorage(dir)
        storage.save(info(), plan(size))
        storage.checkpoint(plan(size)[0])
        assertTrue(storage.hasState())

        storage.reset()

        assertFalse(storage.hasState())
        assertNull(storage.load(info()))
    }

    @Test
    fun atomicWriteLeavesNoTempFileBehind() {
        val target = File(dir, "journal.json")

        NsfxStorage.atomic(target, "hello")
        assertEquals("hello", target.readText())
        assertFalse(File(dir, "journal.json.tmp").exists())

        NsfxStorage.atomic(target, "world")
        assertEquals("world", target.readText())
        assertFalse(File(dir, "journal.json.tmp").exists())
        assertEquals(1, dir.listFiles()!!.size)
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
