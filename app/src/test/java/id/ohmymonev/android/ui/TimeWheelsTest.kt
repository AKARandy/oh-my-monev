package id.ohmymonev.android.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class TimeWheelsTest {
    @Test fun validPartsUse24HourTime() {
        assertEquals(LocalTime.MIDNIGHT, parseTimeParts("00", "00"))
        assertEquals(LocalTime.of(23,59), parseTimeParts("23", "59"))
        assertEquals(LocalTime.of(5,7), parseTimeParts("5", "7"))
    }
    @Test fun invalidOrIncompletePartsCannotBeSaved() {
        for ((h,m) in listOf("" to "00", "12" to "", "24" to "00", "23" to "60", "-1" to "00", "aa" to "00", "123" to "00"))
            assertNull(parseTimeParts(h,m))
    }
    @Test fun changingOnePartPreservesTheOther() {
        assertEquals(LocalTime.of(5,45), parseTimeParts("05", "45"))
        assertEquals(LocalTime.of(20,7), parseTimeParts("20", "07"))
    }
}
