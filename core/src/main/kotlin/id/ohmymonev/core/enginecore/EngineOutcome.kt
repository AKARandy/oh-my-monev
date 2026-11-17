package id.ohmymonev.core.enginecore

import kotlinx.serialization.Serializable

/** Terminal states of one engine run. Order matters only for documentation; match by name. */
enum class EngineOutcome {
    UNKNOWN,
    LOGIN_REQUIRED,
    LOADING_TIMEOUT,
    DAY_NOT_FILLABLE,
    LOCATION_CHECK_FAILS,
    FILL_OK,
    READBACK_MISMATCH,
    SUBMITTED,
    SUBMIT_FAILED,
    RETRYABLE_ERROR,
}

/** One audited automation attempt. At least one RunRecord exists per scheduled run. */
@Serializable
data class RunRecord(
    /** Report day the run targeted (ISO yyyy-MM-dd). */
    val date: String,
    /** Absolute instant the engine attempt started (ISO-8601 with offset). */
    val attemptedAtISO: String,
    val outcome: EngineOutcome,
    /** 1-based retry index within the same date (ladder +5/+20/+60 min per docs/plan.md D4). */
    val attempt: Int,
    val detail: String? = null,
    val trigger: String? = null,
    val contentSource: String? = null,
    val sourceDate: String? = null,
    val reusedPreview: Boolean? = null,
    val locationSource: String? = null,
    val fallbackReason: String? = null,
    val lastStage: String? = null,
    val verification: String? = null,
)
