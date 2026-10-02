// Adapted from XBlocker / SukiSU-Ultra ui/SendLogDialog.kt; GPL-3.0.
package io.github.bileizhen.pan123x.feature.logs

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.bileizhen.pan123x.data.diagnostics.DiagnosticReport
import io.github.bileizhen.pan123x.ui.component.PanIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun SendLogDialog(show: Boolean, report: DiagnosticReport, onDismiss: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try {
                val file = report.create()
                try {
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开保存位置" }.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        }
                    }
                } finally { file.delete() }
                message("日志已保存")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("无法保存日志，请重新选择可写入的位置") }
            finally { busy = false }
        }
    }
    OverlayDialog(show = show && !busy, title = uiText("导出日志"), summary = uiText("应用日志与设备信息会打包为 ZIP，并自动隐藏敏感信息。"), onDismissRequest = onDismiss) {
        ArrowPreference(title = uiText("保存日志"), summary = "选择保存位置", startAction = { Icon(PanIcons.Download, null, Modifier.padding(end = 12.dp)) },
            modifier = Modifier.testTag("export_logs_save"), onClick = {
                onDismiss()
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm_ss"))
                try { save.launch("123PanX_logs_$stamp.zip") } catch (_: Exception) { message("无法打开文件选择器") }
            })
        ArrowPreference(title = uiText("分享日志"), summary = uiText("使用系统分享"), startAction = { Icon(PanIcons.Share, null, Modifier.padding(end = 12.dp)) },
            modifier = Modifier.testTag("export_logs_share"), onClick = {
                if (!busy) scope.launch {
                    onDismiss(); busy = true
                    try {
                        val file = report.create()
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("123PanX 日志", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享日志"))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message("无法分享日志，请尝试保存日志") }
                    finally { busy = false }
                }
            })
        TextButton(uiText("取消"), onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("export_logs_cancel"))
    }
    OverlayDialog(show = busy, title = uiText("正在生成日志"), onDismissRequest = {}) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
