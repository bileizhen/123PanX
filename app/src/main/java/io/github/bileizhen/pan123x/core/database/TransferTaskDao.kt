package io.github.bileizhen.pan123x.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferTaskDao {
    @Query("SELECT * FROM transfer_tasks WHERE accountId = :accountId ORDER BY createTime DESC")
    fun observeTasks(accountId: String): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE accountId = :accountId AND taskId = :taskId")
    suspend fun get(accountId: String, taskId: String): TransferTaskEntity?

    @Upsert
    suspend fun upsert(task: TransferTaskEntity)

    @Query("DELETE FROM transfer_tasks WHERE accountId = :accountId AND taskId = :taskId")
    suspend fun delete(accountId: String, taskId: String)

    /**
     * 清理终态任务（M5 工作台"清除已完成/已取消"， 的 clearFinished）。
     * FAILED 不算"完成"——失败任务用户可能还要排查/重试，保留在列表里；
     * 分片/分段行经复合外键 CASCADE 一并清理（upload_parts / download_segments）。
     */
    @Query("DELETE FROM transfer_tasks WHERE accountId = :accountId AND state IN ('COMPLETED','CANCELED')")
    suspend fun deleteFinished(accountId: String)

    /**
     * 高频进度写入：只 UPDATE 三个列，绝不 upsert 整行，避免每个网络 chunk
     * 都重写任务表。state 以 [TransferState.name] 的字符串传入——Room 默认把枚举按 name 存为
     * TEXT，直接绑定字符串与列亲和度一致，比依赖查询参数的枚举转换更稳妥。
     */
    @Query(
        "UPDATE transfer_tasks SET downloadedBytes = :downloadedBytes, state = :state, updateTime = :updateTime " +
            "WHERE accountId = :accountId AND taskId = :taskId",
    )
    suspend fun updateProgress(accountId: String, taskId: String, downloadedBytes: Long, state: String, updateTime: Long)

    /**
     * 观察"进行中"任务（QUEUED/RESOLVING/RUNNING/COMPLETING）。状态字面量对应 [TransferState]
     * 的英文枚举名，Room 以 name 存为 TEXT，因此可直接在 SQL 中比较。
     */
    @Query(
        "SELECT * FROM transfer_tasks WHERE accountId = :accountId " +
            "AND state IN ('QUEUED','RESOLVING','RUNNING','COMPLETING') ORDER BY createTime DESC",
    )
    fun observeActive(accountId: String): Flow<List<TransferTaskEntity>>

    /**
     * 可恢复任务（QUEUED/RESOLVING/RUNNING/COMPLETING）的快照，供"继续全部"之类的显式用户操作
     * 取候选集；不做任何自动重启。
     */
    @Query(
        "SELECT * FROM transfer_tasks WHERE accountId = :accountId " +
            "AND state IN ('QUEUED','RESOLVING','RUNNING','COMPLETING') ORDER BY createTime ASC",
    )
    suspend fun pendingResumable(accountId: String): List<TransferTaskEntity>

    /**
     * 跨账户的进行中任务快照：recoverOnStart 在进程重启后需要把所有账户的遗留活跃行转
     * WAITING_USER（多账户 +  不自动重启），因此不能按 accountId 过滤。
     */
    @Query("SELECT * FROM transfer_tasks WHERE state IN ('QUEUED','RESOLVING','RUNNING','COMPLETING')")
    suspend fun activeTasks(): List<TransferTaskEntity>

    /**
     * 按方向取遗留活跃任务快照。
     *
     * 上传协调器的 recoverOnStart 只应处理 UPLOAD 行、下载协调器只应处理 DOWNLOAD 行，避免两个
     * 协调器互相改写对方的任务状态。[direction] 以 [TransferDirection.name] 字符串传入。
     *
     * 刻意写成**默认方法**（在 Kotlin 侧基于 [activeTasks] 过滤）而非新增 `@Query` 抽象方法：
     * 启动恢复每次进程只调用一次，数据量小；而新增抽象方法会强制所有既有实现（含测试替身）同步
     * 改造。Room 只处理带注解的抽象方法，普通默认方法会被原样继承。
     */
    suspend fun activeTasksByDirection(direction: String): List<TransferTaskEntity> =
        activeTasks().filter { it.direction.name == direction }
}
