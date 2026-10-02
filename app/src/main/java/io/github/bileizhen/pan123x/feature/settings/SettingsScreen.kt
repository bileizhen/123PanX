// Grouping and preferences migrated from LeiFetch MainActivity.settingsItems.
// SPDX-License-Identifier: GPL-3.0-only. Cloud-client preferences replace LeiFetch-specific business.
package io.github.bileizhen.pan123x.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bileizhen.pan123x.data.settings.AppSettings
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.component.SuperSwitch
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextButton

internal val SectionTitleMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
internal fun SettingsIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp), tint = MiuixTheme.colorScheme.onSurface)
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel, onAppearance: () -> Unit, onAccounts: () -> Unit = {}, onProxy: () -> Unit = {},
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    var actionError by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var numericEdit by remember { mutableStateOf("downloads") }
    var showNumeric by remember { mutableStateOf(false) }
    val directoryName by produceState("已选择下载目录", settings.downloadTree) {
        value = if (settings.downloadTree.isBlank()) "应用内下载目录" else withContext(Dispatchers.IO) {
            try { DocumentFile.fromTreeUri(context, Uri.parse(settings.downloadTree))?.name ?: "所选目录不可用，请重新选择" }
            catch (_: SecurityException) { "目录权限已失效，请重新选择" }
            catch (_: IllegalArgumentException) { "目录不可用，请重新选择" }
        }
    }
    val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                actionError = null
                viewModel.edit { it.copy(downloadTree = uri.toString()) }
            } catch (_: SecurityException) { actionError = "无法保存目录权限，请选择可读写的目录" }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings_screen"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
    ) {
        item { SmallTitle(uiText("传输"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                SuperSwitch(uiText("多线程下载"), settings.multiThreadDownload, { value -> viewModel.edit { it.copy(multiThreadDownload = value) } },
                    summary = uiText("支持 Range 的服务器使用分段并发下载"), tag = "setting_multi_thread")
                OverlaySpinnerPreference(
                    title = uiText("每个下载的连接数"), summary = uiText("自动回退单连接"),
                    startAction = { SettingsIcon(PanIcons.Download) },
                    items = AppSettings.CONNECTION_OPTIONS.map { DropdownItem(uiText("$it 个连接")) },
                    selectedIndex = AppSettings.CONNECTION_OPTIONS.indexOf(settings.downloadConnections),
                    onSelectedIndexChange = { index -> viewModel.edit { it.copy(downloadConnections = AppSettings.CONNECTION_OPTIONS[index]) } },
                    modifier = Modifier.testTag("setting_connections"),
                )
                OverlaySpinnerPreference(title = uiText("上传线程数"), summary = uiText("每个上传任务的并行分片数"),
                    items = AppSettings.UPLOAD_THREAD_OPTIONS.map { DropdownItem(uiText("$it 个分片")) },
                    selectedIndex = settings.uploadThreads - 1,
                    onSelectedIndexChange = { index -> viewModel.edit { it.copy(uploadThreads = index + 1) } },
                    modifier = Modifier.testTag("setting_upload_threads"))
                ArrowPreference(title = uiText("最大并发下载"), summary = uiText("${settings.maxConcurrentDownloads} 个任务"), onClick = { numericEdit = "downloads"; showNumeric = true }, modifier = Modifier.testTag("setting_max_downloads"))
                ArrowPreference(title = uiText("最大并发上传"), summary = uiText("${settings.maxConcurrentUploads} 个任务"), onClick = { numericEdit = "uploads"; showNumeric = true }, modifier = Modifier.testTag("setting_max_uploads"))
                ArrowPreference(title = uiText("下载限速"), summary = uiText(speedLabel(settings.downloadSpeedLimit)), onClick = { numericEdit = "downloadSpeed"; showNumeric = true }, modifier = Modifier.testTag("setting_download_speed"))
                ArrowPreference(title = uiText("上传限速"), summary = uiText(speedLabel(settings.uploadSpeedLimit)), onClick = { numericEdit = "uploadSpeed"; showNumeric = true }, modifier = Modifier.testTag("setting_upload_speed"))
            }
        }
        item { SmallTitle(uiText("保存位置"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = uiText("下载目录"), summary = if (settings.downloadTree.isBlank()) uiText(directoryName) else directoryName, startAction = { SettingsIcon(PanIcons.Folder) },
                    onClick = { chooseDirectory.launch(settings.downloadTree.takeIf { it.isNotBlank() }?.let(Uri::parse)) }, modifier = Modifier.testTag("setting_download_directory"))
                if (settings.downloadTree.isNotBlank()) ArrowPreference(title = uiText("使用应用内目录"), summary = uiText("已有任务继续使用原保存位置"), onClick = { viewModel.edit { it.copy(downloadTree = "") } })
                SuperSwitch(uiText("每次询问下载位置"), settings.askDownloadLocation, { value -> viewModel.edit { it.copy(askDownloadLocation = value) } },
                    summary = uiText("下载前选择本次保存目录，取消选择不会创建任务"), tag = "setting_ask_location")
            }
        }
        item { SmallTitle(uiText("网络行为"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                SuperSwitch(uiText("客户端模拟"), settings.clientSimulation, { value -> viewModel.edit { it.copy(clientSimulation = value) } },
                    summary = uiText("开启使用 Android 请求头，关闭使用 Web 请求头"), tag = "setting_client_simulation")
                SuperSwitch(uiText("错误退避重试"), settings.errorBackoffRetry, { value -> viewModel.edit { it.copy(errorBackoffRetry = value) } },
                    summary = uiText("传输遇到可恢复的错误时有限重试"), tag = "setting_backoff_retry")
            }
        }
        item { SmallTitle(uiText("网络代理"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = uiText("代理"), summary = uiText(state.proxy.effectiveMode.label),
                    onClick = onProxy, modifier = Modifier.testTag("setting_proxy_config"))
            }
        }
        item { SmallTitle(uiText("通知"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = uiText("通知设置"), summary = uiText("传输进度与完成提醒"), startAction = { SettingsIcon(PanIcons.Transfer) }, onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
                    catch (_: ActivityNotFoundException) { actionError = "当前设备无法打开通知设置" }
                })
            }
        }
        item { SmallTitle(uiText("账户"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = uiText("账户管理"), summary = uiText("切换或移除已保存的账户"), startAction = { SettingsIcon(PanIcons.Account) }, onClick = onAccounts)
            }
        }
        item { SmallTitle(uiText("通用"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = uiText("外观"), summary = uiText("主题、模糊、悬浮底栏与显示缩放"), startAction = { SettingsIcon(PanIcons.Grid) }, onClick = onAppearance, modifier = Modifier.testTag("settings_appearance"))
                OverlaySpinnerPreference(title = uiText("界面语言"), summary = "简体中文 / English",
                    items = io.github.bileizhen.pan123x.data.settings.AppLanguage.entries.map { DropdownItem(uiText(it.label)) },
                    selectedIndex = settings.language.ordinal,
                    onSelectedIndexChange = { index -> viewModel.edit { it.copy(language = io.github.bileizhen.pan123x.data.settings.AppLanguage.entries[index]) } }, modifier = Modifier.testTag("setting_language"))
                OverlaySpinnerPreference(title = uiText("文本预览上限"), summary = uiText("超过上限时提供下载"),
                    startAction = { SettingsIcon(PanIcons.File) },
                    items = AppSettings.TEXT_PREVIEW_OPTIONS.map { DropdownItem(formatBytes(it)) },
                    selectedIndex = AppSettings.TEXT_PREVIEW_OPTIONS.indexOf(settings.maxTextPreviewBytes),
                    onSelectedIndexChange = { index -> viewModel.edit { it.copy(maxTextPreviewBytes = AppSettings.TEXT_PREVIEW_OPTIONS[index]) } },
                    modifier = Modifier.testTag("setting_text_preview"))
                io.github.bileizhen.pan123x.ui.component.SuperSwitch(uiText("识别剪贴板分享链接"), settings.recognizeShareClipboard,
                    { value -> viewModel.edit { it.copy(recognizeShareClipboard = value) } }, summary = uiText("回到应用时提示打开 123 云盘分享"), tag = "setting_share_clipboard")
                SuperSwitch(uiText("启动时自动检查更新"), settings.autoCheckUpdates,
                    { value -> viewModel.edit { it.copy(autoCheckUpdates = value) } }, summary = uiText("只检查 GitHub 正式版，发现更新时提示"), tag = "setting_auto_updates")
            }
        }
        item { SmallTitle(uiText("维护"), insideMargin = SectionTitleMargin) }
        item {
            Card(Modifier.fillMaxWidth()) {
                OverlaySpinnerPreference(title = uiText("日志等级"), summary = uiText("日志只保留在内存，通过“我的”导出"),
                    items = AppSettings.LOG_LEVEL_OPTIONS.map { DropdownItem(it) },
                    selectedIndex = AppSettings.LOG_LEVEL_OPTIONS.indexOf(settings.logLevel),
                    onSelectedIndexChange = { index -> viewModel.edit { it.copy(logLevel = AppSettings.LOG_LEVEL_OPTIONS[index]) } }, modifier = Modifier.testTag("setting_log_level"))
                ArrowPreference(title = uiText("清理缓存"), summary = uiText("清理预览与图片缓存，保留下载文件和传输断点"), enabled = !state.busy,
                    onClick = viewModel::clearCache, modifier = Modifier.testTag("setting_clear_cache"))
                ArrowPreference(title = uiText("强制刷新文件列表"), summary = uiText("保留现有列表，在浏览时重新获取云端状态"), enabled = !state.busy,
                    onClick = viewModel::refreshFileLists, modifier = Modifier.testTag("setting_refresh_files"))
                ArrowPreference(title = uiText("删除文件列表缓存"), summary = uiText("仅删除当前账户的本地列表，不删除云端文件"), enabled = !state.busy,
                    onClick = { confirmReset = true }, modifier = Modifier.testTag("setting_reset_files"))
            }
        }
        (actionError ?: state.error)?.let { message -> item { Text(uiText(message), color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
        state.message?.let { message -> item { Text(uiText(message), modifier = Modifier.padding(16.dp).testTag("setting_result")) } }
    }
    numericEdit.let { key ->
        val speed = key.endsWith("Speed")
        val title = when (key) { "downloads" -> "最大并发下载"; "uploads" -> "最大并发上传"; "downloadSpeed" -> "下载限速"; else -> "上传限速" }
        val current = when (key) { "downloads" -> settings.maxConcurrentDownloads.toLong(); "uploads" -> settings.maxConcurrentUploads.toLong(); "downloadSpeed" -> settings.downloadSpeedLimit / 1024; else -> settings.uploadSpeedLimit / 1024 }
        NumericSettingDialog(title, current, if (speed) 0L..(AppSettings.MAX_SPEED_LIMIT / 1024) else 1L..32L,
            summary = if (speed) "单位 KiB/s，0 表示不限速；上传与下载分别限制总速度" else "范围 1–32；降低上限不会中断正在执行的任务",
            show = showNumeric, onDismiss = { showNumeric = false }, onSave = { value ->
                viewModel.edit { old -> when (key) {
                    "downloads" -> old.copy(maxConcurrentDownloads = value.toInt()); "uploads" -> old.copy(maxConcurrentUploads = value.toInt())
                    "downloadSpeed" -> old.copy(downloadSpeedLimit = value * 1024); else -> old.copy(uploadSpeedLimit = value * 1024)
                } }; showNumeric = false
            })
    }
    OverlayDialog(show = confirmReset, title = uiText("删除文件列表缓存？"), onDismissRequest = { confirmReset = false }) {
        Text(uiText("只清除当前账户在本机保存的文件列表。云端文件、账户和传输任务均会保留。"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(uiText("取消"), onClick = { confirmReset = false }, modifier = Modifier.weight(1f))
            TextButton(uiText("清除"), onClick = { confirmReset = false; viewModel.resetFileLists() }, modifier = Modifier.weight(1f).testTag("setting_reset_confirm"))
        }
    }
}

private fun speedLabel(bytes: Long) = if (bytes == 0L) "不限速" else "${formatBytes(bytes)}/s"
