// SPDX-License-Identifier: GPL-3.0-only
// Adapted from bileizhen/LeiFetch SwipeActionRow; only generic gesture behavior retained.
// 列表行的横向滑动操作：右滑露出多选动作，左滑露出删除动作。动作面板宽度逐帧跟手，
// 位移与裁剪都发生在绘制阶段（不触发逐帧重组）；松手按位移与速度判定：越过阈值直接提交，
// 否则按弹簧归位到露出位或原位。提交后由调用方的 removing 状态驱动整行滑出淡出，
// 取消确认时反向弹回 —— 删除与撤销是同一段动画的正反播放。
package io.github.bileizhen.pan123x.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleClip
import kotlin.math.abs
import kotlin.math.max

/** 一侧滑动动作的外观：文案、图标、渐变与露出宽度（决定松手后停住的位置）。 */
internal class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val colors: List<Color>,
    val width: Dp = 96.dp,
)

/** 删除动作的红色渐变：外沿最深，向卡片方向提亮。 */
internal val SwipeDeleteColors = listOf(Color(0xFFFF7B7F), Color(0xFFE03B41))

/** 行的几何量：测量后写入，绘制与手势阶段读取，不参与重组。 */
@Stable
private class SwipeMetrics {
    var row = 0f
    var start = 0f
    var end = 0f
    var arm = 0f
}

private val SwipeSettle = spring<Float>(dampingRatio = 0.85f, stiffness = 420f, visibilityThreshold = 0.5f)
private val SwipeExit = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)

/** 越过露出宽度后的阻尼：手指要多走约一倍行程才能把面板推到底，避免误触发提交。 */
private const val SwipeResistance = 0.45f

/** 行的圆角，必须与行内卡片自身的一致（卡片是 Miuix 连续圆角，裁剪也要用同一种）。 */
private val RowCorner = 20.dp

/**
 * 行级左右滑动容器。
 *
 * @param start 右滑露出的动作（行的左端）。
 * @param end 左滑露出的动作（行的右端）。
 * @param revealed 本行是否为当前唯一露出动作面板的行。
 * @param onRevealChange 露出状态变化回调，用于让同组其他行收回。
 * @param removing 整行滑出屏幕外并淡出（删除待确认），置回 false 即反向弹回。
 */
@Composable
internal fun SwipeActionRow(
    start: SwipeAction?,
    end: SwipeAction?,
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    removing: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val metrics = remember { SwipeMetrics() }
    with(density) {
        metrics.start = (start?.width ?: 0.dp).toPx()
        metrics.end = (end?.width ?: 0.dp).toPx()
        metrics.arm = 46.dp.toPx()
    }
    val anchor = remember { Animatable(0f) }
    // 跟手位移与锚点动画分离：拖动时读 dragPx，其余时刻读 anchor；两者都只在绘制阶段取值。
    var dragPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    val commitStart by rememberUpdatedState(onStart)
    val commitEnd by rememberUpdatedState(onEnd)
    val revealChange by rememberUpdatedState(onRevealChange)
    val offset = { if (dragging) dragPx else anchor.value }
    // 滑出进度：越过露出宽度后逐步淡出，让整行连同动作面板一起消失，取消时再一起回来。
    val fading = {
        val value = offset()
        if (value < 0f) ((abs(value) - metrics.end) / (metrics.row - metrics.end).coerceAtLeast(1f))
            .coerceIn(0f, 1f) else 0f
    }

    // 露出位与滑出位由「另一行被露出 / 删除待确认」驱动；取消确认时反向弹回。
    LaunchedEffect(revealed, removing) {
        if (dragging) return@LaunchedEffect
        armed = false
        when {
            removing -> anchor.animateTo(-(metrics.row + max(metrics.end, metrics.arm)), SwipeExit)
            !revealed && anchor.value != 0f -> anchor.animateTo(0f, SwipeSettle)
        }
    }

    val gesture = if (start != null || end != null) Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        // 松手判定：拖得够远，或在同一方向上甩得够快。提交前先确认该侧存在动作。
        suspend fun release() {
            if (!dragging) return
            val current = dragPx
            val velocity = tracker.calculateVelocity().x
            tracker.resetTracking()
            anchor.snapTo(current)
            dragging = false
            armed = false
            val towardStart = current > 0f
            val reveal = if (towardStart) metrics.start else metrics.end
            val exists = reveal > 0f
            val committed = exists && (abs(current) >= reveal + metrics.arm * 0.5f ||
                (abs(velocity) >= 1100f && abs(current) >= reveal * 0.6f && (velocity > 0f) == towardStart))
            if (committed) {
                if (towardStart) {
                    // 多选提交：整行弹回原位，选中态交由行内容动画呈现。
                    commitStart()
                    anchor.animateTo(0f, SwipeSettle)
                    revealChange(false)
                } else {
                    // 删除提交：停在滑出位，等调用方的 removing 接力滑出屏幕。
                    commitEnd()
                }
                return
            }
            val stay = reveal > 0f && (abs(current) > reveal * 0.45f ||
                (abs(velocity) > 700f && (velocity > 0f) == towardStart))
            anchor.animateTo(if (stay) (if (towardStart) reveal else -reveal) else 0f, SwipeSettle)
            revealChange(stay)
        }
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragCancel = { scope.launch { release() } },
            onDragEnd = { scope.launch { release() } },
            onHorizontalDrag = { change, delta ->
                if (metrics.row <= 0f) return@detectHorizontalDragGestures
                change.consume()
                val current = offset()
                val raw = current + delta
                // 阻尼只作用在「继续往外拖」的那一小段位移上：往回拖永远 1:1，
                // 拖到一半想收回就一定能收回；若按位置整体阻尼，手感会卡在露出位回不去。
                val reveal = if (raw > 0f) metrics.start else metrics.end
                val next = if (reveal <= 0f) 0f else {
                    val away = abs(raw) > abs(current) && abs(current) >= reveal
                    (current + if (away) delta * SwipeResistance else delta)
                        .coerceIn(-(metrics.end + metrics.arm), metrics.start + metrics.arm)
                }
                dragging = true
                dragPx = next
                tracker.addPosition(change.uptimeMillis, change.position)
                // 到达待触发点时轻震一下：此时松手即提交。
                val nowArmed = reveal > 0f && abs(next) >= reveal + metrics.arm * 0.5f
                if (nowArmed && !armed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                armed = nowArmed
            },
        )
    } else Modifier

    Box(modifier.onSizeChanged { metrics.row = it.width.toFloat() }.squircleClip(RowCorner)) {
        // 动作面板不参与尺寸测量，高度与行内容一致。
        end?.let { SwipeActionPanel(it, false, offset, fading, metrics, Modifier.matchParentSize()) }
        start?.let { SwipeActionPanel(it, true, offset, fading, metrics, Modifier.matchParentSize()) }
        Box(
            Modifier.fillMaxWidth().then(gesture).graphicsLayer {
                translationX = offset()
                val exit = fading()
                alpha = 1f - exit
                val scale = 1f - 0.06f * exit
                scaleX = scale
                scaleY = scale
            },
        ) { content() }
    }
}

