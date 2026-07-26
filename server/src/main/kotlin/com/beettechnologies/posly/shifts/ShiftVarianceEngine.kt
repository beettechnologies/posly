package com.beettechnologies.posly.shifts

import kotlin.math.abs

/**
 * Pure classification of a shift's cash variance (closing count vs. expected cash), plus a
 * starting list of possible causes for the cashier/manager to investigate - the same
 * "hypothesis, not verdict" spirit as ticket 28's ReconciliationEngine.
 */
object ShiftVarianceEngine {

    /** Below this absolute dollar variance, a shift closes normally with no override/note required. */
    const val DEFAULT_THRESHOLD = 5.0

    fun causeFor(variance: Double): ShiftVarianceCause = when {
        abs(variance) < 0.005 -> ShiftVarianceCause.NONE
        variance < 0 -> ShiftVarianceCause.SHORT
        else -> ShiftVarianceCause.OVER
    }

    fun possibleReasons(cause: ShiftVarianceCause): List<String> = when (cause) {
        ShiftVarianceCause.NONE -> emptyList()
        ShiftVarianceCause.SHORT -> listOf(
            "Cash under-counted at close",
            "An unrecorded cash payout or refund",
            "Till shortage - miscount or missing cash during the shift"
        )
        ShiftVarianceCause.OVER -> listOf(
            "Cash over-counted at close",
            "A cash sale recorded under the wrong tender",
            "Change miscalculated for a customer"
        )
    }
}
