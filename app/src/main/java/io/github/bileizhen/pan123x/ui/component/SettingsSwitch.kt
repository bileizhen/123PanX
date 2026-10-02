// Adapted from LeiFetch SettingsSwitch (originally XBlocker); GPL-3.0.
package io.github.bileizhen.pan123x.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Switch

@Composable
fun SuperSwitch(
    title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    summary: String? = null, enabled: Boolean = true, tag: String,
    startAction: (@Composable () -> Unit)? = null,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    BasicComponent(
        modifier = Modifier.testTag(tag).semantics {
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            if (!enabled) disabled()
        },
        title = uiText(title), summary = summary?.let(uiText), enabled = enabled, role = Role.Switch,
        startAction = startAction,
        onClick = { onCheckedChange(!checked) },
        endActions = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
    )
}
