package com.beettechnologies.posly.inventory

import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.StoreService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class SubmitStockCountResult {
    data class Success(val stockCount: StockCount) : SubmitStockCountResult()
    data object StoreNotFound : SubmitStockCountResult()
    data class InvalidLine(val message: String) : SubmitStockCountResult()
}

/**
 * Reconciles a physical stock count against the system's current expected on-hand quantity
 * ([InventoryService.getSnapshot]) and immediately posts an [InventoryTransactionType.ADJUSTMENT]
 * for every product whose count differs - there's no separate "review then approve" step, so the
 * returned [StockCount] doubles as both the audit record of what was counted and the reconciliation
 * report of what it did about it.
 *
 * All lines are validated up front (store/products exist, counts are non-negative, and none would
 * push on-hand below what's currently reserved) before any adjustment is posted, so a bad line in
 * an otherwise-good count fails the whole submission rather than leaving some products reconciled
 * and others not.
 */
class StockCountService(
    private val inventoryService: InventoryService,
    private val productService: ProductService,
    private val storeService: StoreService
) {
    private val stockCounts = ConcurrentHashMap<String, StockCount>()

    fun submitStockCount(storeId: String, lines: List<StockCountLineInput>, countedBy: String?): SubmitStockCountResult {
        if (storeService.getStore(storeId) == null) return SubmitStockCountResult.StoreNotFound
        if (lines.isEmpty()) return SubmitStockCountResult.InvalidLine("at least one line is required")

        val seenProductIds = mutableSetOf<String>()
        for (line in lines) {
            if (!seenProductIds.add(line.productId)) {
                return SubmitStockCountResult.InvalidLine("duplicate productId ${line.productId} in stock count")
            }
            if (line.countedQuantity < 0) {
                return SubmitStockCountResult.InvalidLine("countedQuantity must not be negative for ${line.productId}")
            }
            if (productService.getProduct(line.productId) == null) {
                return SubmitStockCountResult.InvalidLine("unknown product ${line.productId}")
            }
            val reserved = inventoryService.getSnapshot(line.productId, storeId)?.reserved ?: 0
            if (line.countedQuantity < reserved) {
                return SubmitStockCountResult.InvalidLine(
                    "counted quantity ${line.countedQuantity} for ${line.productId} is below the ${reserved} units currently reserved"
                )
            }
        }

        val stockCountId = UUID.randomUUID().toString()
        val variances = lines.map { line ->
            val expected = inventoryService.getSnapshot(line.productId, storeId)?.onHand ?: 0
            val delta = line.countedQuantity - expected
            var adjustmentTransactionId: String? = null
            if (delta != 0) {
                val result = inventoryService.adjustStock(
                    productId = line.productId,
                    storeId = storeId,
                    delta = delta,
                    reason = "Stock count reconciliation ($stockCountId)",
                    actorId = countedBy,
                    referenceId = stockCountId
                )
                adjustmentTransactionId = (result as? AdjustStockResult.Success)?.transactionId
                    ?: error("adjustStock rejected a pre-validated line for ${line.productId}: $result")
            }
            StockCountVariance(
                productId = line.productId,
                expectedQuantity = expected,
                countedQuantity = line.countedQuantity,
                delta = delta,
                cause = ReconciliationEngine.causeFor(delta),
                suggestedAdjustment = delta,
                adjustmentTransactionId = adjustmentTransactionId
            )
        }

        val stockCount = StockCount(id = stockCountId, storeId = storeId, variances = variances, countedBy = countedBy)
        stockCounts[stockCount.id] = stockCount
        return SubmitStockCountResult.Success(stockCount)
    }

    fun getStockCount(id: String): StockCount? = stockCounts[id]

    fun listStockCounts(storeId: String? = null): List<StockCount> =
        stockCounts.values
            .filter { storeId == null || it.storeId == storeId }
            .sortedByDescending { it.countedAt }
}
