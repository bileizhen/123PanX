// SPDX-License-Identifier: GPL-3.0-only
// Miuix popup language follows LeiFetch TransferWorkspace; actions use PanX's existing ViewModel.
package io.github.bileizhen.pan123x.feature.transfer

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.bileizhen.pan123x.core.database.TransferState
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TransferActionsMenu(show: Boolean, state: TransferUiState, onDismiss: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onClear: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val visible = remember { MutableTransitionState(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(show) { if (show) pending = null; visible.targetState = show }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) {
            val action = pending
            pending = null
            action?.invoke()
        }
    }
    if (!visible.currentState && !visible.targetState) return
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val offset = with(LocalDensity.current) { IntOffset(0, 48.dp.roundToPx()) }
    Popup(alignment = Alignment.TopEnd, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visible, modifier = Modifier.padding(8.dp),
            enter = fadeIn(tween(120)) + scaleIn(spring(dampingRatio = .9f, stiffness = 600f), initialScale = .94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = .96f, transformOrigin = TransformOrigin(1f, 0f))) {
            Column(Modifier.width(220.dp).testTag("transfer_actions_menu").shadow(8.dp, shape).clip(shape)
                .background(colors.surfaceContainer).border(.5.dp, colors.onSurface.copy(alpha = .08f), shape).padding(6.dp)) {
                val resumable = setOf(TransferState.PAUSED, TransferState.WAITING_NETWORK, TransferState.FAILED)
                TransferMenuItem(uiText("暂停本组"), PanIcons.Pause, "transfer_pause_visible", show && state.visible.any { it.state in ACTIVE_TRANSFER_STATES }) { pending = onPause; onDismiss() }
                TransferMenuItem(uiText("继续本组"), PanIcons.Play, "transfer_resume_visible", show && state.visible.any { it.state in resumable }) { pending = onResume; onDismiss() }
                TransferMenuItem(uiText("清除已完成"), PanIcons.Trash, "transfer_clear_finished", show && state.tasks.any { it.state in setOf(TransferState.COMPLETED, TransferState.CANCELED) }) { pending = onClear; onDismiss() }
            }
        }
    }
}

@Composable
private fun TransferMenuItem(label: String, icon: ImageVector, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
        .testTag(tag).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 14.sp, color = tint)
    }
}
