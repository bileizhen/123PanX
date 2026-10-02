// SPDX-License-Identifier: GPL-3.0-only
// Miuix card/menu language follows LeiFetch TransferWorkspace; file actions and touch anchoring are PanX-specific.
package io.github.bileizhen.pan123x.feature.files

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class FileMenuTarget(val file: CloudFileEntity, val point: IntOffset)
internal val FileMenuAnchorPoint = SemanticsPropertyKey<IntOffset>("FileMenuAnchorPoint")

internal enum class FileQuickAction(val title: String, val icon: ImageVector) {
    DOWNLOAD("下载", PanIcons.Download), SHARE("分享", PanIcons.Share),
    RENAME("重命名", PanIcons.Rename), MOVE("移动", PanIcons.Folder),
    COPY("复制", PanIcons.Copy), RAPID("秒传", PanIcons.Transfer),
    DETAILS("详情", PanIcons.Info), SELECT("多选", PanIcons.List), DELETE("删除", PanIcons.Trash),
}

/** Window coordinates are used so the toolbar, side rail and scrolled rows do not shift the anchor. */
internal class FileMenuPositionProvider(
    private val point: IntOffset,
    private val margin: Int,
    private val topInset: Int = 0,
    private val bottomInset: Int = 0,
    private val onPosition: (IntOffset, IntSize) -> Unit = { _, _ -> },
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (if (layoutDirection == LayoutDirection.Rtl) point.x - popupContentSize.width else point.x)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val top = topInset + margin
        val bottom = windowSize.height - bottomInset - margin
        val below = (point.y + margin).coerceAtLeast(top)
        val above = point.y - popupContentSize.height - margin
        val y = when {
            below + popupContentSize.height <= bottom -> below
            above >= top -> above
            else -> below.coerceIn(top, (bottom - popupContentSize.height).coerceAtLeast(top))
        }
        return IntOffset(x, y).also { onPosition(it, popupContentSize) }
    }
}

@Composable
internal fun FileContextMenu(target: FileMenuTarget, busy: Boolean, onClosed: () -> Unit, onAction: (FileQuickAction) -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val top = WindowInsets.safeDrawing.getTop(density)
    val bottom = WindowInsets.safeDrawing.getBottom(density)
    val margin = with(density) { 8.dp.roundToPx() }
    val maxHeight = with(density) { (window.height - top - bottom - margin * 4).coerceAtLeast(1).toDp() }
    val visible = remember(target) { MutableTransitionState(false).apply { targetState = true } }
    var pending by remember(target) { mutableStateOf<FileQuickAction?>(null) }
    var origin by remember(target) { mutableStateOf(TransformOrigin(0f, 0f)) }
    val closed by rememberUpdatedState(onClosed)
    val action by rememberUpdatedState(onAction)
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) {
            closed()
            pending?.let(action)
        }
    }
    val provider = remember(target.point, margin, top, bottom) {
        FileMenuPositionProvider(target.point, margin, top, bottom) { position, size ->
            origin = TransformOrigin(
                ((target.point.x - position.x).toFloat() / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                ((target.point.y - position.y).toFloat() / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
            )
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = { visible.targetState = false }, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(visibleState = visible, modifier = Modifier.padding(8.dp),
            enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = .85f, stiffness = 600f), initialScale = .9f, transformOrigin = origin),
            exit = fadeOut(tween(110)) + scaleOut(tween(140), targetScale = .96f, transformOrigin = origin)) {
            val colors = MiuixTheme.colorScheme
            val shape = RoundedCornerShape(22.dp)
            val maxWidth = with(density) { (window.width - margin * 4).coerceAtLeast(1).toDp() }
            Column(Modifier.width(minOf(224.dp, maxWidth)).heightIn(max = maxHeight).testTag("file_context_menu")
                .semantics { this[FileMenuAnchorPoint] = target.point }
                .shadow(8.dp, shape).clip(shape).background(colors.surfaceContainer).border(.5.dp, colors.onSurface.copy(alpha = .08f), shape)
                .verticalScroll(rememberScrollState()).padding(6.dp)) {
                Text(target.file.fileName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp))
                FileQuickAction.entries.chunked(2).forEach { entries ->
                  Row(Modifier.fillMaxWidth()) {
                   entries.forEach { entry ->
                    val enabled = !busy || entry == FileQuickAction.DETAILS
                    val tint = if (entry == FileQuickAction.DELETE) colors.error else colors.onSurface
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled && visible.targetState) { pending = entry; visible.targetState = false }
                        .testTag("file_context_${entry.name.lowercase()}").padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(entry.icon, null, tint = tint.copy(alpha = if (enabled) 1f else .4f), modifier = Modifier.size(20.dp))
                        Text(uiText(entry.title), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = tint.copy(alpha = if (enabled) 1f else .4f))
                    }
                   }
                  }
                }
            }
        }
    }
}
