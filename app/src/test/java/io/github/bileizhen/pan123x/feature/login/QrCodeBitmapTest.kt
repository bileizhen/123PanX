package io.github.bileizhen.pan123x.feature.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QrCodeBitmap 编码矩阵测试（M7）：只测 JVM 纯逻辑（zxing BitMatrix），
 * 不触碰 [QrCodeBitmap.renderBitmap] 的 android.graphics 分支（不依赖 Android 运行时）。
 * 断言覆盖矩阵尺寸契约与三个定位图形（finder pattern）的黑白结构。
 */
class QrCodeBitmapTest {

    @Test
    fun encodeProducesSquareMatrixWithQuietZoneAndValidQrSize() {
        val matrix = QrCodeBitmap.encode("https://login.123pan.com/qr?token=abc")

        assertEquals(matrix.width, matrix.height)
        // 有效 QR 尺寸：21 + 4k 数据模块（版本 1..40），矩阵 = 数据模块 + 2×quietZone
        val modules = matrix.width - 2 * QrCodeBitmap.QUIET_ZONE_MODULES
        assertTrue("非法 QR 模块数：$modules", modules in 21..177 && (modules - 21) % 4 == 0)
    }

    @Test
    fun encodeSurroundsContentWithWhiteQuietZone() {
        val matrix = QrCodeBitmap.encode("https://login.123pan.com/qr?token=abc")
        val last = matrix.width - 1

        // quiet zone（默认 4 模块）四边全白
        for (i in 0 until matrix.width) {
            assertFalse("上边 $i 非 white", matrix.get(i, 0))
            assertFalse("左边 $i 非 white", matrix.get(0, i))
            assertFalse("下边 $i 非 white", matrix.get(i, last))
            assertFalse("右边 $i 非 white", matrix.get(last, i))
        }
    }

    @Test
    fun encodeRendersFinderPatternAtTopLeftOnly() {
        val matrix = QrCodeBitmap.encode("https://login.123pan.com/qr?token=abc")
        val q = QrCodeBitmap.QUIET_ZONE_MODULES

        // 左上定位图形：7×7 外框全黑（矩阵内偏移 0 与 6）
        assertTrue(matrix.get(q, q))
        assertTrue(matrix.get(q + 6, q))
        assertTrue(matrix.get(q, q + 6))
        assertTrue(matrix.get(q + 6, q + 6))
        // 内环白（偏移 1）、中心黑（偏移 3）
        assertFalse(matrix.get(q + 1, q + 1))
        assertTrue(matrix.get(q + 3, q + 3))
        // 右下角无定位图形（QR 只在左上 / 右上 / 左下放 finder）
        assertFalse(matrix.get(matrix.width - q - 1, matrix.height - q - 1))
    }

    @Test
    fun quietZoneModulesAreParameterizable() {
        val wide = QrCodeBitmap.encode("1234567890")
        val tight = QrCodeBitmap.encode("1234567890", quietZoneModules = 2)

        // 同一内容数据模块数不变：quietZone 差 2 → 每边差 2，总宽差 4
        assertEquals(wide.width - 2 * (QrCodeBitmap.QUIET_ZONE_MODULES - 2), tight.width)
    }

    @Test
    fun encodeRejectsEmptyContent() {
        // zxing QRCodeWriter 契约：空内容抛 IllegalArgumentException
        val error = runCatching { QrCodeBitmap.encode("") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
