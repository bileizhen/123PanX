// Credit rows and detail dialog styling adapted from LeiFetch AboutScreen.kt, GPL-3.0.
package io.github.bileizhen.pan123x.feature.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

internal data class AboutCredit(val name: String, val role: String, val detail: String)
internal data class AboutCreditGroup(val title: String, val members: List<AboutCredit>)
internal data class CreditFocus(val member: AboutCredit, val group: String)

internal val aboutCreditGroups = listOf(
    AboutCreditGroup("开发与维护", listOf(
        AboutCredit("bileizhen", "123PanX · 开发与维护", "123PanX 是面向 Android 的第三方 123 云盘客户端，以 GNU GPL v3 发布。"),
    )),
    AboutCreditGroup("开源致谢", listOf(
        AboutCredit("LeiFetch", "Android 界面与 NSFX 技术参考", "123PanX 的 Miuix 视觉、悬浮导航、液态玻璃、关于页及 NSFX Kotlin 下载核心来自 LeiFetch 的通用组件与技术实现。来源链包括 XBlocker 与 SukiSU-Ultra，详见第三方声明。"),
        AboutCredit("123panNextGen / 123pan", "123 云盘协议参考", "登录、文件、分享、离线与上传下载协议以 fluent-dev 分支为参考，在 Kotlin 中按职责重写。"),
        AboutCredit("Miuix", "Compose 组件、主题、模糊与动画", "由 compose-miuix-ui 提供，采用 Apache-2.0 许可证。"),
        AboutCredit("Hanabi / NSFX", "分段下载核心的上游来源", "本项目迁移 LeiFetch 的 Kotlin NSFX 通用下载能力，支持 HTTP Range、多连接、断点恢复与动态分段。保留来源和许可证说明。"),
    )),
)

@Composable
internal fun CreditAvatar(name: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(colorScheme.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
        Text(name.first().uppercase(), color = colorScheme.primary, fontSize = (size.value * .38f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun CreditRow(member: AboutCredit, onClick: () -> Unit) {
    BasicComponent(title = member.name, summary = member.role,
        modifier = Modifier.testTag("about_credit_${member.name.substringBefore(' ').lowercase()}"),
        startAction = { CreditAvatar(member.name, modifier = Modifier.padding(end = 6.dp)) },
        endActions = { Icon(PanIcons.Forward, null, tint = colorScheme.onSurfaceVariantActions) },
        onClick = onClick)
}
