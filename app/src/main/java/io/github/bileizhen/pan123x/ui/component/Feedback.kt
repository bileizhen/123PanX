package io.github.bileizhen.pan123x.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.component.LocalPageTitle
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Shared Miuix feedback for all feature pages. */
@Composable
fun PageHeading(title: String, subtitle: String) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (LocalPageTitle.current.isEmpty()) Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(uiText(subtitle), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PanIcons.Folder, null, modifier = Modifier.size(40.dp), tint = MiuixTheme.colorScheme.primary)
            Text(uiText(title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(uiText(description), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
fun ConfirmActionDialog(title: String, description: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit, show: Boolean = true) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    OverlayDialog(show = show, title = uiText(title), summary = uiText(description), onDismissRequest = onDismiss) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(uiText("取消"), onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    TextButton(uiText(confirmLabel), onClick = onConfirm, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
    }
}
