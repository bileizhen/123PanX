package io.github.bileizhen.pan123x.feature.login

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Decode the Android-rendered pixels, including non-integral module scales. No live session. */
@RunWith(AndroidJUnit4::class)
class QrCodeBitmapInstrumentedTest {
    @Test fun renderedLoginQrRetainsAllSessionAndRoutingParameters() {
        val content = "https://yun.123pan.cn/wx-app-login.html?env=production&uniID=fixture-session&source=123pan&type=login"
        val matrix = QrCodeBitmap.encode(content)
        for (size in listOf(210, QrCodeBitmap.DEFAULT_SIZE_PX, 840)) {
            val bitmap = QrCodeBitmap.renderBitmap(matrix, size)
            try {
                val pixels = IntArray(size * size)
                bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
                val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
                assertEquals("Rendered size $size", content, decoded.text)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
