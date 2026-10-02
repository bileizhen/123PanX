package io.github.bileizhen.pan123x.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 网页端分享链接前缀。参考源 `file_service.py:416`：
 * `return "https://www.123pan.cn/s/" + share_key`——这是分享**展示页**地址而非 API host，
 * 与 [ApiHosts.BASE_URL] 的 API 域名只是恰好同域，参考源亦为字面量，故不并入 ApiHosts。
 */
internal const val SHARE_URL_PREFIX = "https://www.123pan.cn/s/"

/** 分享列表分页游标：`"-1"` 表示没有更多页（参考源 model.py:317 缺省值）。 */
internal const val SHARE_NEXT_NO_MORE = "-1"

/**
 * 单个分享条目（`GET /b/api/share/list` 的 `data.InfoList[]` 元素），字段子集与双名兼容
 * 规则逐字对齐参考源 model.py `ShareItemModel.from_dict`（:273-301）：主键大驼峰、回退小驼峰
 * （如 `ShareId`/`shareId`）；数值解析失败按参考源 `int` 语义回退默认值，字符串缺失为空串。
 *
 * [fileIdList] 是服务端回传的**逗号连接字符串**（与创建请求的 `fileIdList` 同形）。
 * [shareLink] 取 `shareLinkList.list[0]`：`shareLinkList` 可能缺失 / null / 空对象
 * （参考源 `json.get("shareLinkList", {}) or {}`，model.py:274），此时为空串。
 */
data class ShareItemDto(
    val shareId: Long,
    val shareKey: String,
    val fileIdList: String,
    val downloadCount: Int,
    val previewCount: Int,
    val saveCount: Int,
    val shareName: String,
    val expiration: String,
    val expired: Boolean,
    val sharePwd: String,
    val status: Int,
    val createAt: String,
    val updateAt: String,
    val shareUrl: String,
    val shareLink: String,
) {
    companion object {
        internal fun fromJsonObject(item: JsonObject): ShareItemDto {
            // shareLinkList 容错：缺失 / null / 非 object 均按参考源的 `or {}` 处理为空对象
            val linkList = item["shareLinkList"] as? JsonObject
            // list 内首元素非字符串（含 null）时按无链接处理，不给用户拼出 "null" 文本
            val firstLink = (linkList?.get("list") as? JsonArray)?.firstOrNull()
            val shareLink = (firstLink as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            return ShareItemDto(
                shareId = item.optLong("ShareId", "shareId"),
                shareKey = item.optString("ShareKey", "shareKey"),
                fileIdList = item.optString("FileIdList", "fileIdList"),
                downloadCount = item.optLong("DownloadCount", "downloadCount").toInt(),
                previewCount = item.optLong("PreviewCount", "previewCount").toInt(),
                saveCount = item.optLong("SaveCount", "saveCount").toInt(),
                shareName = item.optString("ShareName", "shareName"),
                expiration = item.optString("Expiration", "expiration"),
                expired = item.boolOf("Expired", "expired"),
                sharePwd = item.optString("SharePwd", "sharePwd"),
                status = item.optLong("Status", "status").toInt(),
                createAt = item.optString("CreateAt", "createAt"),
                updateAt = item.optString("UpdateAt", "updateAt"),
                shareUrl = item.optString("ShareUrl", "shareUrl"),
                shareLink = shareLink,
            )
        }

        /** 依次尝试多个键，容错 true/false 与 0/1（服务端布尔字段两种形态都出现过）。 */
        private fun JsonObject.boolOf(vararg keys: String): Boolean {
            for (key in keys) {
                val value = this[key] as? JsonPrimitive ?: continue
                value.booleanOrNull?.let { return it }
                value.longOrNull?.let { return it != 0L }
            }
            return false
        }
    }
}

/**
 * 分享列表分页（`data` 对象），键名兼容规则照抄参考源 model.py `ShareListData.from_dict`
 * （:313-322）：`InfoList`/`Total`/`Next` 主键大驼峰、回退小驼峰。[next] 为服务端游标，
 * `"-1"`（[SHARE_NEXT_NO_MORE]）表示无更多，由调用方据此停止翻页。
 */
data class SharePageDto(
    val next: String,
    val total: Int,
    val items: List<ShareItemDto>,
) {
    companion object {
        /**
         * 从响应根解析（包络 code 已由 [PanApi] 统一校验）。data 缺失 / null / 非 object 时
         * 按参考源默认空页处理（model.py:337 `get("data", get("Data", {}))`），不算解析失败；
         * InfoList 行元素非 object 时跳过该行——分享行无缓存身份，跳过脏行优于整页失败。
         */
        internal fun fromJsonElement(root: JsonObject): SharePageDto {
            val data = (root["data"] as? JsonObject) ?: (root["Data"] as? JsonObject)
                ?: return SharePageDto(next = SHARE_NEXT_NO_MORE, total = 0, items = emptyList())
            val infoArray = (data["InfoList"] ?: data["infoList"]) as? JsonArray
            val items = infoArray.orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.let(ShareItemDto::fromJsonObject)
            }
            return SharePageDto(
                // 游标两键均缺失或为空白时按参考源缺省 "-1"（无更多），避免空游标被回传成 query
                next = data.optString("Next", "next").ifBlank { SHARE_NEXT_NO_MORE },
                total = data.optLong("Total", "total").toInt(),
                items = items,
            )
        }
    }
}

