package com.beettechnologies.posly.inventory

/**
 * Pure classification of a stock-count variance into a probable cause. Deliberately a simple,
 * deterministic rule (sign of the delta) rather than a statistical/ML model - the on-call
 * inventory manager gets a starting hypothesis, not a verdict.
 */
object ReconciliationEngine {

    fun causeFor(delta: Int): VarianceCause = when {
        delta == 0 -> VarianceCause.NONE
        delta > 0 -> VarianceCause.OVERAGE
        else -> VarianceCause.SHORTAGE
    }

    fun describe(cause: VarianceCause): String = when (cause) {
        VarianceCause.NONE -> "No variance"
        VarianceCause.OVERAGE -> "Counted more than expected - possible miscount, unrecorded receipt, or an unrecorded return"
        VarianceCause.SHORTAGE -> "Counted less than expected - possible shrinkage, theft, damage, or miscount"
    }
}
