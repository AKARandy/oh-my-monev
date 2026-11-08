package id.ohmymonev.android.ui

import org.junit.Assert.*
import org.junit.Test

class ScrollIndicatorTest {
    @Test fun shortOrUnmeasuredContentHasNoScrollbar() {
        assertNull(scrollbarGeometry(400f, 0, 0, 32f))
        assertNull(scrollbarGeometry(400f, Int.MAX_VALUE, 0, 32f))
        assertNull(scrollbarGeometry(0f, 800, 0, 32f))
    }
    @Test fun thumbTracksTopMiddleAndBottomProportionally() {
        assertEquals(ScrollbarGeometry(0f, 100f), scrollbarGeometry(400f, 1200, 0, 32f))
        assertEquals(ScrollbarGeometry(150f, 100f), scrollbarGeometry(400f, 1200, 600, 32f))
        assertEquals(ScrollbarGeometry(300f, 100f), scrollbarGeometry(400f, 1200, 1200, 32f))
    }
    @Test fun minimumThumbStaysInsideTheViewportAndClampsOverscroll() {
        val bottom = requireNotNull(scrollbarGeometry(400f, 100000, Int.MAX_VALUE, 32f))
        assertEquals(32f, bottom.height, 0.001f)
        assertEquals(400f, bottom.top + bottom.height, 0.001f)
        assertEquals(0f, requireNotNull(scrollbarGeometry(400f, 100000, -1, 32f)).top, 0.001f)
        assertEquals(ScrollbarGeometry(0f, 20f), scrollbarGeometry(20f, 100000, 200, 32f))
    }
}
