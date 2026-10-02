package io.github.bileizhen.pan123x.data.settings

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewCacheCleanerTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun cacheCleaningKeepsActivePartsDownloadsCheckpointsAndOtherDirectories() = runTest {
        val previews = folder.newFolder("previews")
        val pdf = File(previews, "1.pdf").apply { writeBytes(ByteArray(1024)) }
        val active = File(previews, "2.pdf.part").apply { writeText("active") }
        val checkpoint = folder.newFile("segment.checkpoint").apply { writeText("resume") }
        val download = folder.newFolder("downloads").let { File(it, "keep.bin").apply { writeText("complete") } }
        assertEquals(1024L, PreviewCacheCleaner(folder.root).clear())
        assertFalse(pdf.exists()); assertTrue(active.exists()); assertTrue(download.exists()); assertTrue(checkpoint.exists())
    }
    @Test fun absentCacheIsHarmless() = runTest { assertEquals(0L, PreviewCacheCleaner(folder.root).clear()) }
}
