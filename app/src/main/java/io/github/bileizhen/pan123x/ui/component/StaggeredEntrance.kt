// SPDX-License-Identifier: GPL-3.0-only
// Migrated from bileizhen/LeiFetch ui/TransferWorkspace.kt (StaggeredEntrance).
package io.github.bileizhen.pan123x.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
/** ReactBits Animated List 式入场（对应其 useInView）：条目进入视口后才按行序错峰弹出
 *  （缩放 + 淡入 + 轻微上移）。首屏条目逐个显示；滚动进入的远端条目立即弹出，不拖慢滚动；
 *  压栈页面传 baseDelayMs 避开 NavDisplay 转场，否则动画会在转场背后提前播完。 */
@Composable
internal fun StaggeredEntrance(index: Int, baseDelayMs: Long = 0, content: @Composable () -> Unit) {
    var inView by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // 屏幕高度首帧即有效；略加前瞻让条目在临进视口时就开始弹出。
    val viewportBottomPx = with(density) { configuration.screenHeightDp.dp.toPx() } + 64f
    LaunchedEffect(inView) {
        if (inView && !appeared) {
            appeared = true
        }
    }
    // 保留 State 而不是解包成值：动画只在绘制阶段读取，入场期间不会逐帧重组列表项
    // （切换筛选时同时有近十条在播，组合期读取是当时掉帧的主因）。
    val progress = animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        // Same upstream delay and curve; run the delay on Compose's animation clock as well.
        animationSpec = tween(durationMillis = 380,
            delayMillis = (baseDelayMs + if (index in 1..10) index * 110L else 0L).toInt(),
            easing = FastOutSlowInEasing),
        label = "entrance")
    Box(Modifier
        .onGloballyPositioned { coords ->
            if (!inView && coords.boundsInWindow().top < viewportBottomPx) inView = true
        }
        .graphicsLayer {
            val value = progress.value
            alpha = value
            val scale = 0.8f + 0.2f * value
            scaleX = scale
            scaleY = scale
            translationY = (1f - value) * 22.dp.toPx()
        }) { content() }
}
