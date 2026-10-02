// Miuix grouped settings/dialog language inherited from LeiFetch, GPL-3.0-only.
package io.github.bileizhen.pan123x.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bileizhen.pan123x.core.network.ProxyConfig
import io.github.bileizhen.pan123x.ui.component.SuperSwitch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NumericSettingDialog(title: String, current: Long, range: LongRange, summary: String, show: Boolean, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    var text by remember { mutableStateOf(current.toString()) }
    LaunchedEffect(show) { if (show) text = current.toString() }
    val number = text.toLongOrNull()
    OverlayDialog(show = show, title = uiText(title), onDismissRequest = onDismiss) {
        Text(uiText(summary), modifier = Modifier.padding(bottom = 12.dp))
        TextField(value = text, onValueChange = { text = it.filter(Char::isDigit).take(10) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("setting_number_input"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = onDismiss, modifier = Modifier.weight(1f))
            TextButton(uiText("保存"), onClick = { number?.takeIf { it in range }?.let(onSave) }, enabled = number != null && number in range,
                modifier = Modifier.weight(1f).testTag("setting_number_save"))
        }
    }
}
