package io.github.bileizhen.pan123x.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CloudFileDao {
    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId AND parentFileId = :parentFileId ORDER BY isFolder DESC, fileName COLLATE NOCASE")
    abstract fun observeDirectory(accountId: String, parentFileId: Long): Flow<List<CloudFileEntity>>

    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId AND fileId = :fileId")
    abstract suspend fun get(accountId: String, fileId: Long): CloudFileEntity?

    @Upsert
    abstract suspend fun upsert(files: List<CloudFileEntity>)

    @Query("DELETE FROM cloud_files WHERE accountId = :accountId AND parentFileId = :parentFileId")
    abstract suspend fun deleteDirectory(accountId: String, parentFileId: Long)

    /** Only call with a complete successful directory snapshot, never for a failed network read. */
    @Transaction
    open suspend fun replaceDirectory(accountId: String, parentFileId: Long, files: List<CloudFileEntity>) {
        require(files.all { it.accountId == accountId && it.parentFileId == parentFileId })
        deleteDirectory(accountId, parentFileId)
        upsert(files)
    }

    /**
     * directory_states 的写入入口放在本 DAO，是为了让 [replaceDirectoryWithState] 的
     * 事务同时覆盖文件与状态两张表（Room 的 @Transaction 只能编排同一 DAO 内的方法）。
     */
    @Upsert
    abstract suspend fun upsertDirectoryState(state: DirectoryStateEntity)

    /**
     * 全量目录快照原子落库：删旧文件、upsert 新文件、upsert 目录状态
     * 在同一事务内完成，保证观察方看到的 files 与 total / allLoaded 永远来自同一次
     * 成功刷新。只允许在拿到完整全量数据后调用，部分失败一律不调用。
     */
    @Transaction
    open suspend fun replaceDirectoryWithState(
        accountId: String,
        parentFileId: Long,
        files: List<CloudFileEntity>,
        total: Int,
        allLoaded: Boolean,
        updatedAt: Long,
    ) {
        replaceDirectory(accountId, parentFileId, files)
        upsertDirectoryState(
            DirectoryStateEntity(
                accountId = accountId,
                dirId = parentFileId,
                total = total,
                allLoaded = allLoaded,
                updatedAt = updatedAt,
            ),
        )
    }
}
