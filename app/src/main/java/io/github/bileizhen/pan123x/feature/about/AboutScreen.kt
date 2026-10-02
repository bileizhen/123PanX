// Migrated from LeiFetch AboutScreen.kt (XBlocker / SukiSU-Ultra v4.1.3, 0ca744a), GPL-3.0.
// Original layout, scroll fades, blur, animated credits and dialog preserved.
// 123PanX branding, links and truthful upstream credits replace LeiFetch-specific business.
package io.github.bileizhen.pan123x.feature.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bileizhen.pan123x.BuildConfig
import io.github.bileizhen.pan123x.R
import io.github.bileizhen.pan123x.ui.component.LocalPageActive
import io.github.bileizhen.pan123x.ui.component.PageBackHandler
import io.github.bileizhen.pan123x.ui.component.PanIcons
import io.github.bileizhen.pan123x.ui.component.miuix.effect.BgEffectBackground
import io.github.bileizhen.pan123x.ui.component.miuix.effect.ColorBlendToken
import io.github.bileizhen.pan123x.ui.theme.LocalDarkTheme
import io.github.bileizhen.pan123x.ui.util.BlurredBar
import io.github.bileizhen.pan123x.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.flow.onEach
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

@Immutable
private data class AboutLink(val fullText: String, val url: String, val tag: String)

@Immutable
private class AboutUiState {
    val title = "关于"
    val appName = "123PanX"
    val versionName = "v${BuildConfig.VERSION_NAME}"
    val links = listOf(
        AboutLink("GitHub", "https://github.com/bileizhen/123PanX", "about_github"),
        AboutLink("LeiFetch", "https://github.com/bileizhen/LeiFetch", "about_leifetch"),
        AboutLink("开源许可", "pan123x:licenses", "about_license"),
        AboutLink("第三方声明", "pan123x:notices", "about_notices"),
        AboutLink("隐私", "pan123x:privacy", "about_privacy"),
    )
}

@Immutable
private data class AboutScreenActions(val onBack: () -> Unit, val onOpenLink: (String) -> Unit)

/** Internal observable geometry for transition regression tests. */
internal val AboutScrollProgress = SemanticsPropertyKey<Float>("AboutScrollProgress")
internal val AboutLogoOpacity = SemanticsPropertyKey<Float>("AboutLogoOpacity")

