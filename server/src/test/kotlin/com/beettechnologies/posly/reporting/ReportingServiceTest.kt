package com.beettechnologies.posly.reporting

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.OrderEventType
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.RefundLineItemInput
import com.beettechnologies.posly.inventory.InventoryService
import com.beettechnologies.posly.inventory.StockCountLineInput
import com.beettechnologies.posly.inventory.StockCountService
import com.beettechnologies.posly.inventory.SubmitStockCountResult
import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.shifts.CloseShiftResult
import com.beettechnologies.posly.shifts.OpenShiftResult
import com.beettechnologies.posly.shifts.ShiftService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReportingServiceTest {

    /** A settable "now" so period-boundary math is exercised deterministically. */
    private class TestClock(var instant: Instant)

    private data class Harness(
        val stores: StoreService,
        val orders: OrderService,
        val shifts: ShiftService,
        val stockCounts: StockCountService,
        val products: ProductService,
        val reporting: ReportingService,
        val clock: TestClock
    )

    private fun newHarness(now: Instant = Instant.parse("2026-01-15T12:00:00Z")): Harness {
        val clock = TestClock(now)
        val products = ProductService()
        val stores = StoreService(TaxProfileService())
        val orders = OrderService(nowProvider = { clock.instant })
        val shifts = ShiftService(stores, orders, nowProvider = { clock.instant })
        val inventory = InventoryService(products, stores)
        val stockCounts = StockCountService(inventory, products, stores)
        val reporting = ReportingService(orders, stores, stockCounts, shifts, nowProvider = { clock.instant })
        return Harness(stores, orders, shifts, stockCounts, products, reporting, clock)
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

    private fun seedPaidOrder(orders: OrderService, storeId: String, amount: Double, checkedOutAt: Instant, quantity: Int = 1): String {
        val cart = Cart(
            id = "cart-${(0..10_000_000).random()}",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(CartItem(productId = "product-1", productName = "Widget", quantity = quantity, unitPrice = amount / quantity, taxCategory = TaxCategory.STANDARD)),
            createdAt = checkedOutAt,
            updatedAt = checkedOutAt
        )
        val totals = CartTotals(amount, 0.0, 0.0, amount, emptyList(), 0.0, amount)
        val order = orders.createOrder(cart, totals, "key-${(0..10_000_000).random()}", checkedOutAt = checkedOutAt)
        orders.confirmPayment(order.id, "CASH", amount, null, "cashier-1")
        return order.id
    }

    // -------------------------------------------------------------------------
    // Sales aggregate
    // -------------------------------------------------------------------------

    @Test
    fun `a DAILY pipeline run aggregates only orders checked out that day`() {
        val h = newHarness(now = Instant.parse("2026-01-15T12:00:00Z"))
        val storeId = seedStore(h.stores)
        seedPaidOrder(h.orders, storeId, 10.0, Instant.parse("2026-01-15T09:00:00Z"))
        seedPaidOrder(h.orders, storeId, 20.0, Instant.parse("2026-01-15T18:00:00Z"))
        seedPaidOrder(h.orders, storeId, 999.0, Instant.parse("2026-01-14T23:00:00Z")) // previous day - excluded
        seedPaidOrder(h.orders, storeId, 999.0, Instant.parse("2026-01-16T00:00:00Z")) // next day - excluded

        val run = h.reporting.runPipeline(ReportPeriod.DAILY, storeIds = listOf(storeId))

        assertEquals(PipelineRunStatus.SUCCESS, run.status)
        val sales = h.reporting.getSalesAggregate(storeId, ReportPeriod.DAILY, run.periodStart)
        assertNotNull(sales)
        assertEquals(2, sales.orderCount)
        assertEquals(30.0, sales.grossSales)
        assertEquals(30.0, sales.netSales)
    }

    @Test
    fun `a refunded order reduces netSales but not grossSales`() {
        val h = newHarness(now = Instant.parse("2026-01-15T12:00:00Z"))
        val storeId = seedStore(h.stores)
        val orderId = seedPaidOrder(h.orders, storeId, 10.0, Instant.parse("2026-01-15T09:00:00Z"))
        val order = h.orders.getOrder(orderId)!!
        val refundResult = h.orders.refund(orderId, "refund-1", "MANUAL", listOf(RefundLineItemInput(order.items.single().id, 1)), "customer request", "manager-1")
        assertIs<com.beettechnologies.posly.cart.RefundResult.Success>(refundResult)

        val run = h.reporting.runPipeline(ReportPeriod.DAILY, storeIds = listOf(storeId))
        val sales = h.reporting.getSalesAggregate(storeId, ReportPeriod.DAILY, run.periodStart)!!

        assertEquals(10.0, sales.grossSales)
        assertEquals(10.0, sales.refundsTotal)
        assertEquals(0.0, sales.netSales)
    }

    @Test
    fun `a WEEKLY pipeline run spans Monday through the following Monday`() {
        // 2026-01-15 is a Thursday; the containing ISO week is Mon 2026-01-12 to Mon 2026-01-19.
        val h = newHarness(now = Instant.parse("2026-01-15T12:00:00Z"))
        val storeId = seedStore(h.stores)
        seedPaidOrder(h.orders, storeId, 10.0, Instant.parse("2026-01-12T00:00:00Z")) // Monday start - included
        seedPaidOrder(h.orders, storeId, 15.0, Instant.parse("2026-01-18T23:59:59Z")) // Sunday - included
        seedPaidOrder(h.orders, storeId, 999.0, Instant.parse("2026-01-11T23:59:59Z")) // prior Sunday - excluded
        seedPaidOrder(h.orders, storeId, 999.0, Instant.parse("2026-01-19T00:00:00Z")) // next Monday - excluded

        val run = h.reporting.runPipeline(ReportPeriod.WEEKLY, storeIds = listOf(storeId))
        val sales = h.reporting.getSalesAggregate(storeId, ReportPeriod.WEEKLY, run.periodStart)!!

        assertEquals(2, sales.orderCount)
        assertEquals(25.0, sales.grossSales)
        assertEquals(Instant.parse("2026-01-12T00:00:00Z"), run.periodStart)
        assertEquals(Instant.parse("2026-01-19T00:00:00Z"), run.periodEnd)
    }

    @Test
    fun `a MONTHLY pipeline run spans the first through the last day of the month`() {
        val h = newHarness(now = Instant.parse("2026-01-15T12:00:00Z"))
        val storeId = seedStore(h.stores)
        seedPaidOrder(h.orders, storeId, 10.0, Instant.parse("2026-01-01T00:00:00Z"))
        seedPaidOrder(h.orders, storeId, 15.0, Instant.parse("2026-01-31T23:59:59Z"))
        seedPaidOrder(h.orders, storeId, 999.0, Instant.parse("2025-12-31T23:59:59Z"))
        seedPaidOrder(h.orders, storeId, 999.0, Instant.parse("2026-02-01T00:00:00Z"))

        val run = h.reporting.runPipeline(ReportPeriod.MONTHLY, storeIds = listOf(storeId))
        val sales = h.reporting.getSalesAggregate(storeId, ReportPeriod.MONTHLY, run.periodStart)!!

        assertEquals(2, sales.orderCount)
        assertEquals(25.0, sales.grossSales)
    }

    @Test
    fun `runPipeline with no explicit storeIds covers every known store`() {
        val h = newHarness()
        val store1 = seedStore(h.stores, "Downtown")
        val store2 = seedStore(h.stores, "Uptown")
        seedPaidOrder(h.orders, store1, 10.0, h.clock.instant)
        seedPaidOrder(h.orders, store2, 20.0, h.clock.instant)

        val run = h.reporting.runPipeline(ReportPeriod.DAILY)

        assertEquals(setOf(store1, store2), run.storeIds.toSet())
        assertEquals(10.0, h.reporting.getSalesAggregate(store1, ReportPeriod.DAILY, run.periodStart)!!.grossSales)
        assertEquals(20.0, h.reporting.getSalesAggregate(store2, ReportPeriod.DAILY, run.periodStart)!!.grossSales)
    }

    // -------------------------------------------------------------------------
    // Staff aggregate
    // -------------------------------------------------------------------------

    @Test
    fun `staff aggregate counts shifts, distinct cashiers, and total variance within the period`() {
        val h = newHarness(now = Instant.parse("2026-01-15T09:00:00Z"))
        val storeId = seedStore(h.stores)

        val shift1 = assertIs<OpenShiftResult.Success>(h.shifts.openShift(storeId, "cashier-1", 100.0)).shift
        val closeResult1 = h.shifts.closeShift(shift1.id, closingCount = 105.0, note = "over by five", closedBy = "cashier-1", closedByIsManagerOrAdmin = false)
        assertIs<CloseShiftResult.Success>(closeResult1)

        h.clock.instant = Instant.parse("2026-01-15T14:00:00Z")
        val shift2 = assertIs<OpenShiftResult.Success>(h.shifts.openShift(storeId, "cashier-2", 50.0)).shift
        val closeResult2 = h.shifts.closeShift(shift2.id, closingCount = 50.0, note = null, closedBy = "manager-1", closedByIsManagerOrAdmin = true)
        assertIs<CloseShiftResult.Success>(closeResult2)

        val run = h.reporting.runPipeline(ReportPeriod.DAILY, storeIds = listOf(storeId))
        val staff = h.reporting.getStaffAggregate(storeId, ReportPeriod.DAILY, run.periodStart)!!

        assertEquals(2, staff.shiftsWorked)
        assertEquals(2, staff.distinctCashiers)
        assertEquals(5.0, staff.totalCashVariance)
        assertEquals(1, staff.shiftsWithNote)
    }

    // -------------------------------------------------------------------------
    // Inventory aggregate (StockCountService stamps real wall-clock time, so this uses a
    // real-time harness rather than the fixed TestClock used elsewhere in this file).
    // -------------------------------------------------------------------------

    @Test
    fun `inventory aggregate summarizes stock counts performed within the period`() {
        val products = ProductService()
        val stores = StoreService(TaxProfileService())
        val orders = OrderService()
        val shifts = ShiftService(stores, orders)
        val inventory = InventoryService(products, stores)
        val stockCounts = StockCountService(inventory, products, stores)
        val reporting = ReportingService(orders, stores, stockCounts, shifts)

        val storeId = seedStore(stores)
        val productId = (products.createProduct(CreateProductRequest(sku = "SKU-1", name = "Widget", price = 9.99, taxCategory = "STANDARD")) as ProductResult.Created).product.id
        inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        assertIs<SubmitStockCountResult.Success>(stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, 8)), countedBy = "manager-1"))

        val run = reporting.runPipeline(ReportPeriod.DAILY, storeIds = listOf(storeId))
        val inventoryAggregate = reporting.getInventoryAggregate(storeId, ReportPeriod.DAILY, run.periodStart)!!

        assertEquals(1, inventoryAggregate.stockCountsPerformed)
        assertEquals(2, inventoryAggregate.totalVarianceUnits)
        assertEquals(1, inventoryAggregate.shortageCount)
        assertEquals(0, inventoryAggregate.overageCount)
    }

    // -------------------------------------------------------------------------
    // Backfill
    // -------------------------------------------------------------------------

    @Test
    fun `backfill runs the pipeline once per period across the requested range`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        val runs = h.reporting.backfill(
            ReportPeriod.DAILY,
            from = Instant.parse("2026-01-01T00:00:00Z"),
            to = Instant.parse("2026-01-04T00:00:00Z"),
            storeIds = listOf(storeId)
        )

        assertEquals(3, runs.size)
        assertEquals(
            listOf(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-03T00:00:00Z")),
            runs.map { it.periodStart }
        )
        assertTrue(runs.all { it.status == PipelineRunStatus.SUCCESS })
        assertEquals(3, h.reporting.listPipelineRuns().size)
    }

    // -------------------------------------------------------------------------
    // Realtime cache
    // -------------------------------------------------------------------------

    @Test
    fun `getRealtimeSales caches within the TTL and recomputes after it lapses`() {
        val h = newHarness()
        val reporting = ReportingService(h.orders, h.stores, h.stockCounts, h.shifts, nowProvider = { h.clock.instant }, realtimeCacheTtlMillis = 1_000L)
        val storeId = seedStore(h.stores)
        seedPaidOrder(h.orders, storeId, 10.0, h.clock.instant)
        h.clock.instant = h.clock.instant.plusMillis(1) // the realtime window is [start, now) - move past the order's exact checkedOutAt

        val first = reporting.getRealtimeSales(storeId)
        assertEquals(10.0, first.grossSales)

        // A new order lands but the cache is still fresh - the cached snapshot is returned unchanged.
        seedPaidOrder(h.orders, storeId, 20.0, h.clock.instant)
        h.clock.instant = h.clock.instant.plusMillis(1)
        val stillCached = reporting.getRealtimeSales(storeId)
        assertEquals(10.0, stillCached.grossSales)

        // Advance past the TTL - the next call recomputes and picks up the new order.
        h.clock.instant = h.clock.instant.plusSeconds(2)
        val recomputed = reporting.getRealtimeSales(storeId)
        assertEquals(30.0, recomputed.grossSales)
    }

    @Test
    fun `an order event for a store invalidates its realtime cache immediately`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        seedPaidOrder(h.orders, storeId, 10.0, h.clock.instant)
        h.clock.instant = h.clock.instant.plusMillis(1) // the realtime window is [start, now) - move past the order's exact checkedOutAt
        val first = h.reporting.getRealtimeSales(storeId)
        assertEquals(10.0, first.grossSales)

        val secondOrderId = seedPaidOrder(h.orders, storeId, 20.0, h.clock.instant)
        h.clock.instant = h.clock.instant.plusMillis(1) // move past the second order's exact checkedOutAt too
        // seedPaidOrder already fired PAYMENT_CONFIRMED to any registered listener, but this
        // harness's ReportingService is not registered as a listener - invoke onEvent directly to
        // simulate what Application.kt's CompositeOrderEventListener would have dispatched.
        h.reporting.onEvent(h.orders.getOrder(secondOrderId)!!, OrderEventType.PAYMENT_CONFIRMED)

        val afterInvalidation = h.reporting.getRealtimeSales(storeId)
        assertEquals(30.0, afterInvalidation.grossSales)
    }

    // -------------------------------------------------------------------------
    // Pipeline run history
    // -------------------------------------------------------------------------

    @Test
    fun `listPipelineRuns returns every run, most recent first`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val run1 = h.reporting.runPipeline(ReportPeriod.DAILY, storeIds = listOf(storeId))
        h.clock.instant = h.clock.instant.plusSeconds(60)
        val run2 = h.reporting.runPipeline(ReportPeriod.DAILY, storeIds = listOf(storeId))

        val runs = h.reporting.listPipelineRuns()

        assertEquals(listOf(run2.id, run1.id), runs.map { it.id })
        assertEquals(run1, h.reporting.getPipelineRun(run1.id))
    }
}
