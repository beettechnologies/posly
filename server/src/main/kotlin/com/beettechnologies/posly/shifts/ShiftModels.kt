package com.beettechnologies.posly.shifts

import java.time.Instant
import java.util.UUID

enum class ShiftStatus { OPEN, CLOSED }

/**
 * One cashier's till session at a store, from opening float to closing count. [expectedCash],
 * [variance], [note], [closedBy], and [closedAt] are only ever set once, at close time - there is
 * no separate approval step, so closing IS the reconciliation (mirrors how a ticket-28 stock count
 * reconciles immediately rather than needing a review phase).
 */
data class Shift(
    val id: String = UUID.randomUUID().toString(),
    val storeId: String,
    val cashierId: String?,
    val openingFloat: Double,
    val openedAt: Instant,
    val status: ShiftStatus = ShiftStatus.OPEN,
    val closingCount: Double? = null,
    val expectedCash: Double? = null,
    val variance: Double? = null,
    val note: String? = null,
    val closedBy: String? = null,
    val closedAt: Instant? = null
)

enum class ShiftVarianceCause { NONE, SHORT, OVER }

enum class ShiftAuditEventType { OPENED, CLOSED, DISCREPANCY_RECORDED, MANAGER_OVERRIDE }

/**
 * An append-only entry in a shift's audit trail - mirrors [com.beettechnologies.posly.cart.OrderEvent]
 * and [com.beettechnologies.posly.inventory.InventoryTransaction]: never mutated or removed once
 * recorded, so it stays a durable record of what happened and why, independent of the mutable
 * [Shift] row itself.
 */
data class ShiftAuditEvent(
    val id: String = UUID.randomUUID().toString(),
    val shiftId: String,
    val type: ShiftAuditEventType,
    val actorId: String?,
    val detail: String?,
    val createdAt: Instant
)
