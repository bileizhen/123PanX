// SPDX-License-Identifier: GPL-3.0-only
// LeiFetch TransferWorkspace outline actions and segmented filtering, adapted for PanX.
package io.github.bileizhen.pan123x.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WorkspaceAction(icon: ImageVector, label: String, onClick: () -> Unit, tag: String = "", enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(icon, contentDescription = label, tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .35f))
    }
}

@Composable
fun WorkspaceTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, tags: List<String> = emptyList()) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer).padding(4.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val background by animateColorAsState(if (index == selected) colors.surface else Color.Transparent, label = "tabBackground")
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(background)
                .testTag(tags.getOrElse(index) { "workspace_tab_$index" })
                .selectable(index == selected, role = Role.Tab, onClick = { onSelect(index) }), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == selected) colors.primary else colors.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}
