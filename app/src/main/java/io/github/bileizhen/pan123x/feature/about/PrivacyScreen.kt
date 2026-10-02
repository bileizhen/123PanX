// Grouped document layout adapted from LeiFetch AboutDocumentScreen, GPL-3.0.
package io.github.bileizhen.pan123x.feature.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card

@Composable
fun PrivacyScreen() {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("privacy_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card { BasicComponent(title = uiText("账号与凭据"), summary = "登录凭据通过 Android Keystore 和 AES-GCM 在本机加密保存；登录与云盘操作会连接 123 云盘的对应接口。") } }
        item { Card { BasicComponent(title = uiText("云盘与登录设备"), summary = "会员、容量和文件总数按账户缓存在本机；账户号码脱敏显示。查看云盘信息时从 123 云盘获取登录设备，设备名称、IP 与登录时间仅保留在内存中，不进入诊断包。") } }
        item { Card { BasicComponent(title = uiText("文件与传输"), summary = "云盘浏览会缓存文件元数据。上传会将所选文件发送到云盘，下载与预览会连接对应的文件服务器。") } }
        item { Card { BasicComponent(title = uiText("本地存储"), summary = "文件写入应用内目录或你授权的下载目录。传输进度、分段状态和设置保存在本机。") } }
        item { Card { BasicComponent(title = uiText("日志"), summary = "运行日志默认保留在内存中。主动导出时，最近应用日志、当前进程系统日志与设备信息会打包为 ZIP；密码、认证信息及链接参数统一脱敏。仅在你选择保存或系统分享后导出。") } }
        item { Card { BasicComponent(title = uiText("剪贴板"), summary = "启用分享链接识别后，仅在应用前台并获得焦点时检查剪贴板。识别结果只在本次应用会话中去重，不上传、不写入日志；由你确认后打开分享网页。可在设置中关闭。") } }
        item { Card { BasicComponent(title = uiText("关于页"), summary = "维护者与致谢头像使用本地文字绘制。点击外部链接时，通过系统浏览器打开对应网站。") } }
    }
}
