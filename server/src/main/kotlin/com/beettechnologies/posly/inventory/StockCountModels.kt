package com.beettechnologies.posly.inventory

import java.util.UUID

/** One physical count submitted for a single product during a stock-take. */
data class StockCountLineInput(val productId: String, val countedQuantity: Int)

enum class VarianceCause { NONE, OVERAGE, SHORTAGE }

/**
 * The reconciled result for one product on a [StockCount]: the system's expected on-hand
 * quantity at the time of the count vs. what was physically counted, the resulting delta, and
 * (when non-zero) the id of the [InventoryTransaction] that was posted to bring on-hand stock in
 * line with the physical count - [suggestedAdjustment] and the applied delta are the same number,
 * since a stock count reconciles immediately rather than needing a separate approval step.
 */
data class StockCountVariance(
    val productId: String,
    val expectedQuantity: Int,
    val countedQuantity: Int,
    val delta: Int,
    val cause: VarianceCause,
    val suggestedAdjustment: Int,
    val adjustmentTransactionId: String?
)

/** A stored physical inventory count for a store, with its per-product reconciliation report. */
data class StockCount(
    val id: String = UUID.randomUUID().toString(),
    val storeId: String,
    val variances: List<StockCountVariance>,
    val countedBy: String?,
    val countedAt: Long = System.currentTimeMillis()
) {
    val hasVariance: Boolean get() = variances.any { it.delta != 0 }
    val totalVarianceUnits: Int get() = variances.sumOf { kotlin.math.abs(it.delta) }
}
