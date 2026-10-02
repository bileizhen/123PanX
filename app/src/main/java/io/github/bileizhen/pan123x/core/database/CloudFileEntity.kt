package io.github.bileizhen.pan123x.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 云端文件的 Room 缓存行：进入目录先读缓存、后台刷新成功后整体替换。
 * createAt / updateAt 为 epoch 毫秒（v2 起从字符串改为数值，日期排序需要可比较数值）。
 * accountId 外键到 accounts，账户删除时级联清理，禁止跨账户共用缓存。
 */
@Entity(
    tableName = "cloud_files",
    primaryKeys = ["accountId", "fileId"],
    indices = [Index(value = ["accountId", "parentFileId"])],
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["accountId"], childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class CloudFileEntity(
    val accountId: String,
    val fileId: Long,
    val parentFileId: Long,
    val fileName: String,
    val isFolder: Boolean,
    val size: Long = 0,
    val etag: String = "",
    val s3KeyFlag: String = "",
    val createAt: Long = 0,
    val updateAt: Long = 0,
)
