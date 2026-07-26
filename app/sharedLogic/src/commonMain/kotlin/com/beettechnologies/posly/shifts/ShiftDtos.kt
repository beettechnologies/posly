package com.beettechnologies.posly.shifts

import kotlinx.serialization.Serializable

@Serializable
data class OpenShiftRequest(val storeId: String, val openingFloat: Double)

@Serializable
data class CloseShiftRequest(val closingCount: Double, val note: String? = null)

@Serializable
data class ShiftResponse(
    val id: String,
    val storeId: String,
    val cashierId: String?,
    val openingFloat: Double,
    val openedAt: String,
    val status: String,
    val closingCount: Double? = null,
    val expectedCash: Double? = null,
    val variance: Double? = null,
    val varianceCause: String? = null,
    val possibleReasons: List<String> = emptyList(),
    val note: String? = null,
    val closedBy: String? = null,
    val closedAt: String? = null
)

@Serializable
data class ExpectedCashResponse(val expectedCash: Double, val asOf: String)