@Composable
fun AboutScreen(onBack: () -> Unit, onLicense: () -> Unit, onNotices: () -> Unit, onPrivacy: () -> Unit, enableBlur: Boolean) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val context = LocalContext.current
    var linkError by remember { mutableStateOf<String?>(null) }
    val effectsEnabled = enableBlur && Build.VERSION.SDK_INT >= 33 && LocalView.current.isHardwareAccelerated && isRuntimeShaderSupported()
    val state = remember { AboutUiState() }
    val actions = AboutScreenActions(onBack) { link ->
        when (link) {
            "pan123x:licenses" -> onLicense()
            "pan123x:notices" -> onNotices()
            "pan123x:privacy" -> onPrivacy()
            else -> try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            catch (_: ActivityNotFoundException) { linkError = "当前设备没有可打开链接的应用" }
            catch (_: SecurityException) { linkError = "无法打开链接，请检查系统设置" }
        }
    }
    AboutScreenMiuix(state, actions, effectsEnabled)
    OverlayDialog(show = linkError != null, title = uiText("无法打开链接"), onDismissRequest = { linkError = null }) {
        Text(linkError.orEmpty())
        TextButton(uiText("关闭"), onClick = { linkError = null }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

@Composable
private fun AboutScreenMiuix(
    state: AboutUiState,
    actions: AboutScreenActions,
    enableBlur: Boolean,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }

    val scrollProgress by remember {
        derivedStateOf {
            if (logoHeightPx <= 0) {
                0f
            } else {
                val index = lazyListState.firstVisibleItemIndex
                val offset = lazyListState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    val barBlurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = barBlurBackdrop != null && scrollProgress == 1f
    val barColor = if (blurActive) {
        Color.Transparent
    } else {
        if (scrollProgress == 1f) colorScheme.surface else Color.Transparent
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = barBlurBackdrop, blurActive = blurActive) {
                SmallTopAppBar(
                    title = uiText(state.title),
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                    titleColor = colorScheme.onSurface.copy(
                        alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                    ),
                    defaultWindowInsetsPadding = true,
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.size(48.dp).testTag("navigate_back"), onClick = actions.onBack
                        ) {
                            Icon(
                                imageVector = PanIcons.Back,
                                contentDescription = uiText("返回"),
                                tint = colorScheme.onBackground
                            )
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (barBlurBackdrop != null) Modifier.layerBackdrop(barBlurBackdrop) else Modifier) {
            AboutContent(
                state = state,
                actions = actions,
                innerPadding = innerPadding,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                lazyListState = lazyListState,
                scrollProgress = scrollProgress,
                onLogoHeightChanged = { logoHeightPx = it },
                enableBlur = enableBlur,
            )
        }
    }
}

@Composable
private fun AboutContent(
    state: AboutUiState,
    actions: AboutScreenActions,
    enableBlur: Boolean,
    innerPadding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgress: Float,
    onLogoHeightChanged: (Int) -> Unit,
) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val backdrop = rememberLayerBackdrop()
    val blurEnabled = enableBlur && Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported()

    val isInDark = LocalDarkTheme.current
    val active = LocalPageActive.current
    val logoColor = colorScheme.onBackground
    // Use the original foreground pixels, including its shaded X, as the mask.
    // The blue tile has G = 128/255; the white/cyan wordmark is brighter.
    // alpha = clamp(2.1G + A - 2.1) clears the tile, retaining letter edges.
    val logoMask = remember(logoColor) {
        ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, logoColor.red * 255f,
            0f, 0f, 0f, 0f, logoColor.green * 255f,
            0f, 0f, 0f, 0f, logoColor.blue * 255f,
            0f, 2.1f, 0f, 1f, -535.5f,
        )))
    }
    val effectBackground =
        remember(enableBlur) { isRuntimeShaderSupported() && enableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM }

    val blendColors = remember(isInDark) {
        if (isInDark) ColorBlendToken.Overlay_Thin_Light
        else ColorBlendToken.Pured_Regular_Light
    }
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    // Logo parallax/fade tracking
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    var logoAreaY by remember { mutableFloatStateOf(0f) }
    var iconY by remember { mutableFloatStateOf(0f) }
    var projectNameY by remember { mutableFloatStateOf(0f) }
    var versionCodeY by remember { mutableFloatStateOf(0f) }

    var iconProgress by remember { mutableFloatStateOf(0f) }
    var projectNameProgress by remember { mutableFloatStateOf(0f) }
    var versionCodeProgress by remember { mutableFloatStateOf(0f) }
    var initialLogoAreaY by remember { mutableFloatStateOf(0f) }

    // 点开的名单成员（null 表示弹窗关闭）；shownFocus 保留最后一次选择，供退场动画期间渲染。
    var selected by remember { mutableStateOf<CreditFocus?>(null) }
    val shownFocus = remember { mutableStateOf<CreditFocus?>(null) }
    LaunchedEffect(selected) { selected?.let { shownFocus.value = it } }
    PageBackHandler(selected != null) { selected = null }

    LaunchedEffect(lazyListState) {
        // A jump to another item can keep offset == 0; observe the item index too.
        snapshotFlow { lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset }
            .onEach { (index, offset) ->
                if (index > 0) {
                    if (iconProgress != 1f) iconProgress = 1f
                    if (projectNameProgress != 1f) projectNameProgress = 1f
                    if (versionCodeProgress != 1f) versionCodeProgress = 1f
                    return@onEach
                }

                if (initialLogoAreaY == 0f && logoAreaY > 0f) {
                    initialLogoAreaY = logoAreaY
                }
                val refLogoAreaY = if (initialLogoAreaY > 0f) initialLogoAreaY else logoAreaY

                val stage1TotalLength = refLogoAreaY - versionCodeY
                val stage2TotalLength = versionCodeY - projectNameY
                val stage3TotalLength = projectNameY - iconY

                val versionCodeDelay = stage1TotalLength * 0.5f
                versionCodeProgress = ((offset.toFloat() - versionCodeDelay) / (stage1TotalLength - versionCodeDelay).coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                projectNameProgress = ((offset.toFloat() - stage1TotalLength) / stage2TotalLength.coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                iconProgress = ((offset.toFloat() - stage1TotalLength - stage2TotalLength) / stage3TotalLength.coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
            }
            .collect { }
    }

    val scrollPadding = PaddingValues(
        top = innerPadding.calculateTopPadding(),
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )
    val logoPadding = PaddingValues(
        top = innerPadding.calculateTopPadding() + 40.dp,
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )

    BgEffectBackground(
        dynamicBackground = effectBackground && active && scrollProgress < 1f,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (blurEnabled) Modifier.layerBackdrop(backdrop) else Modifier,
        isFullSize = true,
        effectBackground = effectBackground,
        alpha = { 1f - scrollProgress },
    ) {
        // Logo area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateStartPadding(layoutDirection),
                    end = logoPadding.calculateEndPadding(layoutDirection),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .testTag("about_logo")
                    .semantics { this[AboutLogoOpacity] = 1f - iconProgress }
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (iconY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        iconY = y + size.height
                    },
            ) {
                // LeiFetch masks the sampled, mixed backdrop with a transparent logo.
                // A launcher background would fill the mask and erase the brand detail.
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    modifier = Modifier.requiredSize(180.dp).then(
                        if (blurEnabled) Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(0.dp),
                            blurRadius = 150f,
                            colors = BlurColors(blendColors = logoBlend),
                            contentBlendMode = ComposeBlendMode.DstIn,
                            enabled = true,
                        ) else Modifier,
                    ),
                    colorFilter = logoMask,
                    contentDescription = "123PanX 图标",
                )
            }
            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .onGloballyPositioned { coordinates ->
                        if (projectNameY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        projectNameY = y + size.height
                    }
                    .graphicsLayer {
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (blurEnabled) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(0.dp),
                                blurRadius = 150f,
                                colors = BlurColors(blendColors = logoBlend),
                                contentBlendMode = ComposeBlendMode.DstIn,
                                enabled = true,
                            )
                        } else Modifier
                    ),
                text = state.appName,
                color = colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (versionCodeY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        versionCodeY = y + size.height
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = state.versionName,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        // Scrollable content
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("about_screen")
                .semantics { this[AboutScrollProgress] = scrollProgress }
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateStartPadding(layoutDirection),
                end = scrollPadding.calculateEndPadding(layoutDirection),
            ),
            overscrollEffect = null,
        ) {
            // Transparent spacer matching logo height
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        )
                        .onSizeChanged { size ->
                            onLogoHeightChanged(size.height)
                        }
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            logoAreaY = y + size.height
                        },
                    contentAlignment = Alignment.TopCenter,
                    content = { },
                )
            }

            // 链接卡与名单同页连续排布；名单每条按 ReactBits AnimatedList 的节奏弹入。
            val memberCard: @Composable (AboutCredit, String) -> Unit = { member, group ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .then(
                            if (blurEnabled) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 60f,
                                    colors = BlurColors(blendColors = blendColors),
                                    enabled = true,
                                )
                            } else Modifier
                        ),
                    colors = CardDefaults.defaultColors(
                        if (blurEnabled) Color.Transparent else colorScheme.surfaceContainer,
                        Color.Transparent,
                    ),
                ) {
                    CreditRow(member, onClick = { selected = CreditFocus(member, group) })
                }
            }
            item(key = "about") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = innerPadding.calculateBottomPadding() + 12.dp),
                ) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .then(
                                if (blurEnabled) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        colors = BlurColors(blendColors = blendColors),
                                        enabled = true,
                                    )
                                } else Modifier
                            ),
                        colors = CardDefaults.defaultColors(
                            if (blurEnabled) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        state.links.forEach {
                            ArrowPreference(
                                modifier = Modifier.testTag(it.tag), title = uiText(it.fullText),
                                onClick = {
                                    actions.onOpenLink(it.url)
                                }
                            )
                        }
                    }
                    aboutCreditGroups.forEachIndexed { sectionIndex, section ->
                        // 首个分组的标题正好落在首屏下沿、只露出半截；多留一段空白把它整体压到屏幕外。
                        Spacer(Modifier.height(if (sectionIndex == 0) 40.dp else 18.dp))
                        SmallTitle(section.title, insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp))
                        section.members.forEachIndexed { index, member ->
                            AnimatedListItem(listState = lazyListState, hostKey = "about") {
                                Column {
                                    memberCard(member, section.title)
                                    if (index < section.members.lastIndex) Spacer(Modifier.height(10.dp))
                                }
                            }
                        }
                    }
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                        )
                    )
                }
            }
        }

        CreditDetailDialog(show = selected != null, focus = shownFocus.value, onDismiss = { selected = null })
    }
}


