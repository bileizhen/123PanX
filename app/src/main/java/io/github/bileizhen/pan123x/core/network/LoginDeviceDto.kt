package io.github.bileizhen.pan123x.core.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Display fields from model.py DeviceItemModel; device keys and LoginUuid are discarded. */
data class LoginDeviceDto(
    val name: String = "",
    val platform: String = "",
    val ip: String = "",
    val lastLoginTime: String = "",
    val type: String = "",
    val current: Boolean = false,
    val loginType: String = "",
) {
    companion object {
        /** Array order and aliases follow DeviceListResponse.from_dict, including DeviceS. */
        fun listFromJson(element: JsonElement): List<LoginDeviceDto>? {
            val root = element as? JsonObject ?: return null
            val data = if ("data" in root) root["data"] as? JsonObject ?: return null else root
            val raw = listOf("DeviceS", "deviceList", "list", "device_list")
                .mapNotNull { data[it]?.takeUnless { value -> value == JsonNull || value == JsonArray(emptyList()) } }
                .firstOrNull() ?: return emptyList()
            val array = raw as? JsonArray ?: return null
            return array.map { item ->
                val device = item as? JsonObject ?: return null
                val current = device.first("curDevice", "currentDevice", "cur_device")
                LoginDeviceDto(
                    name = device.text("deviceName", "device_name"),
                    platform = device.text("platform", "platForm", "plat_form"),
                    ip = device.text("ip", "loginIp", "login_ip"),
                    lastLoginTime = device.text("lastLoginTime", "last_login_time", "LastLoginTime"),
                    type = device.text("deviceType", "device_type"),
                    current = current?.booleanOrNull ?: (current?.longOrNull?.let { it != 0L } ?: false),
                    loginType = device.text("loginType", "login_type"),
                )
            }
        }
        private fun JsonObject.first(vararg keys: String): JsonPrimitive? = keys.firstNotNullOfOrNull {
            (this[it] as? JsonPrimitive)?.takeUnless { value -> value == JsonNull || value.content.isEmpty() }
        }
        private fun JsonObject.text(vararg keys: String): String = first(*keys)?.content.orEmpty()
    }
}

interface PanDeviceApi {
    suspend fun getLoginDevices(): ApiResult<List<LoginDeviceDto>>
}
