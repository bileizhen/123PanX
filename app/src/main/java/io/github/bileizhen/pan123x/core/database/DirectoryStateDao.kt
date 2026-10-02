package io.github.bileizhen.pan123x.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 目录缓存元数据读写。写入的正规入口是 [CloudFileDao.replaceDirectoryWithState]——
 * 文件与状态必须同事务落库；单独 upsert 仅用于测试或未来的局部维护场景。
 */
@Dao
interface DirectoryStateDao {

    @Query("SELECT * FROM directory_states WHERE accountId = :accountId AND dirId = :dirId")
    fun observe(accountId: String, dirId: Long): Flow<DirectoryStateEntity?>

    @Query("SELECT * FROM directory_states WHERE accountId = :accountId AND dirId = :dirId")
    suspend fun get(accountId: String, dirId: Long): DirectoryStateEntity?

    @Upsert
    suspend fun upsert(state: DirectoryStateEntity)

    @Query("DELETE FROM directory_states WHERE accountId = :accountId AND dirId = :dirId")
    suspend fun delete(accountId: String, dirId: Long)
}
