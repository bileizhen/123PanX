// Form pattern adapted from LeiFetch ProxyScreen (GPL-3.0): Miuix TextField +
// PasswordVisualTransformation + validity-gated submit button. Brand and flow rewritten for 123PanX.
// M7: 双 Tab「账号密码 / 扫码」；二维码经 zxing core 编码 + QrCodeBitmap 渲染。
package io.github.bileizhen.pan123x.feature.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bileizhen.pan123x.ui.component.WorkspaceTabs
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 登录页：双 Tab「账号密码 / 扫码」。只出内容，标题与返回由
 * [ScreenFrame][io.github.bileizhen.pan123x.ui.component.ScreenFrame] 提供。
 * 登录成功由 [LoginUiState.loggedIn] 驱动 [onSuccess] 返回上一页；错误只显示仓库映射后的可读文案。
 */
@Composable
fun LoginScreen(viewModel: LoginViewModel, onSuccess: () -> Unit) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.loggedIn) { if (state.loggedIn) onSuccess() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(uiText("登录 123 云盘"), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(
                uiText("使用账号 / 手机号登录，或用 123云盘 App 扫码。凭据仅加密保存在本机。"),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        WorkspaceTabs(listOf("账号密码", "扫码登录"), state.selectedTab, viewModel::selectTab, listOf("login_tab_password", "login_tab_qr"))
        if (state.selectedTab == LOGIN_TAB_PASSWORD) {
            PasswordForm(state, viewModel)
            Text(
                uiText("若账号触发风控验证，请先在官方客户端完成验证后再登录。"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            QrPanel(state, viewModel)
            Text(
                uiText("使用 123云盘 App「扫一扫」确认登录；微信扫码暂不支持。"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 密码登录表单：与 M1 版本逐字段一致（testTag login_passport / login_password / login_error / login_submit）。 */
@Composable
private fun PasswordForm(state: LoginUiState, viewModel: LoginViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    val keyboard = LocalSoftwareKeyboardController.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TextField(
                value = state.passport,
                onValueChange = viewModel::onPassportChange,
                label = uiText("账号 / 手机号 / 邮箱"),
                singleLine = true,
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_passport"),
            )
            TextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = uiText("密码"),
                singleLine = true,
                enabled = !state.submitting,
                visualTransformation = if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); viewModel.submit() }),
                trailingIcon = { PasswordEyeToggle(state.showPassword, viewModel::onTogglePassword) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_password"),
            )
            state.error?.let { message ->
                Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.error, modifier = Modifier.testTag("login_error"))
            }
            TextButton(
                if (state.submitting) "登录中…" else "登录",
                enabled = !state.submitting && state.passport.isNotBlank() && state.password.isNotEmpty(),
                onClick = viewModel::submit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login_submit"),
            )
        }
    }
}

/**
 * 扫码登录面板：二维码位图 + 状态文案（qr_status）+ 终态「刷新二维码」（qr_refresh）。
 * 位图只在拿到 url 后生成并随 url 记忆；REJECTED / EXPIRED / FAILED 保留旧码便于对照，
 * 以刷新按钮重新生成（状态机）。
 */
@Composable
private fun QrPanel(state: LoginUiState, viewModel: LoginViewModel) {
    val uiText = io.github.bileizhen.pan123x.ui.util.rememberUiTranslator()
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.qrUrl?.let { url ->
                val bitmap = remember(url) { QrCodeBitmap.renderBitmap(QrCodeBitmap.encode(url)) }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = uiText("登录二维码"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp).testTag("qr_image"),
                )
            }
            Text(
                qrStatusText(state),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.testTag("qr_status"),
            )
            if (state.qrPhase == QrPhase.REJECTED || state.qrPhase == QrPhase.EXPIRED || state.qrPhase == QrPhase.FAILED) {
                TextButton(
                    uiText("刷新二维码"),
                    onClick = viewModel::refreshQr,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("qr_refresh"),
                )
            }
        }
    }
}

/** 扫码状态 → 用户可读文案（不透出状态码，微信文案来自接口约定）。 */
private fun qrStatusText(state: LoginUiState): String = when (state.qrPhase) {
    QrPhase.IDLE -> "正在进入扫码登录…"
    QrPhase.GENERATING -> "正在生成二维码…"
    QrPhase.READY -> if (state.qrScanned) "已扫码，请在手机上确认" else "等待扫码，请使用 123云盘 App"
    QrPhase.VERIFYING -> "正在确认登录…"
    QrPhase.SUCCESS -> "登录成功"
    QrPhase.REJECTED -> "已拒绝本次登录，可刷新二维码重试"
    QrPhase.EXPIRED -> "二维码已过期，请刷新"
    QrPhase.FAILED -> state.qrMessage ?: "登录失败，请刷新二维码重试"
}

/**
 * 密码明文切换：material-icons-core 不含 Visibility 图标，这里用 Canvas 画一枚极简眼睛，
 * 隐藏态叠加斜线表示掩码，不为此引入整个 extended 图标库。
 */
@Composable
private fun PasswordEyeToggle(visible: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = if (visible) "隐藏密码" else "显示密码" },
        contentAlignment = Alignment.Center,
    ) {
        val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        Canvas(Modifier.size(22.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawOval(
                color = color,
                topLeft = Offset(size.width * 0.06f, size.height * 0.26f),
                size = Size(size.width * 0.88f, size.height * 0.48f),
                style = stroke,
            )
            if (visible) {
                drawCircle(color = color, radius = size.minDimension * 0.13f, center = center)
            } else {
                drawCircle(color = color, radius = size.minDimension * 0.13f, center = center, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.12f, size.height * 0.86f),
                    end = Offset(size.width * 0.88f, size.height * 0.14f),
                    strokeWidth = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
