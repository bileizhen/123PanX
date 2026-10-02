// SPDX-License-Identifier: GPL-3.0-only
// Migrated from bileizhen/LeiFetch TransferWorkspace.kt FilterBar; PanX supplies counts and actions.
package io.github.bileizhen.pan123x.feature.transfer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.bileizhen.pan123x.ui.component.miuix.animation.DampedDragAnimation
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
@Composable
internal fun TransferFilterBar(selected: Int, counts: List<Int>, onSelect: (Int) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val colors = MiuixTheme.colorScheme
    val labels = listOf("进行中", "已停止", "已完成", "全部").map(uiText)
    // 分段控件：一条轨道 + 一块滑动的选中指示。按住可左右拖动、松手吸附到最近的分段，
    // 与底部悬浮导航栏共用同一套 DampedDragAnimation，手感保持一致。
    val inset = 3.dp
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    var trackWidth by remember { mutableFloatStateOf(0f) }
    val insetPx = with(density) { inset.toPx() }
    val segmentPx = ((trackWidth - 2 * insetPx) / labels.size).coerceAtLeast(1f)
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestSegment by rememberUpdatedState(segmentPx)
    val damped = remember(animationScope, labels.size) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selected.toFloat(),
            valueRange = 0f..(labels.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1f,
            onDragStarted = {},
            onDragStopped = {},
            onDrag = { _, _ -> },
        )
    }
    // 自己接横向手势而不用 DampedDragAnimation 自带的检测器：那个不区分方向，
    // 会把在轨道上起手的竖向滑动一起吃掉，列表就滚不动了。
    val dragModifier = Modifier.pointerInput(damped, labels.size) {
        detectHorizontalDragGestures(
            onDragStart = { damped.press() },
            onDragEnd = {
                // 松手吸附到最近分段；与当前选中相同就弹回原位，否则交给外层切筛选。
                val target = damped.targetValue.roundToInt().coerceIn(0, labels.size - 1)
                if (target == latestSelected) damped.animateToValue(target.toFloat()) else latestOnSelect(target)
            },
            onDragCancel = { damped.animateToValue(latestSelected.toFloat()) },
            onHorizontalDrag = { change, delta ->
                change.consume()
                val segment = latestSegment
                if (segment > 0f) {
                    damped.updateValue((damped.targetValue + delta / segment)
                        .coerceIn(0f, (labels.size - 1).toFloat()))
                }
            },
        )
    }
    // 点击分段或拖动提交后，指示块滑到新位置。
    LaunchedEffect(selected) { damped.animateToValue(selected.toFloat()) }
    Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(46.dp)
        .onSizeChanged { trackWidth = it.width.toFloat() }
        .then(dragModifier)
        .clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer)) {
        Box(Modifier.fillMaxSize().padding(inset)) {
            Box(Modifier
                .layout { measurable, constraints ->
                    // 内层 Box 已缩进 inset，这里只按位置平移、宽度取整段，四边留白才一致；
                    // 半径取 12 - 3 = 9dp，与轨道内缘同心，内圆角就不会和外圆角错开。
                    val segment = ((trackWidth - 2 * inset.toPx()) / labels.size).coerceAtLeast(1f)
                    val placeable = measurable.measure(Constraints.fixed(segment.roundToInt(), constraints.maxHeight))
                    layout(placeable.width, placeable.height) {
                        placeable.place((damped.value * segment).roundToInt(), 0)
                    }
                }
                .testTag("transfer_filter_indicator")
                .background(colors.primary, RoundedCornerShape(9.dp)))
            Row(Modifier.fillMaxSize().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val active = index == selected
                    val foreground by animateColorAsState(if (active) colors.onPrimary else colors.onSurfaceVariantSummary,
                        animationSpec = tween(200), label = "filterFg")
                    Row(Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .testTag("transfer-filter-${io.github.bileizhen.pan123x.feature.transfer.TransferFilter.entries[index].name}")
                        .selectable(active, role = Role.Tab, onClick = { onSelect(index) }),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = foreground)
                        // 计数只挂在选中段上；直接显示，不做宽度动画，避免逐帧改变分段布局。
                        if (active) Text("${counts.getOrElse(index) { 0 }}", fontSize = 13.sp,
                            color = foreground.copy(alpha = .75f), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
