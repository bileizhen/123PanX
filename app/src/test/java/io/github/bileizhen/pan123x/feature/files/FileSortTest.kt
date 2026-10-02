package io.github.bileizhen.pan123x.feature.files

import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件列表客户端排序 / 搜索规则（协议真源 file_service.py：服务端只保证分页稳定，
 * 展示顺序完全由此纯函数决定）。
 */
class FileSortTest {

    private fun entity(
        fileId: Long,
        name: String,
        folder: Boolean = false,
        size: Long = 0,
        createAt: Long = 0,
        updateAt: Long = 0,
    ) = CloudFileEntity(
        accountId = "acc", fileId = fileId, parentFileId = 0, fileName = name,
        isFolder = folder, size = size, etag = "", s3KeyFlag = "", createAt = createAt, updateAt = updateAt,
    )

    private val fixtures = listOf(
        entity(9, "a.txt", size = 100, createAt = 10, updateAt = 90),
        entity(2, "b.txt", size = 200, createAt = 30, updateAt = 20),
        entity(1, "zFolder", folder = true, size = 999, createAt = 50, updateAt = 40),
        entity(3, "b.txt", size = 200, createAt = 20, updateAt = 80),
    )

    @Test fun everySortKeepsFoldersFirst() {
        assertEquals(1L, FileListQuery.apply(fixtures, "", FileSortField.NAME, true).first().fileId)
        assertEquals(1L, FileListQuery.apply(fixtures, "", FileSortField.SIZE, false).first().fileId)
        assertEquals(1L, FileListQuery.apply(fixtures, "", FileSortField.DATE, true).first().fileId)
    }

    @Test fun nameSortIsCaseInsensitiveWithFileIdTieBreak() {
        val mixed = listOf(
            entity(5, "Beta.txt"), entity(4, "alpha.txt"), entity(6, "BETA.txt"),
        )
        assertEquals(listOf(4L, 5L, 6L), FileListQuery.apply(mixed, "", FileSortField.NAME, true).map { it.fileId })
        // 降序整段反转，但文件夹仍在前、并列仍按 fileId 升序断路（5 与 6 同名）。
        assertEquals(listOf(5L, 6L, 4L), FileListQuery.apply(mixed, "", FileSortField.NAME, false).map { it.fileId })
        // 大小写不敏感全名比较："a.txt" < "b.txt" < "zFolder"（文件夹置前后文件段）
        assertEquals(listOf(1L, 9L, 2L, 3L), FileListQuery.apply(fixtures, "", FileSortField.NAME, true).map { it.fileId })
    }

    @Test fun sizeSortRespectsAscendingFlag() {
        assertEquals(listOf(1L, 9L, 2L, 3L), FileListQuery.apply(fixtures, "", FileSortField.SIZE, true).map { it.fileId })
        assertEquals(listOf(1L, 2L, 3L, 9L), FileListQuery.apply(fixtures, "", FileSortField.SIZE, false).map { it.fileId })
    }

    @Test fun dateSortPrefersUpdateAtAndFallsBackToCreateAt() {
        // updateAt: 90(id9) > 80(id3) > 40(folder) > 20(id2)
        assertEquals(listOf(1L, 2L, 3L, 9L), FileListQuery.apply(fixtures, "", FileSortField.DATE, true).map { it.fileId })
        assertEquals(listOf(1L, 9L, 3L, 2L), FileListQuery.apply(fixtures, "", FileSortField.DATE, false).map { it.fileId })
        // updateAt=0 时回退 createAt：id7(无 update, create=50) 排在 id8(update=10) 之后
        val fallback = listOf(
            entity(7, "x.bin", createAt = 50, updateAt = 0),
            entity(8, "y.bin", createAt = 1, updateAt = 10),
        )
        assertEquals(listOf(8L, 7L), FileListQuery.apply(fallback, "", FileSortField.DATE, true).map { it.fileId })
    }

    @Test fun searchIsTrimmedCaseInsensitiveSubstring() {
        val files = listOf(
            entity(1, "README.TXT"), entity(2, "readme-notes.md"), entity(3, "photos.jpg"),
        )
        // 小写全名比较："readme-notes.md"（'-' 0x2D）排在 "README.TXT"（'.' 0x2E）之前
        assertEquals(listOf(2L, 1L), FileListQuery.apply(files, "  ReAdMe ", FileSortField.NAME, true).map { it.fileId })
        assertEquals(emptyList<Long>(), FileListQuery.apply(files, "missing", FileSortField.NAME, true).map { it.fileId })
        // 空白搜索等同无过滤。
        assertEquals(3, FileListQuery.apply(files, "   ", FileSortField.NAME, true).size)
    }

    @Test fun searchAppliesBeforeSort() {
        val result = FileListQuery.apply(fixtures, "b.txt", FileSortField.SIZE, false)
        assertEquals(listOf(2L, 3L), result.map { it.fileId })
    }
}
