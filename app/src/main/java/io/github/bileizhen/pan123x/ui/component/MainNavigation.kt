package io.github.bileizhen.pan123x.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.theme.isInDarkTheme

// Pure-color geometry adapted from LeiFetch PlainFloatingBar; GPL-3.0.
// Kept independent of miuix-blur so API 26–32 never instantiate shader classes.
@Composable
fun PlainFloatingBar(selected: Int, labels: List<String>, icons: List<ImageVector>, onSelect: (Int) -> Unit) {
    val background = if (isInDarkTheme()) MiuixTheme.colorScheme.surfaceContainer else Color.White
    Row(Modifier.fillMaxWidth().height(64.dp).testTag("solid_floating_bar").shadow(8.dp, CircleShape).clip(CircleShape)
        .background(background).padding(4.dp).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val tint = if (selected == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                .background(if (selected == index) tint.copy(alpha = .12f) else Color.Transparent)
                .testTag("tab_$index").selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
                Icon(icons[index], null, tint = tint)
                Text(label, color = tint, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
fun StandardNavigationBar(selected: Int, labels: List<String>, icons: List<ImageVector>, onSelect: (Int) -> Unit) {
    NavigationBar(modifier = Modifier.testTag("standard_navigation_bar")) {
        labels.forEachIndexed { index, label ->
            NavigationBarItem(
                modifier = Modifier.weight(1f).testTag("tab_$index"),
                selected = selected == index, onClick = { onSelect(index) }, icon = icons[index], label = label,
            )
        }
    }
}

@Composable
fun MainSidebar(selected: Int, labels: List<String>, icons: List<ImageVector>, onSelect: (Int) -> Unit) {
    Column(Modifier.width(200.dp).fillMaxHeight().background(MiuixTheme.colorScheme.surfaceContainer)
        .statusBarsPadding().navigationBarsPadding().padding(16.dp).testTag("main_sidebar").selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("123PanX", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 20.dp))
        labels.forEachIndexed { index, label ->
            if (index == labels.lastIndex) Spacer(Modifier.weight(1f))
            val tint = if (selected == index) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
            Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                .background(if (selected == index) MiuixTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                .testTag("tab_$index").selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icons[index], null, tint = tint)
                Text(label, color = tint)
            }
        }
    }
}
