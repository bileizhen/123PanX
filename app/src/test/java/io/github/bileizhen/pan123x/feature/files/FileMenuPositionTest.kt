package io.github.bileizhen.pan123x.feature.files

import androidx.compose.ui.unit.*
import org.junit.Assert.assertEquals
import org.junit.Test

class FileMenuPositionTest {
    private fun position(point: IntOffset, rtl: Boolean = false, size: IntSize = IntSize(220, 440)) =
        FileMenuPositionProvider(point, 8, topInset = 32, bottomInset = 24)
            .calculatePosition(IntRect(0, 70, 400, 760), IntSize(400, 800), if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, size)

    @Test fun menuStartsBesidePressWithRoomBelow() { assertEquals(IntOffset(50, 158), position(IntOffset(50, 150))) }
    @Test fun lowerRowPlacesMenuAbovePress() { assertEquals(IntOffset(50, 252), position(IntOffset(50, 700))) }
    @Test fun rightEdgeClampsToWindow() { assertEquals(172, position(IntOffset(390, 150)).x) }
    @Test fun rtlStartsOnLeftOfPress() { assertEquals(130, position(IntOffset(350, 150), rtl = true).x) }
    @Test fun insufficientRoomUsesSafeInsets() { assertEquals(IntOffset(8, 328), position(IntOffset(1, 390))) }
    @Test fun topPressRespectsStatusBarInset() { assertEquals(40, position(IntOffset(50, 1)).y) }
}
