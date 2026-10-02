package io.github.bileizhen.pan123x.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.bileizhen.pan123x.core.share.ShareClipboardGate
import io.github.bileizhen.pan123x.core.share.SharedLink
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ClipboardSharePrompt(enabled: Boolean, onOpenInApp: (SharedLink) -> Unit = {}) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val gate = remember { ShareClipboardGate() }
    var pending by remember { mutableStateOf<SharedLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(enabled, view, lifecycle) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var disposed = false
        val read = Runnable {
            if (!disposed && enabled && view.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try {
                    val clip = clipboard?.primaryClip
                    val own = clip?.description?.label?.toString()?.startsWith("123PanX 分享") == true
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (text != null) gate.take(text, own)?.let { pending = it; error = null }
                } catch (_: SecurityException) { error = "无法读取剪贴板，请在系统设置中允许访问" }
            }
        }
        fun schedule() { view.removeCallbacks(read); view.post(read) }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) schedule() }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) schedule() }
        val listener = ClipboardManager.OnPrimaryClipChangedListener { schedule() }
        if (enabled) {
            lifecycle.addObserver(observer)
            view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
            clipboard?.addPrimaryClipChangedListener(listener)
            schedule()
        }
        onDispose {
            disposed = true
            view.removeCallbacks(read)
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            clipboard?.removePrimaryClipChangedListener(listener)
        }
    }
    val link = pending
    OverlayDialog(show = enabled && link != null, title = uiText("发现 123 云盘分享"), summary = uiText("可在应用内查看、转存或下载分享文件"), onDismissRequest = { pending = null }) {
        if (link != null) {
            SelectionContainer { Text(link.url, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("clipboard_share_url")) }
            if (link.password.isNotBlank()) Text("提取码：${link.password}", modifier = Modifier.padding(bottom = 16.dp).testTag("clipboard_share_password"))
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(uiText("忽略"), onClick = { pending = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("clipboard_share_ignore"))
                TextButton(uiText("应用内打开"), colors = ButtonDefaults.textButtonColorsPrimary(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("clipboard_share_open"), onClick = {
                    pending = null
                    onOpenInApp(link)
                })
            }
        }
    }
}