@Composable
private fun SwipeActionPanel(
    action: SwipeAction,
    fromStart: Boolean,
    offset: () -> Float,
    fading: () -> Float,
    metrics: SwipeMetrics,
    modifier: Modifier = Modifier,
) {
    val reveal = if (fromStart) metrics.start else metrics.end
    // 只认本侧的位移：右滑时不该把左滑的删除面板也画出来（层叠处会露出接缝）。
    val pulled = { val value = offset(); (if (fromStart) value else -value).coerceAtLeast(0f) }
    // 图标与文案随露出进度淡入；面板底色保持不透明，像是压在卡片下面的一层。
    val contentAlpha = { (pulled() / reveal.coerceAtLeast(1f)).coerceIn(0f, 1f) * (1f - fading()) }
    Row(
        modifier
            .drawWithContent {
                // 面板宽度跟随手指；内容锚在行的外沿，靠裁剪自然露出，无需额外的位移动画。
                val visible = pulled().coerceIn(0f, size.width)
                if (visible <= 0.5f) return@drawWithContent
                val alpha = 1f - fading()
                if (alpha <= 0.01f) return@drawWithContent
                val span = (reveal * 1.8f).coerceIn(1f, size.width)
                val brush = if (fromStart) Brush.horizontalGradient(action.colors, startX = 0f, endX = span)
                else Brush.horizontalGradient(action.colors, startX = size.width - span, endX = size.width)
                // 底色铺满整行：卡片整体压在面板上，卡片自己的圆角处露出的就是动作色，
                // 而不是被咬掉一块背景。内容仍只露出手指拖出的那一段。
                drawRect(brush, size = size, alpha = alpha)
                val left = if (fromStart) 0f else size.width - visible
                val right = if (fromStart) visible else size.width
                clipRect(left, 0f, right, size.height) { this@drawWithContent.drawContent() }
            }
            .padding(horizontal = 20.dp),
        horizontalArrangement = if (fromStart) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fromStart) {
            Icon(action.icon, null, tint = Color.White,
                modifier = Modifier.size(20.dp).graphicsLayer { alpha = contentAlpha() })
            Text(action.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
                maxLines = 1, modifier = Modifier.padding(start = 8.dp).graphicsLayer { alpha = contentAlpha() })
        } else {
            Text(action.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
                maxLines = 1, modifier = Modifier.padding(end = 8.dp).graphicsLayer { alpha = contentAlpha() })
            Icon(action.icon, null, tint = Color.White,
                modifier = Modifier.size(20.dp).graphicsLayer { alpha = contentAlpha() })
        }
    }
}
