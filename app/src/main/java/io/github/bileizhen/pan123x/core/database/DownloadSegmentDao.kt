package io.github.bileizhen.pan123x.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 下载分段进度读写。
 *
 * 声明为抽象类而非接口，是为了与 [CloudFileDao] 的事务编排风格一致：Room 的 @Transaction
 * 只能覆盖同一 DAO 内的抽象方法，把"先删后插"的默认实现放在本类里，观察方就永远不会看到
 * 半套分段（的分段表整体替换语义）。
 */
@Dao
abstract class DownloadSegmentDao {

    /** 观察某任务的全部分段（按段序升序），供传输工作台绘制分段点阵。 */
    @Query("SELECT * FROM download_segments WHERE accountId = :accountId AND taskId = :taskId ORDER BY segmentIndex ASC")
    abstract fun observe(accountId: String, taskId: String): Flow<List<DownloadSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(segments: List<DownloadSegmentEntity>)

    /** 清空某任务的全部分段（取消下载、断点失效重置时使用）。 */
    @Query("DELETE FROM download_segments WHERE accountId = :accountId AND taskId = :taskId")
    abstract suspend fun clear(accountId: String, taskId: String)

    /**
     * 分段计划整体替换：只在分段计划确定或发生动态拆分时调用，事务内先删后插。
     * 禁止每个网络 tick 调用，调用方需先比对计划是否真的变化。
     */
    @Transaction
    open suspend fun replaceFor(accountId: String, taskId: String, segments: List<DownloadSegmentEntity>) {
        clear(accountId, taskId)
        if (segments.isNotEmpty()) insert(segments)
    }
}
