package com.beettechnologies.posly.shifts

import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.roundCents
import com.beettechnologies.posly.db.ShiftAuditEventsTable
import com.beettechnologies.posly.db.ShiftsTable
import com.beettechnologies.posly.stores.StoreService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

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

private fun rowToShift(row: ResultRow) = Shift(
    id = row[ShiftsTable.id],
    storeId = row[ShiftsTable.storeId],
    cashierId = row[ShiftsTable.cashierId],
    openingFloat = row[ShiftsTable.openingFloat],
    openedAt = row[ShiftsTable.openedAt],
    status = ShiftStatus.valueOf(row[ShiftsTable.status]),
    closingCount = row[ShiftsTable.closingCount],
    expectedCash = row[ShiftsTable.expectedCash],
    variance = row[ShiftsTable.variance],
    note = row[ShiftsTable.note],
    closedBy = row[ShiftsTable.closedBy],
    closedAt = row[ShiftsTable.closedAt]
)

private fun rowToShiftAuditEvent(row: ResultRow) = ShiftAuditEvent(
    id = row[ShiftAuditEventsTable.id],
    shiftId = row[ShiftAuditEventsTable.shiftId],
    type = ShiftAuditEventType.valueOf(row[ShiftAuditEventsTable.type]),
    actorId = row[ShiftAuditEventsTable.actorId],
    detail = row[ShiftAuditEventsTable.detail],
    createdAt = row[ShiftAuditEventsTable.createdAt]
)

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

    fun openShift(storeId: String, cashierId: String?, openingFloat: Double): OpenShiftResult {
        if (storeService.getStore(storeId) == null) return OpenShiftResult.StoreNotFound
        if (openingFloat < 0) return OpenShiftResult.InvalidAmount("openingFloat must not be negative")

        return transaction {
            if (cashierId != null && hasOpenShift(storeId, cashierId)) return@transaction OpenShiftResult.ShiftAlreadyOpen

            val shift = Shift(storeId = storeId, cashierId = cashierId, openingFloat = openingFloat, openedAt = nowProvider())
            ShiftsTable.insert {
                it[id] = shift.id
                it[ShiftsTable.storeId] = shift.storeId
                it[ShiftsTable.cashierId] = shift.cashierId
                it[ShiftsTable.openingFloat] = shift.openingFloat
                it[openedAt] = shift.openedAt
                it[status] = shift.status.name
            }
            recordAuditEvent(shift.id, ShiftAuditEventType.OPENED, cashierId, "openingFloat=$openingFloat")
            OpenShiftResult.Success(shift)
        }
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
    ): CloseShiftResult = transaction {
        val shift = ShiftsTable.selectAll().where { ShiftsTable.id eq shiftId }
            .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()?.let { rowToShift(it) }
            ?: return@transaction CloseShiftResult.NotFound
        if (shift.status != ShiftStatus.OPEN) return@transaction CloseShiftResult.NotOpen
        if (closingCount < 0) return@transaction CloseShiftResult.InvalidAmount("closingCount must not be negative")

        val closedAt = nowProvider()
        val expectedCash = computeExpectedCash(shift, closedAt)
        val variance = roundCents(closingCount - expectedCash)

        val overThreshold = kotlin.math.abs(variance) > varianceThreshold
        if (overThreshold && note.isNullOrBlank() && !closedByIsManagerOrAdmin) {
            return@transaction CloseShiftResult.RequiresOverrideOrNote(variance, varianceThreshold)
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
        ShiftsTable.update({ ShiftsTable.id eq shiftId }) {
            it[status] = updated.status.name
            it[ShiftsTable.closingCount] = updated.closingCount
            it[ShiftsTable.expectedCash] = updated.expectedCash
            it[ShiftsTable.variance] = updated.variance
            it[ShiftsTable.note] = updated.note
            it[ShiftsTable.closedBy] = updated.closedBy
            it[ShiftsTable.closedAt] = updated.closedAt
        }

        recordAuditEvent(
            shiftId, ShiftAuditEventType.CLOSED, closedBy,
            "closingCount=$closingCount expectedCash=$expectedCash variance=$variance"
        )
        if (variance != 0.0) {
            recordAuditEvent(
                shiftId, ShiftAuditEventType.DISCREPANCY_RECORDED, closedBy,
                "expected=$expectedCash actual=$closingCount variance=$variance cause=${ShiftVarianceEngine.causeFor(variance)}"
            )
        }
        if (overThreshold && closedByIsManagerOrAdmin) {
            recordAuditEvent(
                shiftId, ShiftAuditEventType.MANAGER_OVERRIDE, closedBy,
                "variance=$variance threshold=$varianceThreshold reason=${note?.takeIf { it.isNotBlank() } ?: "No reason provided"}"
            )
        }
        CloseShiftResult.Success(updated)
    }

    fun getShift(id: String): Shift? = transaction {
        ShiftsTable.selectAll().where { ShiftsTable.id eq id }.singleOrNull()?.let { rowToShift(it) }
    }

    fun listShifts(storeId: String? = null, cashierId: String? = null): List<Shift> = transaction {
        var query = ShiftsTable.selectAll()
        if (storeId != null) query = query.andWhere { ShiftsTable.storeId eq storeId }
        if (cashierId != null) query = query.andWhere { ShiftsTable.cashierId eq cashierId }
        query.map { rowToShift(it) }.sortedByDescending { it.openedAt }
    }

    /** The full append-only audit trail for one shift, oldest first. */
    fun listAuditEvents(shiftId: String): List<ShiftAuditEvent> = transaction {
        ShiftAuditEventsTable.selectAll().where { ShiftAuditEventsTable.shiftId eq shiftId }
            .orderBy(ShiftAuditEventsTable.createdAt to SortOrder.ASC)
            .map { rowToShiftAuditEvent(it) }
    }

    /** The expected-cash figure a still-open shift would close at right now, without mutating anything. */
    fun previewExpectedCash(shiftId: String): Double? {
        val shift = getShift(shiftId) ?: return null
        return computeExpectedCash(shift, nowProvider())
    }

    private fun hasOpenShift(storeId: String, cashierId: String): Boolean =
        !ShiftsTable.selectAll()
            .where { (ShiftsTable.storeId eq storeId) and (ShiftsTable.cashierId eq cashierId) and (ShiftsTable.status eq ShiftStatus.OPEN.name) }
            .empty()

    private fun recordAuditEvent(shiftId: String, type: ShiftAuditEventType, actorId: String?, detail: String?) {
        ShiftAuditEventsTable.insert {
            it[id] = java.util.UUID.randomUUID().toString()
            it[ShiftAuditEventsTable.shiftId] = shiftId
            it[ShiftAuditEventsTable.type] = type.name
            it[ShiftAuditEventsTable.actorId] = actorId
            it[ShiftAuditEventsTable.detail] = detail
            it[createdAt] = nowProvider()
        }
    }

    private fun computeExpectedCash(shift: Shift, asOf: Instant): Double {
        val orders = orderService.listOrders(shift.storeId, shift.openedAt, asOf)
        val cashSales = orders.sumOf { order -> order.payments.filter { it.method == "CASH" }.sumOf { it.amount } }
        val cashRefunds = orders.sumOf { order -> order.refunds.filter { it.method == "MANUAL" }.sumOf { it.amount } }
        return roundCents(shift.openingFloat + cashSales - cashRefunds)
    }
}
