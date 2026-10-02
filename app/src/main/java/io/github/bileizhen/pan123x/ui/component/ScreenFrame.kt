// SPDX-License-Identifier: GPL-3.0-only
// Pinned Miuix app bar and grouped layout inspired by LeiFetch.
package io.github.bileizhen.pan123x.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*

val LocalPageTitle = staticCompositionLocalOf { "" }
val LocalContentBottomPadding = staticCompositionLocalOf { 24.dp }

/** Insets belong to each scene so predictive back fills the whole window. */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    translateTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    topBarBottom: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Scaffold(
        topBar = {
          Column {
            SmallTopAppBar(title = if (translateTitle) uiText(title) else title, actions = actions, navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("navigate_back")) {
                    Icon(PanIcons.Back, contentDescription = uiText("返回"))
                }
            })
            topBarBottom()
          }
        },
        popupHost = {},
    ) { padding ->
        CompositionLocalProvider(LocalPageTitle provides uiText(title)) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { content() }
        }
    }
}
