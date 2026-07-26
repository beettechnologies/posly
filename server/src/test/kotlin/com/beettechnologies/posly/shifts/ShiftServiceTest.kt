package com.beettechnologies.posly.shifts

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.RefundLineItemInput
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShiftServiceTest {

    /** A settable "now" so a test can open a shift, seed sales, advance the clock, then close it. */
    private class TestClock(var instant: Instant)

    private data class Harness(val stores: StoreService, val orders: OrderService, val shifts: ShiftService, val clock: TestClock)

    private fun newHarness(openedAt: Instant = Instant.parse("2026-01-01T09:00:00Z"), threshold: Double = 5.0): Harness {
        val clock = TestClock(openedAt)
        val stores = StoreService(TaxProfileService())
        val orders = OrderService(nowProvider = { clock.instant })
        val shifts = ShiftService(stores, orders, nowProvider = { clock.instant }, varianceThreshold = threshold)
        return Harness(stores, orders, shifts, clock)
    }

    private fun seedStore(stores: StoreService, name: String = "Downtown"): String {
        val result = stores.createStore(
            name = name,
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = null
        )
        return (result as CreateStoreResult.Created).store.id
    }

    /** Checks out and pays for a $[amount] order at [storeId] with [method] ("CASH", "CARD", ...). */
    private fun seedPaidOrder(orders: OrderService, storeId: String, amount: Double, method: String, checkedOutAt: Instant) {
        val cart = Cart(
            id = "cart-${(0..1_000_000).random()}",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = amount, taxCategory = TaxCategory.STANDARD)),
            createdAt = checkedOutAt,
            updatedAt = checkedOutAt
        )
        val totals = CartTotals(amount, 0.0, 0.0, amount, emptyList(), 0.0, amount)
        val order = orders.createOrder(cart, totals, "key-${(0..1_000_000).random()}", checkedOutAt = checkedOutAt)
        orders.confirmPayment(order.id, method, amount, null, "cashier-1")
    }

    /** Checks out, pays cash, then issues a MANUAL (cash) refund for the full amount. */
    private fun seedCashRefund(orders: OrderService, storeId: String, amount: Double, checkedOutAt: Instant) {
        val cart = Cart(
            id = "cart-${(0..1_000_000).random()}",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = amount, taxCategory = TaxCategory.STANDARD)),
            createdAt = checkedOutAt,
            updatedAt = checkedOutAt
        )
        val totals = CartTotals(amount, 0.0, 0.0, amount, emptyList(), 0.0, amount)
        val order = orders.createOrder(cart, totals, "key-${(0..1_000_000).random()}", checkedOutAt = checkedOutAt)
        orders.confirmPayment(order.id, "CASH", amount, null, "cashier-1")
        val itemId = order.items.single().id
        orders.refund(order.id, "refund-${(0..1_000_000).random()}", "MANUAL", listOf(RefundLineItemInput(itemId, 1)), "Customer return", "manager-1")
    }

    @Test
    fun `opening a shift persists the opening float`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        val result = h.shifts.openShift(storeId, cashierId = "cashier-1", openingFloat = 100.0)

        val shift = assertIs<OpenShiftResult.Success>(result).shift
        assertEquals(100.0, shift.openingFloat)
        assertEquals(ShiftStatus.OPEN, shift.status)
        assertEquals(shift, h.shifts.getShift(shift.id))
    }

    @Test
    fun `opening a shift for an unknown store is rejected`() {
        val h = newHarness()

        val result = h.shifts.openShift("does-not-exist", "cashier-1", 100.0)

        assertEquals(OpenShiftResult.StoreNotFound, result)
    }

    @Test
    fun `a negative opening float is rejected`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        val result = h.shifts.openShift(storeId, "cashier-1", -10.0)

        assertIs<OpenShiftResult.InvalidAmount>(result)
    }

    @Test
    fun `a cashier cannot open a second shift while one is already open`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        h.shifts.openShift(storeId, "cashier-1", 100.0)

        val result = h.shifts.openShift(storeId, "cashier-1", 100.0)

        assertEquals(OpenShiftResult.ShiftAlreadyOpen, result)
    }

    @Test
    fun `two different cashiers can each have their own open shift at the same store`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        assertIs<OpenShiftResult.Success>(h.shifts.openShift(storeId, "cashier-1", 100.0))
        assertIs<OpenShiftResult.Success>(h.shifts.openShift(storeId, "cashier-2", 100.0))
    }

    @Test
    fun `closing a shift with cash sales matching the count reports no variance`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        seedPaidOrder(h.orders, storeId, 25.0, "CASH", openedAt.plusSeconds(60))
        seedPaidOrder(h.orders, storeId, 10.0, "CARD", openedAt.plusSeconds(120))
        h.clock.instant = openedAt.plusSeconds(180)

        val result = h.shifts.closeShift(shift.id, closingCount = 125.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals(ShiftStatus.CLOSED, closed.status)
        assertEquals(125.0, closed.expectedCash, "opening float 100 + 25 cash sale, the card sale must not count")
        assertEquals(0.0, closed.variance)
    }

    @Test
    fun `a cash refund during the shift reduces expected cash`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        seedPaidOrder(h.orders, storeId, 50.0, "CASH", openedAt.plusSeconds(60))
        seedCashRefund(h.orders, storeId, 20.0, openedAt.plusSeconds(120))
        h.clock.instant = openedAt.plusSeconds(180)

        val result = h.shifts.closeShift(shift.id, closingCount = 150.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals(150.0, closed.expectedCash, "100 float + 50 cash sale + 20 cash sale for the refunded order - 20 cash refund")
        assertEquals(0.0, closed.variance)
    }

    @Test
    fun `orders outside the shift window do not affect expected cash`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt)
        val storeId = seedStore(h.stores)
        seedPaidOrder(h.orders, storeId, 999.0, "CASH", openedAt.minusSeconds(60))
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        h.clock.instant = openedAt.plusSeconds(60)

        val result = h.shifts.closeShift(shift.id, closingCount = 100.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals(100.0, closed.expectedCash, "a sale before the shift opened must not be counted")
    }

    @Test
    fun `a variance within the threshold closes without a note or manager override`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt, threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val result = h.shifts.closeShift(shift.id, closingCount = 104.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals(4.0, closed.variance)
        assertEquals(ShiftVarianceCause.OVER, ShiftVarianceEngine.causeFor(closed.variance!!))
    }

    @Test
    fun `a variance over the threshold without a note or manager closer requires override or note`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt, threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val result = h.shifts.closeShift(shift.id, closingCount = 90.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val requirement = assertIs<CloseShiftResult.RequiresOverrideOrNote>(result)
        assertEquals(-10.0, requirement.variance)
        assertEquals(5.0, requirement.threshold)
        assertNull(h.shifts.getShift(shift.id)?.closedAt, "a rejected close must not mutate the shift")
    }

    @Test
    fun `a note satisfies an over-threshold variance for a non-manager closer`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt, threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val result = h.shifts.closeShift(
            shift.id, closingCount = 90.0, note = "Till was short, reported to manager", closedBy = "cashier-1", closedByIsManagerOrAdmin = false
        )

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals("Till was short, reported to manager", closed.note)
        assertEquals(ShiftVarianceCause.SHORT, ShiftVarianceEngine.causeFor(closed.variance!!))
        assertTrue(ShiftVarianceEngine.possibleReasons(ShiftVarianceCause.SHORT).isNotEmpty())
    }

    @Test
    fun `a manager or admin closer satisfies an over-threshold variance without a note`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt, threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val result = h.shifts.closeShift(shift.id, closingCount = 90.0, note = null, closedBy = "manager-1", closedByIsManagerOrAdmin = true)

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals("manager-1", closed.closedBy)
        assertNull(closed.note)
    }

    @Test
    fun `closing an already-closed shift is rejected`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        h.shifts.closeShift(shift.id, 100.0, null, "cashier-1", false)

        val result = h.shifts.closeShift(shift.id, 100.0, null, "cashier-1", false)

        assertEquals(CloseShiftResult.NotOpen, result)
    }

    @Test
    fun `closing an unknown shift is rejected`() {
        val h = newHarness()

        val result = h.shifts.closeShift("does-not-exist", 100.0, null, "cashier-1", false)

        assertEquals(CloseShiftResult.NotFound, result)
    }

    @Test
    fun `a negative closing count is rejected`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val result = h.shifts.closeShift(shift.id, -1.0, null, "cashier-1", false)

        assertIs<CloseShiftResult.InvalidAmount>(result)
    }

    @Test
    fun `previewExpectedCash reflects sales so far without closing the shift`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        seedPaidOrder(h.orders, storeId, 30.0, "CASH", openedAt.plusSeconds(60))
        h.clock.instant = openedAt.plusSeconds(120)

        assertEquals(130.0, h.shifts.previewExpectedCash(shift.id))
        assertEquals(ShiftStatus.OPEN, h.shifts.getShift(shift.id)?.status, "a preview must not mutate the shift")
    }

    @Test
    fun `previewExpectedCash returns null for an unknown shift`() {
        val h = newHarness()

        assertNull(h.shifts.previewExpectedCash("does-not-exist"))
    }

    @Test
    fun `rounding to the cent is applied to expected cash and variance`() {
        val openedAt = Instant.parse("2026-01-01T09:00:00Z")
        val h = newHarness(openedAt = openedAt)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        seedPaidOrder(h.orders, storeId, 10.1, "CASH", openedAt.plusSeconds(10))
        seedPaidOrder(h.orders, storeId, 10.15, "CASH", openedAt.plusSeconds(20))
        seedPaidOrder(h.orders, storeId, 10.2, "CASH", openedAt.plusSeconds(30))
        h.clock.instant = openedAt.plusSeconds(60)

        val result = h.shifts.closeShift(shift.id, closingCount = 130.45, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val closed = assertIs<CloseShiftResult.Success>(result).shift
        assertEquals(130.45, closed.expectedCash)
        assertEquals(0.0, closed.variance)
    }

    @Test
    fun `listShifts filters by store and cashier`() {
        val h = newHarness()
        val storeA = seedStore(h.stores, "Downtown")
        val storeB = seedStore(h.stores, "Uptown")
        h.shifts.openShift(storeA, "cashier-1", 100.0)
        h.shifts.openShift(storeA, "cashier-2", 100.0)
        h.shifts.openShift(storeB, "cashier-1", 100.0)

        assertEquals(2, h.shifts.listShifts(storeId = storeA).size)
        assertEquals(2, h.shifts.listShifts(cashierId = "cashier-1").size)
        assertEquals(1, h.shifts.listShifts(storeId = storeA, cashierId = "cashier-1").size)
        assertEquals(3, h.shifts.listShifts().size)
    }

    @Test
    fun `opening a shift records an OPENED audit event`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val events = h.shifts.listAuditEvents(shift.id)
        assertEquals(listOf(ShiftAuditEventType.OPENED), events.map { it.type })
        assertEquals("cashier-1", events.single().actorId)
    }

    @Test
    fun `closing with an exact count records CLOSED but no discrepancy event`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        h.shifts.closeShift(shift.id, closingCount = 100.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val events = h.shifts.listAuditEvents(shift.id)
        assertEquals(listOf(ShiftAuditEventType.OPENED, ShiftAuditEventType.CLOSED), events.map { it.type })
    }

    @Test
    fun `closing with a within-threshold variance records a DISCREPANCY_RECORDED event`() {
        val h = newHarness(threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        h.shifts.closeShift(shift.id, closingCount = 103.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val events = h.shifts.listAuditEvents(shift.id)
        assertEquals(listOf(ShiftAuditEventType.OPENED, ShiftAuditEventType.CLOSED, ShiftAuditEventType.DISCREPANCY_RECORDED), events.map { it.type })
        val discrepancy = events.single { it.type == ShiftAuditEventType.DISCREPANCY_RECORDED }
        assertTrue(discrepancy.detail!!.contains("variance=3.0"))
        assertTrue(discrepancy.detail!!.contains("cause=OVER"))
    }

    @Test
    fun `a manager override with a reason logs it as a MANAGER_OVERRIDE event linked to the shift`() {
        val h = newHarness(threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        h.shifts.closeShift(
            shift.id, closingCount = 80.0, note = "Cashier reported a till error, verified in person",
            closedBy = "manager-1", closedByIsManagerOrAdmin = true
        )

        val events = h.shifts.listAuditEvents(shift.id)
        val override = events.single { it.type == ShiftAuditEventType.MANAGER_OVERRIDE }
        assertEquals(shift.id, override.shiftId)
        assertEquals("manager-1", override.actorId)
        assertTrue(override.detail!!.contains("Cashier reported a till error, verified in person"))
    }

    @Test
    fun `a manager override with no reason is still logged, noting none was provided`() {
        val h = newHarness(threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        h.shifts.closeShift(shift.id, closingCount = 80.0, note = null, closedBy = "manager-1", closedByIsManagerOrAdmin = true)

        val override = h.shifts.listAuditEvents(shift.id).single { it.type == ShiftAuditEventType.MANAGER_OVERRIDE }
        assertTrue(override.detail!!.contains("No reason provided"))
    }

    @Test
    fun `a note-satisfied over-threshold close by a non-manager does not record a MANAGER_OVERRIDE event`() {
        val h = newHarness(threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        h.shifts.closeShift(shift.id, closingCount = 80.0, note = "Till was short", closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        val events = h.shifts.listAuditEvents(shift.id)
        assertTrue(events.none { it.type == ShiftAuditEventType.MANAGER_OVERRIDE }, "a cashier satisfying the requirement with a note is not an override")
        assertTrue(events.any { it.type == ShiftAuditEventType.DISCREPANCY_RECORDED })
    }

    @Test
    fun `a rejected close (requires override or note) does not record any audit events`() {
        val h = newHarness(threshold = 5.0)
        val storeId = seedStore(h.stores)
        val shift = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift

        val result = h.shifts.closeShift(shift.id, closingCount = 80.0, note = null, closedBy = "cashier-1", closedByIsManagerOrAdmin = false)

        assertIs<CloseShiftResult.RequiresOverrideOrNote>(result)
        assertEquals(listOf(ShiftAuditEventType.OPENED), h.shifts.listAuditEvents(shift.id).map { it.type })
    }

    @Test
    fun `listAuditEvents scopes to the given shift and returns events oldest first`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val shiftA = (h.shifts.openShift(storeId, "cashier-1", 100.0) as OpenShiftResult.Success).shift
        val shiftB = (h.shifts.openShift(storeId, "cashier-2", 50.0) as OpenShiftResult.Success).shift
        h.shifts.closeShift(shiftA.id, 100.0, null, "cashier-1", false)

        val eventsA = h.shifts.listAuditEvents(shiftA.id)
        assertEquals(listOf(ShiftAuditEventType.OPENED, ShiftAuditEventType.CLOSED), eventsA.map { it.type })
        assertEquals(listOf(ShiftAuditEventType.OPENED), h.shifts.listAuditEvents(shiftB.id).map { it.type })
    }
}
