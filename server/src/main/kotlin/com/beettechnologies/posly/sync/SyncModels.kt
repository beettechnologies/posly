package com.beettechnologies.posly.sync

import com.beettechnologies.posly.cart.Discount
import java.time.Instant

/**
 * How an ingested sale's items are priced when they conflict with the current catalog:
 * REJECT persists nothing, MAP re-prices to today's catalog, CONVERT keeps whatever the
 * customer was actually charged offline. Chosen once per batch by the syncing device.
 */
enum class ConflictPolicy { REJECT, MAP, CONVERT }

enum class ConflictReason {
    /** The SKU no longer resolves to any product - can't be MAP'd or CONVERT'd, always rejected. */
    PRODUCT_NOT_FOUND,
    PRICE_CHANGED,
    TAX_CATEGORY_CHANGED,
    /** The tenders captured offline don't cover (or exceed) the sale's total - always rejected. */
    PAYMENT_MISMATCH,
    /** Structurally malformed sale (no items, non-positive quantity, blank idempotency key). */
    INVALID_SALE
}

data class ItemConflict(
    val sku: String?,
    val reason: ConflictReason,
    val capturedValue: String?,
    val currentValue: String?
)

enum class OfflineSaleOutcome { CREATED, CONFLICT_RESOLVED_MAP, CONFLICT_RESOLVED_CONVERT, CONFLICT_REJECTED }

/**
 * One ledger entry per idempotencyKey ever submitted to the offline sync endpoint - the single
 * source of truth for "have we already processed this sale" (a resubmission replays this record
 * verbatim instead of reprocessing) and, independently, the durable record an admin reviews for
 * anything that didn't cleanly resolve to CREATED.
 */
data class OfflineSaleRecord(
    val idempotencyKey: String,
    val deviceId: String,
    val storeId: String,
    val outcome: OfflineSaleOutcome,
    val orderId: String?,
    val conflicts: List<ItemConflict>,
    val processedAt: Instant
)

// --- Domain-level ingestion input (route layer converts request DTOs into these) ---

data class OfflineSaleModifierInput(val modifierId: String, val option: String, val additionalCost: Double)

data class OfflineSaleItemInput(
    val sku: String,
    val productName: String,
    val quantity: Int,
    val unitPriceAtSale: Double,
    val taxCategoryAtSale: String,
    val selectedModifiers: List<OfflineSaleModifierInput> = emptyList(),
    val discount: Discount? = null
)

data class OfflineSalePaymentInput(
    val method: String,
    val amount: Double,
    val reference: String? = null
)

data class OfflineSaleInput(
    val idempotencyKey: String,
    val items: List<OfflineSaleItemInput>,
    val discount: Discount? = null,
    val payments: List<OfflineSalePaymentInput>,
    val soldAt: Instant,
    val soldBy: String? = null
)
