package io.github.bileizhen.pan123x.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 目录缓存元数据：一次全量刷新成功后的 total / allLoaded / updatedAt。
 * 与 cloud_files 的目录快照配合，用于区分“确为空目录”与“从未加载过”；对应参考实现
 * file_list_db 中按目录记录的分页游标信息。accountId 外键级联，多账户互不共用。
 */
@Entity(
    tableName = "directory_states",
    primaryKeys = ["accountId", "dirId"],
    indices = [Index(value = ["accountId"])],
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["accountId"], childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DirectoryStateEntity(
    val accountId: String,
    val dirId: Long,
    val total: Int,
    val allLoaded: Boolean,
    val updatedAt: Long,
)
