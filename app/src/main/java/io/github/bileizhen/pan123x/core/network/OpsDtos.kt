package io.github.bileizhen.pan123x.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * M3 文件操作接口的响应 data（协议真源 `.reference/123pan` session_file.py）。
 * 手动 JsonObject 解析，风格对齐 FileDtos.kt：主键大驼峰、回退小驼峰；结构宽松，
 * 缺键回退默认值，绝不在成功 code 下因 data 形状抛异常。
 */

/**
 * `/a/api/file/trash` 成功时的 data：`InfoList[].FileId` 为实际生效的文件 id，
 * `AbnormalFileIdList` 为服务端标记异常的文件 id。data 缺失 / null / 非 object 时
 * 两个列表均为空（code==0 仍视为成功，由调用方自行决定是否告警）。
 */
data class TrashData(
    val infoList: List<Long>,
    val abnormalFileIds: List<Long>,
) {
    companion object {
        fun fromJsonElement(element: JsonElement): TrashData {
            val data = element.jsonDataOrNull()
                ?: return TrashData(infoList = emptyList(), abnormalFileIds = emptyList())
            val infoIds = (data["InfoList"] ?: data["infoList"]).jsonArrayOrEmpty()
                .mapNotNull { item -> (item as? JsonObject)?.optLongOrNull("FileId", "fileId") }
            val abnormalIds = (data["AbnormalFileIdList"] ?: data["abnormalFileIdList"]).jsonArrayOrEmpty()
                .mapNotNull { item -> (item as? JsonPrimitive)?.longOrNull }
            return TrashData(infoList = infoIds, abnormalFileIds = abnormalIds)
        }
    }
}

/**
 * `/b/api/restful/goapi/v1/file/copy/async` 成功时的 data：任务 id 在 `taskId|taskID`
 * 双大小写键（参考源 `copy_files_async` 的 `get("taskId") or get("taskID")`，空串同样
 * 视为缺失）。两键均无值时 [taskId] 为 null，由 FileOpsRepository 转用户可读失败。
 */
data class CopySubmitData(
    val taskId: String?,
) {
    companion object {
        fun fromJsonElement(element: JsonElement): CopySubmitData? {
            val root = element as? JsonObject ?: return null
            val taskId = root.jsonDataOrNull()?.optString("taskId", "taskID").orEmpty().trim()
            return CopySubmitData(taskId = taskId.ifBlank { null })
        }
    }
}

/**
 * `GET /b/api/restful/goapi/v1/file/copy/task` 成功时的 data。status 缺失保持 null，
 * 由 FileOpsRepository 落到参考源 `copy_file_task` 的防御分支（无 status 视为成功）。
 * status：1 进行中 / 2 成功 / 3 失败（failMsg 给出原因）/ 4 等待。
 */
data class CopyTaskData(
    val status: Int?,
    val failMsg: String?,
) {
    companion object {
        fun fromJsonElement(element: JsonElement): CopyTaskData? {
            val root = element as? JsonObject ?: return null
            val data = root.jsonDataOrNull()
                ?: return CopyTaskData(status = null, failMsg = null)
            val status = data.optLongOrNull("status")?.toInt()
            val failMsg = data.optString("failMsg").trim().ifBlank { null }
            return CopyTaskData(status = status, failMsg = failMsg)
        }
    }
}

/** 取包络中的 data 对象：存在 "data" 键且为 object 时返回，缺失 / null / 非 object 返回 null。 */
private fun JsonElement.jsonDataOrNull(): JsonObject? {
    val root = this as? JsonObject ?: return null
    val data = root["data"] ?: return null
    return data as? JsonObject
}

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

/** 依次尝试多个键，返回第一个可解析的整数（容错 string / int），全部缺失或不可解析为 null。 */
internal fun JsonObject.optLongOrNull(vararg keys: String): Long? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.longOrNull?.let { return it }
    }
    return null
}

/** 依次尝试多个键，返回第一个可解析的整数（容错 string / int），否则 [default]。 */
internal fun JsonObject.optLong(vararg keys: String, default: Long = 0L): Long =
    optLongOrNull(*keys) ?: default

/** 依次尝试多个键，返回第一个字符串值（跳过 JSON null 与非原始值），否则空串。 */
internal fun JsonObject.optString(vararg keys: String): String {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        val primitive = value as? JsonPrimitive ?: continue
        return primitive.content
    }
    return ""
}
