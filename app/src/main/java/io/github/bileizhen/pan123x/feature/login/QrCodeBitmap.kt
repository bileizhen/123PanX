package io.github.bileizhen.pan123x.feature.login

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 二维码位图工具（M7）。
 *
 * 编码与渲染刻意分离：[encode] 只产出 zxing [BitMatrix]（纯 JVM，单测直接断言矩阵尺寸与
 * 定位点），[renderBitmap] 才触碰 android.graphics（真机路径，单测不加载该分支）。
 * zxing core 是纯 Java 库，编码在 JVM 单测可用；白底黑码、quiet zone 由 MARGIN 提示控制。
 */
object QrCodeBitmap {

    /** 登录页二维码渲染边长（px）；矩阵等比缩放到该尺寸，四周自带 quiet zone。 */
    const val DEFAULT_SIZE_PX = 420

    /** quiet zone 模块数（zxing QR 规范建议 4， 冻结值）。 */
    const val QUIET_ZONE_MODULES = 4

    /**
     * 把内容编码为最小尺寸的 QR 矩阵：宽高传 0 时 zxing 取"模块数 + 2×quietZone"的 1:1 矩阵
     * （QRCodeWriter.renderResult 的 `outputWidth = max(width, qrWidth)` 分支），
     * 矩阵已含 quiet zone，尺寸 = 21+4k 模块 + 8。空内容按 zxing 契约抛 IllegalArgumentException。
     */
    fun encode(content: String, quietZoneModules: Int = QUIET_ZONE_MODULES): BitMatrix =
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to quietZoneModules,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * 渲染为白底黑码的方形位图：矩阵（含 quiet zone）等比缩放到 [sizePx]。
     * 模块矩形取 floor/ceil 对齐避免缩放留缝；不用抗锯齿（抗锯齿灰边会降低扫码容错）。
     * 仅 Android 运行时调用，JVM 单测只走 [encode]。
     */
    fun renderBitmap(matrix: BitMatrix, sizePx: Int = DEFAULT_SIZE_PX): Bitmap {
        require(sizePx > 0) { "二维码边长必须为正" }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val scale = sizePx.toFloat() / maxOf(matrix.width, matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix.get(x, y)) continue
                canvas.drawRect(
                    floor(x * scale),
                    floor(y * scale),
                    ceil((x + 1) * scale),
                    ceil((y + 1) * scale),
                    paint,
                )
            }
        }
        return bitmap
    }
}
