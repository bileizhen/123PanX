package io.github.bileizhen.pan123x.feature.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import io.github.bileizhen.pan123x.ui.component.PanIcons
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.CardDefaults
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bileizhen.pan123x.core.database.CloudFileEntity
import io.github.bileizhen.pan123x.ui.util.FileKind
import io.github.bileizhen.pan123x.ui.util.fileKindOf
import io.github.bileizhen.pan123x.ui.util.formatBytes
import io.github.bileizhen.pan123x.ui.util.formatDateTime
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文件 / 文件夹条目：单击打开；多选中单击切换选中态；长按在按压处打开快捷菜单。
 * 前置复选圆点仅多选时出现，48dp 触控由整卡可点区域保证。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileTile(
    file: CloudFileEntity,
    grid: Boolean,
    selectMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onContextMenu: (IntOffset) -> Unit,
    onToggle: () -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val kind = fileKindOf(file.isFolder, file.fileName)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var pressPoint by remember { mutableStateOf<Offset?>(null) }
    val colors = MiuixTheme.colorScheme
    val container by animateColorAsState(if (selected && selectMode) colors.primary.copy(alpha = .12f).compositeOver(colors.surfaceContainer) else colors.surfaceContainer,
        animationSpec = tween(200), label = "file-selection-color")
    val date = formatDateTime(if (file.updateAt > 0) file.updateAt else file.createAt)
    val metadata = if (file.isFolder) {
        if (date.isBlank()) uiText("文件夹") else "${uiText("文件夹")} · $date"
    } else {
        listOfNotNull(formatBytes(file.size), date.ifBlank { null }).joinToString(" · ")
    }
    Card(
        modifier
            .fillMaxWidth()
            .testTag("file_item_${file.fileId}")
            .onGloballyPositioned { coordinates = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { pressPoint = it.position }
                    }
                }
            }
            .combinedClickable(
                onClick = { if (selectMode) onToggle() else onOpen() },
                onLongClickLabel = uiText(if (selectMode) "选择文件" else "打开快捷菜单"),
                onLongClick = {
                    if (selectMode) onToggle() else {
                    val layout = coordinates
                    if (layout != null && layout.isAttached) {
                        val position = layout.localToWindow(pressPoint ?: Offset(layout.size.width / 2f, layout.size.height / 2f))
                        onContextMenu(IntOffset(position.x.roundToInt(), position.y.roundToInt()))
                    }
                    }
                },
            ),
        cornerRadius = 22.dp,
        colors = CardDefaults.defaultColors(container),
        insideMargin = PaddingValues(0.dp),
    ) {
        if (grid) {
            Box {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 128.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FileSymbol(kind)
                    Text(file.fileName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(metadata, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                androidx.compose.animation.AnimatedVisibility(selectMode, modifier = Modifier.align(Alignment.TopEnd), enter = fadeIn(tween(120)) + scaleIn(spring(stiffness = 500f)), exit = fadeOut(tween(100)) + scaleOut(tween(150))) {
                    SelectionDot(
                        selected = selected,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimatedVisibility(selectMode, enter = fadeIn(tween(120)) + expandHorizontally(spring(stiffness = 500f)), exit = fadeOut(tween(100)) + shrinkHorizontally(tween(180))) { SelectionDot(selected = selected) }
                FileSymbol(kind)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(file.fileName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(metadata, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (file.isFolder && !selectMode) Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 22.sp)
            }
        }
    }
}

/** 复选圆点：选中为主题色填充 + 对勾，未选中为描边空心。 */
@Composable
private fun SelectionDot(selected: Boolean, modifier: Modifier = Modifier) {
    val shape = CircleShape
    val fill by animateColorAsState(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent, tween(180), label = "selection-dot")
    Box(
        modifier
            .size(24.dp)
            .clip(shape)
            .background(fill, shape).border(2.dp, if (selected) fill else MiuixTheme.colorScheme.onSurfaceVariantSummary, shape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(selected, enter = fadeIn(tween(100)) + scaleIn(tween(160)), exit = fadeOut(tween(90)) + scaleOut(tween(120))) {
            Text("✓", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
internal fun FileSymbol(kind: FileKind) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .09f)),
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            FileKind.PDF, FileKind.TEXT -> Text(if (kind == FileKind.PDF) "PDF" else "TXT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            else -> Icon(when (kind) { FileKind.FOLDER -> PanIcons.Folder; FileKind.IMAGE -> PanIcons.Image; FileKind.VIDEO -> PanIcons.Play; else -> PanIcons.File },
                uiText(kind.label), tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    }
}
