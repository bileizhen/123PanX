package io.github.bileizhen.pan123x.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 下载分段进度。
 *
 * 为什么单独成表而不是塞进 transfer_tasks：分段计划（index/start/end）行数可变，且 NSFX 的
 * 动态尾段拆分会在下载过程中改写计划；若并入任务行，每次计划变化都要重写整行（
 * 明确禁止每个网络 chunk 更新整表）。独立表让分段进度成为"整体替换"的独立写入单元。
 *
 * 主键 (accountId, taskId, segmentIndex) 保证同账户同任务下分段唯一；accountId 参与主键与
 * 外键，杜绝跨账户共用。复合外键指向 transfer_tasks(accountId, taskId) 并
 * CASCADE：任务行被删除时分段行随之清理，不留下孤儿数据。
 */
@Entity(
    tableName = "download_segments",
    primaryKeys = ["accountId", "taskId", "segmentIndex"],
    indices = [Index(value = ["accountId", "taskId"])],
    foreignKeys = [ForeignKey(
        entity = TransferTaskEntity::class,
        parentColumns = ["accountId", "taskId"],
        childColumns = ["accountId", "taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DownloadSegmentEntity(
    val accountId: String,
    val taskId: String,
    val segmentIndex: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long = 0,
)
