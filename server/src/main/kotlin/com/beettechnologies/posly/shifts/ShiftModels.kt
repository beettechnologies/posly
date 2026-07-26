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
