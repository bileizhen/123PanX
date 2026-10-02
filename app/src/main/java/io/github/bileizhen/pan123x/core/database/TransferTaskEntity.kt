package io.github.bileizhen.pan123x.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class TransferDirection { DOWNLOAD, UPLOAD }
enum class TransferState {
    QUEUED, RESOLVING, RUNNING, PAUSED, WAITING_NETWORK, WAITING_USER,
    COMPLETING, COMPLETED, FAILED, CANCELED,
}

/**
 * Durable task identity never depends on an expiring CDN URL.
 *
 * destinationTree：SAF 目录树 uri；空串表示应用内目录（的保存位置需可恢复）。
 * segments：分段数，仅用于列表展示，避免为展示而 join download_segments。
 * 两列都带 @ColumnInfo(defaultValue)：迁移（v2→v3）以 ALTER TABLE ... DEFAULT 加列，
 * 实体侧必须声明同名默认值，否则 Room 打开数据库时会因 schema 校验不一致而抛异常。
 *
 * v4 新增列——上传任务用；下载任务保持默认值：
 * - [s3KeyFlag]：M4 遗留的下载恢复字段（下载重启后 resume 需要），上传不使用。
 * - [parentFileId]：上传目标目录 id（upload_request 的 parentFileId）。
 * - [bucket] / [storageNode] / [uploadKey] / [uploadId]：S3 multipart 会话字段。
 * - [sourceMtime]：上传源文件修改时间（续传校验：与当前文件 mtime 差 >1s 即放弃会话）。
 * - [blockSize]：会话内分片大小（；固定 5MiB，见 UploadPartPlan.BLOCK_SIZE）。
 *
 * **三处列复用（必须按 direction 理解，否则必然误读）**：
 * - [fileId]：**下载**时是云端文件 id；**上传**时是 `up_file_id`（，upload_request 返回的 data.FileId）。
 * - [etag]：**下载**时是云端文件 etag；**上传**时是**本地文件 MD5**（upload_request 的 etag 入参）。
 * - [downloadedBytes]：**下载**时是已下载字节；**上传**时表示**"已上传字节"**（上传进度）。
 *
 * 新增 8 列全部带 @ColumnInfo(defaultValue)，默认值必须与 MIGRATION_3_4 的 DEFAULT 字面量逐字一致，
 * 否则 Room 打开数据库时 schema 校验失败（M4 已踩过）。
 */
@Entity(
    tableName = "transfer_tasks",
    primaryKeys = ["accountId", "taskId"],
    indices = [Index(value = ["accountId", "state"])],
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["accountId"], childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TransferTaskEntity(
    val accountId: String,
    val taskId: String,
    val fileId: Long? = null,
    val fileName: String,
    val direction: TransferDirection,
    val state: TransferState = TransferState.QUEUED,
    val size: Long = 0,
    val etag: String = "",
    val targetUri: String = "",
    @ColumnInfo(defaultValue = "''") val destinationTree: String = "",
    @ColumnInfo(defaultValue = "0") val segments: Int = 0,
    val downloadedBytes: Long = 0,
    val createTime: Long = 0,
    val updateTime: Long = 0,
    val error: String? = null,
    @ColumnInfo(defaultValue = "''") val s3KeyFlag: String = "",
    @ColumnInfo(defaultValue = "0") val parentFileId: Long = 0,
    @ColumnInfo(defaultValue = "''") val bucket: String = "",
    @ColumnInfo(defaultValue = "''") val storageNode: String = "",
    @ColumnInfo(defaultValue = "''") val uploadKey: String = "",
    @ColumnInfo(defaultValue = "''") val uploadId: String = "",
    @ColumnInfo(defaultValue = "0") val sourceMtime: Long = 0,
    @ColumnInfo(defaultValue = "0") val blockSize: Long = 0,
)
