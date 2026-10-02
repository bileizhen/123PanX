package io.github.bileizhen.pan123x.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 上传分片计划镜像。
 *
 * 为什么单独成表而不是塞进 transfer_tasks：分片计划（partNumber/size）行数可变，若并入任务行，
 * 每次分片变化都要重写整行（明确禁止每个网络 chunk 更新整表）。独立表让分片计划
 * 成为"整体替换/单条标记"的独立写入单元。
 *
 * **语义边界（与下载断点本质不同）**：S3 multipart 的断点是**分片粒度**，权威来源是服务端
 * `s3_list_upload_parts`；本表只是本地镜像，供 UI 展示分片点阵，**不作为续传判据**
 * （续传以 transfer_tasks 里的 S3 会话字段 + 服务端 parts 列表为准）。因此本表不设 done 列。
 *
 * 主键 (accountId, taskId, partNumber) 保证同账户同任务下分片唯一；accountId 参与主键与外键，
 * 杜绝跨账户共用。复合外键指向 transfer_tasks(accountId, taskId) 并 CASCADE：
 * 任务行被删除时分片行随之清理，不留下孤儿数据。
 */
@Entity(
    tableName = "upload_parts",
    primaryKeys = ["accountId", "taskId", "partNumber"],
    indices = [Index(value = ["accountId", "taskId"])],
    foreignKeys = [ForeignKey(
        entity = TransferTaskEntity::class,
        parentColumns = ["accountId", "taskId"],
        childColumns = ["accountId", "taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class UploadPartEntity(
    val accountId: String,
    val taskId: String,
    val partNumber: Int,
    val size: Int,
)
