// SPDX-License-Identifier: GPL-3.0-only
package io.github.bileizhen.pan123x.core.transfer.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 存储层的纯逻辑单测。
 *
 * 只覆盖不依赖 ContentResolver 的部分：扩展名 → MIME 映射，以及目录 uri 的静态校验文案。
 * 真实的 SAF 读写需要设备上的 DocumentsProvider，按  留到 androidTest（后置）。
 *
 * 被测对象是 [SafStorageRules] 而不是 [SafStorage]：后者需要 Context 与 DocumentFile，
 * 在 JVM 单测里无法构造，纯逻辑因此被显式隔离出来。
 */
class SafStorageLogicTest {

    @Test
    fun mapsKnownExtensionsToTheirMimeTypes() {
        val expected = mapOf(
            "movie.mp4" to "video/mp4",
            "movie.mkv" to "video/x-matroska",
            "movie.avi" to "video/x-msvideo",
            "movie.mov" to "video/quicktime",
            "song.mp3" to "audio/mpeg",
            "song.flac" to "audio/flac",
            "song.wav" to "audio/x-wav",
            "song.m4a" to "audio/mp4",
            "photo.jpg" to "image/jpeg",
            "photo.jpeg" to "image/jpeg",
            "photo.png" to "image/png",
            "photo.gif" to "image/gif",
            "photo.webp" to "image/webp",
            "photo.heic" to "image/heic",
            "doc.pdf" to "application/pdf",
            "notes.txt" to "text/plain",
            "notes.md" to "text/markdown",
            "data.json" to "application/json",
            "data.xml" to "text/xml",
            "run.log" to "text/plain",
            "pack.zip" to "application/zip",
            "pack.7z" to "application/x-7z-compressed",
            "pack.rar" to "application/vnd.rar",
            "app.apk" to SafStorageRules.MIME_APK,
        )
        expected.forEach { (fileName, mime) ->
            assertEquals("文件名 <$fileName>", mime, SafStorageRules.mimeTypeFor(fileName))
        }
    }

    @Test
    fun mimeLookupIgnoresCaseAndFallsBackToOctetStream() {
        assertEquals("video/mp4", SafStorageRules.mimeTypeFor("MOVIE.MP4"))
        assertEquals("image/jpeg", SafStorageRules.mimeTypeFor("Photo.JPG"))
        // 未知后缀 / 无后缀 / 空名一律按二进制处理，不猜类型。
        listOf("file.unknownext", "README", "noextension.", "", "archive.tar.zzz").forEach {
            assertEquals("文件名 <$it>", SafStorageRules.MIME_DEFAULT, SafStorageRules.mimeTypeFor(it))
        }
        assertEquals("application/gzip", SafStorageRules.mimeTypeFor("archive.tar.gz"))
    }

    @Test
    fun blankOrMalformedTreeUrisAreUnavailableWithUserReadableMessages() {
        val malformed = listOf(
            "",
            "   ",
            "not a uri",
            // 非 content scheme：真实路径不属于 SAF
            "file:///storage/emulated/0/Download",
            // 只有 scheme，没有 authority
            "content://",
            // 有 authority 但缺 /tree/ 与 /document/ 段，DocumentFile.fromTreeUri 无法解析
            "content://com.example.provider/root/x",
            "content://com.example.provider/tree/primary%3ADownload",
        )
        malformed.forEach { input ->
            val result = SafStorageRules.staticCheck(input)
            assertTrue("<$input> 应判定为不可用，实际 $result", result is StorageCheck.Unavailable)
            val message = (result as StorageCheck.Unavailable).userMessage
            assertTrue("文案不能为空", message.isNotBlank())
            assertTrue("文案必须是中文提示：<$message>", message.contains("目录"))
        }
    }

    @Test
    fun wellFormedTreeUriPassesStaticValidation() {
        val treeUri = "content://com.android.externalstorage.documents" +
            "/tree/primary%3ADownload/document/primary%3ADownload"
        assertEquals(StorageCheck.Ok, SafStorageRules.staticCheck(treeUri))
        assertEquals(StorageCheck.Ok, SafStorageRules.staticCheck("  $treeUri  "))
    }
}
