package io.github.bileizhen.pan123x.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 后缀表逐项对照协议真源 preview_manager.py；未收录后缀一律 OTHER。 */
class FileKindTest {

    @Test fun foldersAlwaysClassifyAsFolder() {
        assertEquals(FileKind.FOLDER, fileKindOf(isFolder = true, fileName = "anything.jpg"))
        assertEquals(FileKind.FOLDER, fileKindOf(isFolder = true, fileName = ""))
    }

    @Test fun extensionMatchingIsCaseInsensitiveAndTakesLastSegment() {
        assertEquals(FileKind.IMAGE, fileKindOf(false, "photo.JPG"))
        assertEquals(FileKind.IMAGE, fileKindOf(false, "archive.tar.png"))
        assertEquals(FileKind.VIDEO, fileKindOf(false, "movie.Mp4"))
        assertEquals(FileKind.AUDIO, fileKindOf(false, "song.FLAC"))
        assertEquals(FileKind.PDF, fileKindOf(false, "doc.Pdf"))
        assertEquals(FileKind.TEXT, fileKindOf(false, "code.Kt"))
    }

    @Test fun unknownOrMissingExtensionsFallBackToOther() {
        assertEquals(FileKind.OTHER, fileKindOf(false, "archive.zip"))
        assertEquals(FileKind.OTHER, fileKindOf(false, "backup.7z"))
        assertEquals(FileKind.OTHER, fileKindOf(false, "noextension"))
        assertEquals(FileKind.OTHER, fileKindOf(false, "trailingdot."))
        assertEquals(FileKind.OTHER, fileKindOf(false, ""))
    }

    @Test fun textTableCoversSourceAndConfigSuffixes() {
        listOf("txt", "md", "json", "xml", "yaml", "yml", "ini", "log", "py", "js", "ts", "java", "kt", "sql", "html", "css", "sh", "env")
            .forEach { suffix -> assertEquals(suffix, FileKind.TEXT, fileKindOf(false, "file.$suffix")) }
    }
}