/**
 * 创建分享结果（`POST /b/api/share/create` 成功时的 data）。[shareKey] 即 `data.ShareKey`
 * （参考源 `file_service.py:415` 直接下标取值，仅此一个键名、无双名兼容）；[url] 为
 * [SHARE_URL_PREFIX] + shareKey 的网页分享链接（file_service.py:416）。
 */
data class CreateShareDto(
    val shareKey: String,
    val url: String,
) {
    companion object {
        /** ShareKey 缺失 / 空白返回 null，由调用方转 ParseError（参考源该键缺失会直接 KeyError）。 */
        internal fun fromData(data: JsonObject): CreateShareDto? {
            val shareKey = data.optString("ShareKey")
            if (shareKey.isBlank()) return null
            return CreateShareDto(shareKey = shareKey, url = SHARE_URL_PREFIX + shareKey)
        }
    }
}

/**
 * 分享协议 API（协议真源 `.reference/123pan`
 * `src/app/service/share_service.py`、`file_service.py#share` :378-417），供
 * `ShareRepository` / 分享入口消费；测试可用 MockWebServer 或替身实现。
 *
 * 协议要点（逐字对齐参考源，**禁止**"顺手改"）：
 * - 三个端点的 host 都是 [ApiHosts.SHARE_BASE_URL]（参考源 `SHARE_API_BASE =
 *   FALLBACK_BASE_URL`，share_service.py:22），不走主 / 备线路切换——参考源本身直连
 *   备用域，其下再无 fallback；
 * - 三个端点参考源均为 `timeout=10`；
 * - `create` / `delete` 为非幂等 POST，协议层不自动重试；`list` 为幂等
 *   GET，可对 429/5xx 有限退避重试。
 */
interface PanShareApi {

    /**
     * 创建分享（`POST /b/api/share/create`，参数由接口定义）。
     *
     * [fileIds] 由调用方去重归一化后传入（`fileIdList` 逗号连接字符串，`fileNum` = 其个数，
     * 对应参考源 `str(int(fid))` 归一化语义）；[sharePwd] 空 = 无密码（参考源 `or ""`）。
     * 成功取 `data.ShareKey`，链接 = "https://www.123pan.cn/s/" + ShareKey（file_service.py:415-416）。
     */
    suspend fun createShare(fileIds: List<Long>, sharePwd: String = ""): ApiResult<CreateShareDto> =
        createShare(fileIds, io.github.bileizhen.pan123x.core.share.ShareCreateOptions(password = sharePwd))

    suspend fun createShare(fileIds: List<Long>, options: io.github.bileizhen.pan123x.core.share.ShareCreateOptions): ApiResult<CreateShareDto>

    /**
     * 拉取免费分享列表（`GET /b/api/share/list`）。付费分享列表（get_pay_share_list）不在
     * M6 范围。[next] 为上一页 [SharePageDto.next]；`"-1"` 表示无更多，
     * 此时直接返回空页、不发网络请求。
     */
    suspend fun listShares(limit: Int = 500, next: String = "0", search: String = ""): ApiResult<SharePageDto>

    /** 撤销分享（`POST /b/api/share/delete`），`code==0` 即成功。 */
    suspend fun deleteShare(shareId: Long): ApiResult<Unit>
}
