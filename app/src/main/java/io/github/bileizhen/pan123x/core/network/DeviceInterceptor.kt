package io.github.bileizhen.pan123x.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 为每个请求附加 123 云盘 Android 客户端伪装头，逐字段对应参考源
 * `constants.py` CLIENT_SIMULATION_HEADERS + `session.py` _build_headers。
 *
 * 不在此设置 content-type（由请求体决定）与 accept-encoding（gzip 由 OkHttp BridgeInterceptor 处理）。
 */
class DeviceInterceptor(private val provider: () -> DeviceProfile, private val simulation: () -> Boolean = { true }) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val device = provider()
        val builder = chain.request().newBuilder()
        if (!simulation()) {
            listOf("devicename", "x-app-version", "osversion", "devicetype").forEach(builder::removeHeader)
            return chain.proceed(builder.header("platform", "web").header("app-version", "3")
                .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")
                .header("loginuuid", device.loginUuid).build())
        }
        val request = builder
            .header("platform", device.platform)
            .header("devicename", device.deviceName)
            .header("app-version", device.appVersion)
            .header("x-app-version", device.xAppVersion)
            .header("user-agent", device.userAgent)
            .header("osversion", device.osVersion)
            .header("devicetype", device.deviceType)
            .header("loginuuid", device.loginUuid)
            .build()
        return chain.proceed(request)
    }
}
