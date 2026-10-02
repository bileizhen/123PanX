package io.github.bileizhen.pan123x.feature.files

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import io.github.bileizhen.pan123x.core.share.ShareCreateOptions
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix grouped form; only confirmed protocol fields are configurable. */
@Composable
fun ShareCreateDialog(
    show: Boolean = true, count: Int, busy: Boolean, initialName: String = "123云盘分享",
    onDismiss: () -> Unit, onConfirm: (ShareCreateOptions) -> Unit, submissionError: String? = null,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    var name by rememberSaveable { mutableStateOf(initialName) }
    var expiry by rememberSaveable { mutableIntStateOf(3) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var password by rememberSaveable { mutableStateOf("") }
    var randomCode by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var serverError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(show) {
        if (show) { name = initialName; expiry = 3; mode = 0; password = ""; randomCode = ShareCreateOptions.randomPassword(); attempted = false; serverError = null }
    }
    LaunchedEffect(submissionError) { if (attempted && submissionError != null) serverError = submissionError }
    val options = ShareCreateOptions(name.trim(), when (mode) { 1 -> randomCode; 2 -> password.trim(); else -> "" }, listOf(1, 7, 30, 0)[expiry])
    val validationError = if (mode == 2 && password.isBlank()) "请输入 4 位提取码" else options.validationError()
    val maxContentHeight = (LocalWindowInfo.current.containerDpSize.height - WindowInsets.ime.asPaddingValues().calculateBottomPadding() - 230.dp).coerceIn(180.dp, 520.dp)
    OverlayDialog(show = show, title = uiText("分享链接设置"), onDismissRequest = { if (!busy) onDismiss() }) {
      Column(Modifier.fillMaxWidth().heightIn(max = maxContentHeight)) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("dialog_share_form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("已选择 $count 个文件", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            SmallTitle(uiText("分享主题"), insideMargin = PaddingValues(top = 4.dp, bottom = 0.dp))
            TextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("dialog_share_name"))
            SmallTitle(uiText("有效期"), insideMargin = PaddingValues(top = 4.dp, bottom = 0.dp))
            TabRowWithContour(tabs = listOf("1 天", "7 天", "30 天", "长期").map(uiText), selectedTabIndex = expiry,
                onTabSelected = { expiry = it }, minWidth = 0.dp, itemSpacing = 0.dp, height = 44.dp, modifier = Modifier.testTag("dialog_share_expiry"))
            SmallTitle(uiText("提取码"), insideMargin = PaddingValues(top = 4.dp, bottom = 0.dp))
            TabRowWithContour(tabs = listOf("无提取码", "随机", "自定义").map(uiText), selectedTabIndex = mode,
                onTabSelected = { mode = it }, minWidth = 0.dp, itemSpacing = 0.dp, height = 44.dp, modifier = Modifier.testTag("dialog_share_mode"))
            AnimatedVisibility(visible = mode != 0, enter = fadeIn() + expandVertically(spring()), exit = fadeOut() + shrinkVertically(spring())) {
                Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("提取码：$randomCode", modifier = Modifier.padding(vertical = 12.dp).testTag("dialog_share_random"))
                        TextButton(uiText("换一个"), onClick = { randomCode = ShareCreateOptions.randomPassword() })
                    } else TextField(value = password, onValueChange = { password = it.take(4) }, label = uiText("4 位字母或数字"), singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("dialog_share_pwd"))
                }
            }
        }
        val error = if (attempted) validationError ?: serverError else null
        error?.let { Text(it, color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("dialog_share_error")) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(uiText(if (busy) "正在创建…" else "创建链接"), onClick = { attempted = true; serverError = null; if (validationError == null) onConfirm(options) }, enabled = !busy,
                colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("dialog_share_ok"))
        }
      }
    }
}
