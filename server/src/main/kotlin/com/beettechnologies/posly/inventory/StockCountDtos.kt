package com.beettechnologies.posly.inventory

import kotlinx.serialization.Serializable

@Serializable
data class StockCountLineRequest(val productId: String, val countedQuantity: Int)

@Serializable
data class SubmitStockCountRequest(val storeId: String, val lines: List<StockCountLineRequest>)

@Serializable
data class StockCountVarianceResponse(
    val productId: String,
    val expectedQuantity: Int,
    val countedQuantity: Int,
    val delta: Int,
    val cause: String,
    val probableCause: String,
    val suggestedAdjustment: Int,
    val adjustmentTransactionId: String? = null
)

@Serializable
data class StockCountResponse(
    val id: String,
    val storeId: String,
    val variances: List<StockCountVarianceResponse>,
    val countedBy: String?,
    val countedAt: Long,
    val hasVariance: Boolean,
    val totalVarianceUnits: Int
)

fun StockCountVariance.toResponse() = StockCountVarianceResponse(
    productId = productId,
    expectedQuantity = expectedQuantity,
    countedQuantity = countedQuantity,
    delta = delta,
    cause = cause.name,
    probableCause = ReconciliationEngine.describe(cause),
    suggestedAdjustment = suggestedAdjustment,
    adjustmentTransactionId = adjustmentTransactionId
)

fun StockCount.toResponse() = StockCountResponse(
    id = id,
    storeId = storeId,
    variances = variances.map { it.toResponse() },
    countedBy = countedBy,
    countedAt = countedAt,
    hasVariance = hasVariance,
    totalVarianceUnits = totalVarianceUnits
)
