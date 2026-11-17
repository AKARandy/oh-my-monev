package id.ohmymonev.android.engine

import id.ohmymonev.core.enginecore.TodayState
import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionVerificationTest {
    private val status = SiteStatus(true, TodayState.FILLED, null, emptyList(), account = "participant")

    @Test fun onlyMatchingConfirmedStatusCanReportSuccess() {
        assertEquals("CONFIRMED", submissionVerification(status, "participant"))
        assertEquals("UNCONFIRMED", submissionVerification(status, "other"))
        assertEquals("UNCONFIRMED", submissionVerification(status, null))
        assertEquals("UNCONFIRMED", submissionVerification(status.copy(sessionActive = false), "participant"))
    }

    @Test fun anUnavailableApiDoesNotClaimTheReportWasNotRecorded() {
        assertEquals("UNCONFIRMED", submissionVerification(null, "participant"))
        assertEquals("UNCONFIRMED", submissionVerification(status.copy(today = TodayState.UNKNOWN), "participant"))
    }

    @Test fun matchingUnfilledStatusCanReportNotRecorded() {
        assertEquals("FAILED", submissionVerification(status.copy(today = TodayState.UNFILLED), "participant"))
    }
}
