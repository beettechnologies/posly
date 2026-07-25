package com.beettechnologies.posly.inventory

import java.util.UUID

data class InventorySnapshot(
    val productId: String,
    val storeId: String,
    val onHand: Int,
    val reserved: Int,
    val version: Long = 0
) {
    val available: Int get() = onHand - reserved
}

enum class InventoryTransactionType { RESERVE, RELEASE, COMMIT, ADJUSTMENT }

data class InventoryTransaction(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val storeId: String,
    val type: InventoryTransactionType,
    val quantity: Int,
    val referenceId: String? = null,
    val reason: String? = null,
    val actorId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ReservationStatus { ACTIVE, RELEASED, COMMITTED }

data class Reservation(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val storeId: String,
    val quantity: Int,
    val referenceId: String,
    val status: ReservationStatus = ReservationStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
