package com.beettechnologies.posly.sync

import com.beettechnologies.posly.cart.DiscountDto
import kotlinx.serialization.Serializable

@Serializable
data class OfflineSaleModifierRequest(val modifierId: String, val option: String, val additionalCost: Double)

@Serializable
data class OfflineSaleItemRequest(
    val sku: String,
    val productName: String,
    val quantity: Int,
    val unitPriceAtSale: Double,
    val taxCategoryAtSale: String,
    val selectedModifiers: List<OfflineSaleModifierRequest> = emptyList(),
    val discount: DiscountDto? = null
)

@Serializable
data class OfflineSalePaymentRequest(
    val method: String,
    val amount: Double,
    val reference: String? = null
)

@Serializable
data class OfflineSaleRequest(
    val idempotencyKey: String,
    val items: List<OfflineSaleItemRequest>,
    val discount: DiscountDto? = null,
    val payments: List<OfflineSalePaymentRequest> = emptyList(),
    val soldAt: String,
    val soldBy: String? = null
)

@Serializable
data class OfflineSaleBatchRequest(
    val clientId: String,
    val clientSecret: String,
    val conflictPolicy: String = "REJECT",
    val sales: List<OfflineSaleRequest>
)

@Serializable
data class ItemConflictResponse(
    val sku: String?,
    val reason: String,
    val capturedValue: String?,
    val currentValue: String?
)

@Serializable
data class OfflineSaleResultResponse(
    val idempotencyKey: String,
    val outcome: String,
    val orderId: String?,
    val replayed: Boolean,
    val conflicts: List<ItemConflictResponse>
)

@Serializable
data class OfflineSaleBatchResponse(val results: List<OfflineSaleResultResponse>)

@Serializable
data class OfflineSaleConflictResponse(
    val idempotencyKey: String,
    val deviceId: String,
    val storeId: String,
    val outcome: String,
    val orderId: String?,
    val conflicts: List<ItemConflictResponse>,
    val processedAt: String
)

fun ItemConflict.toResponse() = ItemConflictResponse(
    sku = sku,
    reason = reason.name,
    capturedValue = capturedValue,
    currentValue = currentValue
)

fun OfflineSaleOutcomeResult.toResponse() = OfflineSaleResultResponse(
    idempotencyKey = record.idempotencyKey,
    outcome = record.outcome.name,
    orderId = record.orderId,
    replayed = replayed,
    conflicts = record.conflicts.map { it.toResponse() }
)

fun OfflineSaleRecord.toResponse() = OfflineSaleConflictResponse(
    idempotencyKey = idempotencyKey,
    deviceId = deviceId,
    storeId = storeId,
    outcome = outcome.name,
    orderId = orderId,
    conflicts = conflicts.map { it.toResponse() },
    processedAt = processedAt.toString()
)
