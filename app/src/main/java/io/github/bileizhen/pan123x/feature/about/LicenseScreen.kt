package io.github.bileizhen.pan123x.feature.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun LicenseScreen(asset: String) {
    val context = LocalContext.current
    val text by produceState<String?>(initialValue = null, asset) {
        value = withContext(Dispatchers.IO) {
            try { context.assets.open(asset).bufferedReader().use { it.readText() } }
            catch (_: IOException) { "无法读取许可文档" }
        }
    }
    LazyColumn(modifier = Modifier.testTag("license_screen"), contentPadding = PaddingValues(20.dp)) {
        item { Text(text ?: "正在读取…", fontSize = 13.sp) }
    }
}
