package io.github.bileizhen.pan123x.core.network

/**
 * 123 云盘 API 域名集中定义，对应参考源 `123pan/src/app/api/constants.py`。
 * 禁止在其他位置散落硬编码 host。
 */
object ApiHosts {
    /** 主 API 域名。 */
    const val BASE_URL = "https://www.123pan.cn"

    /** 备用 API 域名（主域名连接层失败且路径含 /api/ 时切换，成功后进程内粘滞）。 */
    const val FALLBACK_BASE_URL = "https://api.123278.com"

    /** 二维码登录专用域名（M7 实现，不参与 fallback）。 */
    const val LOGIN_BASE_URL = "https://login.123pan.com"

    /** 离线下载专用域名（解析/提交任务固定使用）。 */
    const val OFFLINE_BASE_URL = "https://api.123278.com"

    /**
     * 分享端点专用域名（M6 创建 / 列表 / 撤销固定使用，不参与主备切换）。
     * 参考源 `share_service.py:22`：`SHARE_API_BASE = FALLBACK_BASE_URL`，三端点全部直连本域。
     */
    const val SHARE_BASE_URL = FALLBACK_BASE_URL
}
