package io.github.bileizhen.pan123x.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 上传分片计划读写。
 *
 * 声明为抽象类而非接口，是为了让 [replaceFor] 的"先删后插"事务与 [DownloadSegmentDao] 风格一致：
 * Room 的 @Transaction 只能覆盖同一 DAO 内的抽象方法，把默认实现放在本类里，观察方就永远不会
 * 看到半套分片计划（的分片表整体替换语义）。
 */
@Dao
abstract class UploadPartDao {

    /** 观察某任务的全部分片（按分片号升序），供传输工作台绘制分片点阵。 */
    @Query("SELECT * FROM upload_parts WHERE accountId = :accountId AND taskId = :taskId ORDER BY partNumber ASC")
    abstract fun observe(accountId: String, taskId: String): Flow<List<UploadPartEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(parts: List<UploadPartEntity>)

    /** 清空某任务的全部分片（取消上传、续传会话失效重置时使用）。 */
    @Query("DELETE FROM upload_parts WHERE accountId = :accountId AND taskId = :taskId")
    abstract suspend fun clear(accountId: String, taskId: String)

    /**
     * 分片计划整体替换：只在分片计划确定时调用，事务内先删后插。
     * 禁止每个分片完成都调用，单分片完成请用 [markUploaded]。
     */
    @Transaction
    open suspend fun replaceFor(accountId: String, taskId: String, parts: List<UploadPartEntity>) {
        clear(accountId, taskId)
        if (parts.isNotEmpty()) insert(parts)
    }

    /** 单分片完成（分片粒度、低频，不需要节流）。REPLACE 语义对同分片号幂等。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun markUploaded(part: UploadPartEntity)
}
