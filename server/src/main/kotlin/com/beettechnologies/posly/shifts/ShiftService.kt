package com.beettechnologies.posly.shifts

import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.roundCents
import com.beettechnologies.posly.stores.StoreService
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed class OpenShiftResult {
    data class Success(val shift: Shift) : OpenShiftResult()
    data object StoreNotFound : OpenShiftResult()
    data object ShiftAlreadyOpen : OpenShiftResult()
    data class InvalidAmount(val message: String) : OpenShiftResult()
}

sealed class CloseShiftResult {
    data class Success(val shift: Shift) : CloseShiftResult()
    data object NotFound : CloseShiftResult()
    data object NotOpen : CloseShiftResult()
    data class InvalidAmount(val message: String) : CloseShiftResult()
    /** Over-threshold variance with neither a note nor a manager/admin closer - the caller must supply one or the other and retry. */
    data class RequiresOverrideOrNote(val variance: Double, val threshold: Double) : CloseShiftResult()
}

/**
 * Owns the cashier shift lifecycle: opening float -> (cash sales happen via [OrderService] as
 * normal) -> closing count. Expected cash is computed at close time as the opening float plus
 * every CASH payment confirmed on this store's orders during the shift window, minus every MANUAL
 * (cash-handled) refund in that same window - approximated via [OrderService.listOrders] rather
 * than tagging individual orders with a shift id, since orders and shifts are otherwise unrelated
 * concepts and a cashier's shift window is normally an accurate proxy for "their" sales.
 */
class ShiftService(
    private val storeService: StoreService,
    private val orderService: OrderService,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val varianceThreshold: Double = ShiftVarianceEngine.DEFAULT_THRESHOLD
) {
    private val shifts = ConcurrentHashMap<String, Shift>()

    fun openShift(storeId: String, cashierId: String?, openingFloat: Double): OpenShiftResult {
        if (storeService.getStore(storeId) == null) return OpenShiftResult.StoreNotFound
        if (openingFloat < 0) return OpenShiftResult.InvalidAmount("openingFloat must not be negative")
        if (cashierId != null && hasOpenShift(storeId, cashierId)) return OpenShiftResult.ShiftAlreadyOpen

        val shift = Shift(storeId = storeId, cashierId = cashierId, openingFloat = openingFloat, openedAt = nowProvider())
        shifts[shift.id] = shift
        return OpenShiftResult.Success(shift)
    }

    /**
     * [note] and [closedByIsManagerOrAdmin] are the two ways to satisfy an over-threshold variance;
     * neither is required when the variance is within [varianceThreshold].
     */
    fun closeShift(
        shiftId: String,
        closingCount: Double,
        note: String?,
        closedBy: String?,
        closedByIsManagerOrAdmin: Boolean
    ): CloseShiftResult {
        val shift = shifts[shiftId] ?: return CloseShiftResult.NotFound
        if (shift.status != ShiftStatus.OPEN) return CloseShiftResult.NotOpen
        if (closingCount < 0) return CloseShiftResult.InvalidAmount("closingCount must not be negative")

        val closedAt = nowProvider()
        val expectedCash = computeExpectedCash(shift, closedAt)
        val variance = roundCents(closingCount - expectedCash)

        val overThreshold = kotlin.math.abs(variance) > varianceThreshold
        if (overThreshold && note.isNullOrBlank() && !closedByIsManagerOrAdmin) {
            return CloseShiftResult.RequiresOverrideOrNote(variance, varianceThreshold)
        }

        val updated = shift.copy(
            status = ShiftStatus.CLOSED,
            closingCount = closingCount,
            expectedCash = expectedCash,
            variance = variance,
            note = note,
            closedBy = closedBy,
            closedAt = closedAt
        )
        shifts[shiftId] = updated
        return CloseShiftResult.Success(updated)
    }

    fun getShift(id: String): Shift? = shifts[id]

    fun listShifts(storeId: String? = null, cashierId: String? = null): List<Shift> =
        shifts.values
            .filter { (storeId == null || it.storeId == storeId) && (cashierId == null || it.cashierId == cashierId) }
            .sortedByDescending { it.openedAt }

    /** The expected-cash figure a still-open shift would close at right now, without mutating anything. */
    fun previewExpectedCash(shiftId: String): Double? {
        val shift = shifts[shiftId] ?: return null
        return computeExpectedCash(shift, nowProvider())
    }

    private fun hasOpenShift(storeId: String, cashierId: String): Boolean =
        shifts.values.any { it.storeId == storeId && it.cashierId == cashierId && it.status == ShiftStatus.OPEN }

    private fun computeExpectedCash(shift: Shift, asOf: Instant): Double {
        val orders = orderService.listOrders(shift.storeId, shift.openedAt, asOf)
        val cashSales = orders.sumOf { order -> order.payments.filter { it.method == "CASH" }.sumOf { it.amount } }
        val cashRefunds = orders.sumOf { order -> order.refunds.filter { it.method == "MANUAL" }.sumOf { it.amount } }
        return roundCents(shift.openingFloat + cashSales - cashRefunds)
    }
}
