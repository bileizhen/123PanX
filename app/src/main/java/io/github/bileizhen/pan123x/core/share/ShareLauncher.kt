package io.github.bileizhen.pan123x.core.share

import io.github.bileizhen.pan123x.data.share.ShareOutcome

/**
 * 文件页"分享"入口的窄接口（分层约束：UI / ViewModel 不得直接依赖分享 API）。
 * 模式与 `DownloadLauncher` / `UploadLauncher` 一致：ViewModel 单测注入假实现即可。
 */
fun interface ShareLauncher {

    /** 将所选云端文件及标题、有效期、提取码提交为一条分享链接。 */
    suspend fun launch(fileIds: List<Long>, options: ShareCreateOptions): ShareOutcome
}
