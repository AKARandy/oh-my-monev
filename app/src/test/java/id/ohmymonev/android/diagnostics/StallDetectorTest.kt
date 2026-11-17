package id.ohmymonev.android.diagnostics

import org.junit.Assert.*
import org.junit.Test

class StallDetectorTest {
    @Test fun recordsOneStallThenRecoveryAndCanDetectAnotherStall() {
        val detector = StallDetector()
        assertNull(detector.sample(4_999))
        assertEquals("ui.stall", detector.sample(5_000))
        assertNull(detector.sample(60_000))
        assertEquals("ui.recovered", detector.sample(0))
        assertNull(detector.sample(0))
        assertEquals("ui.stall", detector.sample(5_001))
    }
}
