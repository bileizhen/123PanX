package io.github.bileizhen.pan123x.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Only regenerable file metadata. Accounts, credentials and transfer checkpoints are untouched. */
@Dao
abstract class CacheMaintenanceDao {
    @Query("UPDATE directory_states SET updatedAt = 0 WHERE accountId = :accountId")
    abstract suspend fun invalidate(accountId: String)
    @Query("DELETE FROM cloud_files WHERE accountId = :accountId")
    abstract suspend fun deleteFiles(accountId: String)
    @Query("DELETE FROM directory_states WHERE accountId = :accountId")
    abstract suspend fun deleteDirectories(accountId: String)
    @Transaction
    open suspend fun clearFileMetadata(accountId: String) { deleteFiles(accountId); deleteDirectories(accountId) }
}
