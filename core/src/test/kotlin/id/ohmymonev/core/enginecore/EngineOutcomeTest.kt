package id.ohmymonev.core.enginecore

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineOutcomeTest {

    @Test
    fun `outcome catalogue is complete`() {
        val expected = listOf(
            "UNKNOWN",
            "LOGIN_REQUIRED",
            "LOADING_TIMEOUT",
            "DAY_NOT_FILLABLE",
            "LOCATION_CHECK_FAILS",
            "FILL_OK",
            "READBACK_MISMATCH",
            "SUBMITTED",
            "SUBMIT_FAILED",
            "RETRYABLE_ERROR",
        )
        assertEquals(expected, EngineOutcome.entries.map { it.name })
    }

    @Test
    fun `run record keeps audit fields`() {
        val run = RunRecord(
            date = "2026-11-06",
            attemptedAtISO = "2026-11-06T21:00:00+07:00",
            outcome = EngineOutcome.FILL_OK,
            attempt = 2,
            detail = "mode uji: tidak dikirim",
        )
        assertEquals("2026-11-06", run.date)
        assertEquals("2026-11-06T21:00:00+07:00", run.attemptedAtISO)
        assertEquals(EngineOutcome.FILL_OK, run.outcome)
        assertEquals(2, run.attempt)
        assertEquals("mode uji: tidak dikirim", run.detail)
    }

    @Test
    fun `detail defaults to null`() {
        val run = RunRecord(
            date = "2026-11-06",
            attemptedAtISO = "2026-11-06T21:00:00+07:00",
            outcome = EngineOutcome.UNKNOWN,
            attempt = 1,
        )
        assertNull(run.detail)
    }

    @Test
    fun `run record serializes round trip`() {
        val run = RunRecord(
            date = "2026-11-06",
            attemptedAtISO = "2026-11-06T21:00:00+07:00",
            outcome = EngineOutcome.SUBMITTED,
            attempt = 1,
            detail = "ok",
        )
        val encoded = Json.encodeToString(RunRecord.serializer(), run)
        val round = Json.decodeFromString(RunRecord.serializer(), encoded)
        assertEquals(run, round)
    }
}
