package io.github.bileizhen.pan123x.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 离线下载协议 API，供 `OfflineRepository` 消费；
 * 测试可用 MockWebServer 或替身实现。
 *
 * 协议要点（协议真源 `.reference/123pan` `offline_service.py:51-98`）：
 * - 两端点的 canonical host 均为 `OFFLINE_BASE_URL`（参考源 `api_url(path, OFFLINE_BASE_URL)`
 *   直连该域，不走主 / 备线路切换）；
 * - 均为非幂等 POST，协议层**不**自动重试，读超时 30s（参考源两处调用均
 *   `timeout=30`）；
 * - `resolve` 的 data.list[] 逐项 `result==0` 才算解析成功，失败项带 err_code/err_msg
 *   （参考源 offline_service.py:61 透传不解析，字段名按 view 层实际用法定，
 *   offline_download_dialog.py:431-476 / :504——解析双名兼容，缺省容错）；
 * - `submit` 的 data.task_list[] 每项含 task_id / result（offline_download_dialog.py:554）。
 */
interface PanOfflineApi {

    /**
     * 解析离线下载链接（`POST /b/api/v2/offline_download/task/resolve`）。
     * [urls] 为多行链接原文（换行分隔），逐字作为 body `{"urls": ...}` 发送。
     * 返回逐条解析结果（[OfflineResolvedItem.ok] = result==0），**部分失败不算接口失败**。
     */
    suspend fun resolve(urls: String): ApiResult<List<OfflineResolvedItem>>

    /**
     * 提交离线下载任务（`POST /b/api/v2/offline_download/task/submit`）。
     * [resources] 形如 `resource_id -> select_file_id 列表`（空列表 = 整个资源，
     * offline_download_dialog.py:521）；返回服务端任务逐项结果。
     */
    suspend fun submit(resources: List<OfflineResource>): ApiResult<List<OfflineSubmittedTask>>
}

/**
 * resolve 结果中的单个文件条目（多文件资源如种子内文件）。字段名以 view 层用法为准做
 * 双名兼容：主键 `id`（offline_download_dialog.py:103 `f.get("id", 0)`），回退
 * `fileId` / `file_id`。
 */
data class OfflineResolvedFile(
    val fileId: Long,
    val name: String,
    val size: Long,
) {
    companion object {
        /** 非 object 项返回 null 由调用方跳过；缺键容错回默认值，成功 code 下绝不抛异常。 */
        fun fromJsonElement(element: JsonElement): OfflineResolvedFile? {
            val obj = element as? JsonObject ?: return null
            return OfflineResolvedFile(
                fileId = obj.optLong("id", "fileId", "file_id"),
                name = obj.optString("name"),
                size = obj.optLong("size"),
            )
        }
    }
}

/**
 * resolve 结果中的单个资源条目（[ok] = `result==0`）。
 *
 * 双名兼容键（snake_case 为 view 层实际用法，camelCase 为服务端可能的另一形态）：
 * - resourceId：`id`（主键，offline_download_dialog.py:504）/ `resourceId` / `resource_id`；
 * - fileNums：`file_nums` / `fileNums`；
 * - errMessage：`err_msg`（offline_download_dialog.py:468）/ `errMsg` / `message`。
 *
 * [type] 协议为整数；若服务端返回字符串形态（如 "http"/"magnet"，view 层 `_TYPE_TEXT`
 * 以字符串为键暗示过该可能），解析为 0，UI 徽标需对 0 做兜底展示。
 */
data class OfflineResolvedItem(
    val url: String,
    val type: Int,
    val ok: Boolean,
    val name: String,
    val size: Long,
    val resourceId: Long,
    val fileNums: Int,
    val files: List<OfflineResolvedFile>,
    val errMessage: String,
) {
    companion object {

        /**
         * 解析包络根中的 `data.list[]`；data / list 缺失或非数组按空列表处理
         * （参考源 offline_service.py:74 `(body.get("data") or {}).get("list") or []` 容错）。
         */
        fun listFromRoot(root: JsonObject): List<OfflineResolvedItem> {
            val data = root["data"] as? JsonObject ?: return emptyList()
            val list = data["list"] as? JsonArray ?: return emptyList()
            return list.mapNotNull { element ->
                (element as? JsonObject)?.let(::fromJsonObject)
            }
        }

        private fun fromJsonObject(obj: JsonObject): OfflineResolvedItem {
            // result 缺失按失败处理（view 层 `res.get("result", 1) == 0` 的缺省值即 1）
            val result = obj.optLong("result", default = 1L)
            return OfflineResolvedItem(
                url = obj.optString("url"),
                type = obj.optLong("type").toInt(),
                ok = result == 0L,
                name = obj.optString("name"),
                size = obj.optLong("size"),
                resourceId = obj.optLong("id", "resourceId", "resource_id"),
                fileNums = obj.optLong("file_nums", "fileNums").toInt(),
                files = (obj["files"] as? JsonArray)?.listOfFiles() ?: emptyList(),
                errMessage = obj.optString("err_msg", "errMsg", "message"),
            )
        }

        private fun JsonArray.listOfFiles(): List<OfflineResolvedFile> =
            mapNotNull(OfflineResolvedFile::fromJsonElement)
    }
}

/** submit 的单个资源：[selectFileIds] 为空表示下载整个资源（view 层 :521 语义）。 */
data class OfflineResource(
    val resourceId: Long,
    val selectFileIds: List<Long>,
)

/**
 * submit 的单个任务结果：[ok] = `result==0`（offline_download_dialog.py:554 缺省按失败）；
 * taskId 双名兼容 `task_id`（主）/ `taskId`。
 */
data class OfflineSubmittedTask(
    val taskId: String,
    val ok: Boolean,
    val errMessage: String,
) {
    companion object {

        /** 解析 `data.task_list[]`；缺失 / 非数组按空列表处理（offline_service.py:96 容错）。 */
        fun listFromRoot(root: JsonObject): List<OfflineSubmittedTask> {
            val data = root["data"] as? JsonObject ?: return emptyList()
            val list = data["task_list"] as? JsonArray ?: return emptyList()
            return list.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                OfflineSubmittedTask(
                    taskId = obj.optString("task_id", "taskId"),
                    ok = obj.optLong("result", default = 1L) == 0L,
                    errMessage = obj.optString("err_msg", "errMsg", "message"),
                )
            }
        }
    }
}