@Composable
private fun AnimatedListItem(
    listState: LazyListState,
    hostKey: String,
    content: @Composable () -> Unit,
) {
    var offsetInHost by remember { mutableStateOf(0f) }
    var heightPx by remember { mutableStateOf(0f) }

    val inView by remember(listState, hostKey) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val host = layout.visibleItemsInfo.firstOrNull { it.key == hostKey }
            if (host == null || heightPx <= 0f) {
                false
            } else {
                val top = host.offset + offsetInHost
                val visible = (top + heightPx).coerceAtMost(layout.viewportEndOffset.toFloat()) -
                        top.coerceAtLeast(layout.viewportStartOffset.toFloat())
                visible >= heightPx * 0.5f
            }
        }
    }

    Box(
        Modifier.onGloballyPositioned { coordinates ->
            offsetInHost = coordinates.positionInParent().y
            heightPx = coordinates.size.height.toFloat()
        }
    ) {
        val progress by animateFloatAsState(
            targetValue = if (inView) 1f else 0f,
            animationSpec = tween(
                durationMillis = 200,
                delayMillis = 100,
                easing = FastOutSlowInEasing,
            ),
            label = "animatedListItem",
        )
        Box(
            Modifier.graphicsLayer {
                alpha = progress
                val scale = 0.7f + 0.3f * progress
                scaleX = scale
                scaleY = scale
            }
        ) { content() }
    }
}

/** 成员详情弹窗：头像、昵称、所属分组与分工；有 detail 的成员再补一段贡献说明。
 *  show 与 focus 分开传：关闭后 focus 仍保留最后一次选择，供退场动画期间继续渲染内容。 */
@Composable
private fun CreditDetailDialog(show: Boolean, focus: CreditFocus?, onDismiss: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    OverlayDialog(show = show, title = uiText("项目与致谢"), onDismissRequest = onDismiss) {
        focus?.let { current ->
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CreditAvatar(current.member.name, size = 76.dp)
                Text(current.member.name, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp))
                Text(current.group, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp))
                HorizontalDivider(Modifier.padding(vertical = 16.dp),
                    color = colorScheme.onSurface.copy(alpha = 0.08f))
                MemberInfoRow("分工", current.member.role)
            }
            current.member.detail.let { detail ->
                // 详情可能有多行（作者的贡献说明是分条的），限高后可滚动，不挤走下面的关闭按钮。
                Text(detail, fontSize = 13.sp, lineHeight = 21.sp,
                    color = colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp))
            }
            TextButton(uiText("关闭"), onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        }
    }
}

@Composable
private fun MemberInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(48.dp))
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}
