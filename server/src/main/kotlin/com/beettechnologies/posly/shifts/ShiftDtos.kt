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

@Serializable
data class ShiftAuditEventResponse(
    val id: String,
    val shiftId: String,
    val type: String,
    val actorId: String?,
    val detail: String?,
    val createdAt: String
)

fun ShiftAuditEvent.toResponse() = ShiftAuditEventResponse(
    id = id,
    shiftId = shiftId,
    type = type.name,
    actorId = actorId,
    detail = detail,
    createdAt = createdAt.toString()
)

fun Shift.toResponse(): ShiftResponse {
    val cause = variance?.let { ShiftVarianceEngine.causeFor(it) }
    return ShiftResponse(
        id = id,
        storeId = storeId,
        cashierId = cashierId,
        openingFloat = openingFloat,
        openedAt = openedAt.toString(),
        status = status.name,
        closingCount = closingCount,
        expectedCash = expectedCash,
        variance = variance,
        varianceCause = cause?.name,
        possibleReasons = cause?.let { ShiftVarianceEngine.possibleReasons(it) } ?: emptyList(),
        note = note,
        closedBy = closedBy,
        closedAt = closedAt?.toString()
    )
}
