package io.github.bileizhen.pan123x.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 123 云盘通用响应包络：`{"code":int,"message":str,"data":{...}}`。
 * 部分接口错误时使用 "msg" 键；code 缺失时按 -1 处理（对应参考源 `session.py` body.get("code", -1)）。
 */
@Serializable
data class ApiEnvelope(
    val code: Int = -1,
    val message: String? = null,
    val msg: String? = null,
)

/** `/b/api/user/sign_in` 成功时的 data。 */
@Serializable
data class LoginData(
    val token: String = "",
)

/**
 * 云盘用户信息（`/b/api/user/info`），键名兼容规则逐字段照抄参考源
 * `model.py CloudUserInfoModel.from_dict`：主键大驼峰，回退小驼峰首词（如 uid / spaceUsed）。
 * 数值字段容错 string / int，布尔字段容错 0 / 1。
 */
@Serializable
data class UserInfoDto(
    val uid: Long = 0L,
    val nickname: String = "",
    val spaceUsed: Long = 0L,
    val spaceTotal: Long = 0L,
    val spaceTemp: Long = 0L,
    val fileCount: Long = 0L,
    val vip: Boolean = false,
    val vipExpire: String = "",
    val vipLevel: Int = 0,
    val headImage: String = "",
    val directTraffic: Long = 0L,
    val shareTraffic: Long = 0L,
    val passport: Long = 0L,
    val professionalSpacePermanent: Long = 0L,
    val professionalSpaceUsed: Long = 0L,
    val standardSpacePermanent: Long = 0L,
    val standardSpaceUsed: Long = 0L,
) {
    companion object {

        /**
         * 从响应根元素解析；沿用参考源语义：存在 "data" 键则取 data，否则把根元素本身当 data。
         * 结构不符（data 非 object、根非 object）返回 null，由调用方转 ParseError。
         */
        fun fromJsonElement(element: JsonElement): UserInfoDto? {
            val root = element as? JsonObject ?: return null
            val data = if (root.containsKey("data")) {
                root["data"] as? JsonObject ?: return null
            } else {
                root
            }
            return UserInfoDto(
                uid = data.longOf("UID", "uid"),
                nickname = data.stringOf("Nickname", "nickname"),
                spaceUsed = data.longOf("SpaceUsed", "spaceUsed"),
                spaceTotal = data.longOf("SpacePermanent", "spacePermanent"),
                spaceTemp = data.longOf("SpaceTemp", "spaceTemp"),
                fileCount = data.longOf("FileCount", "fileCount"),
                vip = data.boolOf("Vip", "vip"),
                vipExpire = data.stringOf("VipExpire", "vipExpire"),
                vipLevel = data.longOf("VipLevel", "vipLevel").toInt(),
                headImage = data.stringOf("HeadImage", "headImage"),
                directTraffic = data.longOf("DirectTraffic", "directTraffic"),
                shareTraffic = data.longOf("ShareTraffic", "shareTraffic"),
                passport = data.longOf("Passport", "passport"),
                professionalSpacePermanent = data.longOf("ProfessionalSpacePermanent", "professionalSpacePermanent"),
                professionalSpaceUsed = data.longOf("ProfessionalSpaceUsed", "professionalSpaceUsed"),
                standardSpacePermanent = data.longOf("StandardSpacePermanent", "standardSpacePermanent"),
                standardSpaceUsed = data.longOf("StandardSpaceUsed", "standardSpaceUsed"),
            )
        }

        /** 依次尝试多个键，返回第一个可解析的整数（容错 string / int），否则 0。 */
        private fun JsonObject.longOf(vararg keys: String): Long {
            for (key in keys) {
                val value = this[key] as? JsonPrimitive ?: continue
                value.longOrNull?.let { return it }
            }
            return 0L
        }

        /** 依次尝试多个键，返回第一个字符串值，否则空串。 */
        private fun JsonObject.stringOf(vararg keys: String): String {
            for (key in keys) {
                val value = this[key] as? JsonPrimitive ?: continue
                return value.content
            }
            return ""
        }

        /** 依次尝试多个键，容错 true/false 与 0/1，否则 false。 */
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
