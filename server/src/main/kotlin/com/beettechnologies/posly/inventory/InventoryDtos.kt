package com.beettechnologies.posly.inventory

import kotlinx.serialization.Serializable

@Serializable
data class AdjustStockRequest(
    val productId: String,
    val storeId: String,
    val delta: Int,
    val reason: String
)

@Serializable
data class ReserveRequest(
    val productId: String,
    val storeId: String,
    val quantity: Int,
    val referenceId: String
)

@Serializable
data class InventorySnapshotResponse(
    val productId: String,
    val storeId: String,
    val onHand: Int,
    val reserved: Int,
    val available: Int,
    val version: Long
)

fun InventorySnapshot.toResponse() = InventorySnapshotResponse(
    productId = productId,
    storeId = storeId,
    onHand = onHand,
    reserved = reserved,
    available = available,
    version = version
)

@Serializable
data class ReservationResponse(
    val id: String,
    val productId: String,
    val storeId: String,
    val quantity: Int,
    val referenceId: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

fun Reservation.toResponse() = ReservationResponse(
    id = id,
    productId = productId,
    storeId = storeId,
    quantity = quantity,
    referenceId = referenceId,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

@Serializable
data class InventoryTransactionResponse(
    val id: String,
    val productId: String,
    val storeId: String,
    val type: String,
    val quantity: Int,
    val referenceId: String? = null,
    val reason: String? = null,
    val actorId: String? = null,
    val createdAt: Long
)

fun InventoryTransaction.toResponse() = InventoryTransactionResponse(
    id = id,
    productId = productId,
    storeId = storeId,
    type = type.name,
    quantity = quantity,
    referenceId = referenceId,
    reason = reason,
    actorId = actorId,
    createdAt = createdAt
)
