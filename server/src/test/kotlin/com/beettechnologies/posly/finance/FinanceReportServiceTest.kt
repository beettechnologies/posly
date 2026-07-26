package com.beettechnologies.posly.finance

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.email.EmailGateway
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.shifts.ShiftService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FinanceReportServiceTest {

    /** A settable "now" so schedule due-checks and period-boundary math are exercised deterministically. */
    private class TestClock(var instant: Instant)

    private class RecordingEmailGateway(private val bounceRecipients: Set<String> = emptySet()) : EmailGateway {
        val sentTo = mutableListOf<Pair<String, String>>()

        override suspend fun sendReceipt(recipient: String, subject: String, pdfBytes: ByteArray): String {
            if (recipient in bounceRecipients) throw GatewayException("Simulated bounce for $recipient")
            sentTo += recipient to subject
            return "msg-${sentTo.size}"
        }

        override suspend fun sendPlainText(recipient: String, subject: String, body: String): String = "msg"
    }

    private data class Harness(
        val stores: StoreService,
        val orders: OrderService,
        val shifts: ShiftService,
        val gateway: RecordingEmailGateway,
        val finance: FinanceReportService,
        val clock: TestClock
    )

    private fun newHarness(now: Instant = Instant.parse("2026-01-15T12:00:00Z"), gateway: RecordingEmailGateway = RecordingEmailGateway()): Harness {
        val clock = TestClock(now)
        val stores = StoreService(TaxProfileService())
        val orders = OrderService(nowProvider = { clock.instant })
        val shifts = ShiftService(stores, orders, nowProvider = { clock.instant })
        val finance = FinanceReportService(orders, shifts, stores, gateway, nowProvider = { clock.instant })
        return Harness(stores, orders, shifts, gateway, finance, clock)
    }

    private fun seedStore(stores: StoreService, timezone: String = "America/New_York"): String {
        val result = stores.createStore(
            name = "Downtown",
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = timezone,
            currency = "USD",
            taxProfileId = null
        )
        return (result as CreateStoreResult.Created).store.id
    }

    private fun seedOrder(orders: OrderService, storeId: String, amount: Double, checkedOutAt: Instant): String {
        val cart = Cart(
            id = "cart-${(0..10_000_000).random()}",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = amount, taxCategory = TaxCategory.STANDARD)),
            createdAt = checkedOutAt,
            updatedAt = checkedOutAt
        )
        val totals = CartTotals(amount, 0.0, 0.0, amount, emptyList(), 0.0, amount)
        val order = orders.createOrder(cart, totals, "key-${(0..10_000_000).random()}", checkedOutAt = checkedOutAt)
        orders.confirmPayment(order.id, "CASH", amount, null, "cashier-1")
        return order.id
    }

    // -------------------------------------------------------------------------
    // generateReport
    // -------------------------------------------------------------------------

    @Test
    fun `generateReport returns CSV bytes for a valid sales request`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        seedOrder(h.orders, storeId, 50.0, Instant.parse("2026-01-15T09:00:00Z"))

        val result = h.finance.generateReport(
            FinanceReportType.SALES, FinanceReportFormat.CSV, storeId,
            Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z"), "America/New_York"
        )

        val success = assertIs<GenerateReportResult.Success>(result)
        assertTrue(success.report.bytes.isNotEmpty())
        assertEquals("text/csv", success.report.contentType)
        assertTrue(success.report.fileName.startsWith("sales-report_"))
    }

    @Test
    fun `generateReport rejects an unknown store`() {
        val h = newHarness()
        val result = h.finance.generateReport(
            FinanceReportType.SALES, FinanceReportFormat.CSV, "no-such-store",
            Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z"), "UTC"
        )
        assertEquals(GenerateReportResult.StoreNotFound, result)
    }

    @Test
    fun `generateReport rejects an invalid timezone`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val result = h.finance.generateReport(
            FinanceReportType.TAX, FinanceReportFormat.PDF, storeId,
            Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z"), "Not/AZone"
        )
        assertEquals(GenerateReportResult.InvalidTimezone("Not/AZone"), result)
    }

    @Test
    fun `generateReport rejects a range where from is not before to`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val result = h.finance.generateReport(
            FinanceReportType.TAX, FinanceReportFormat.PDF, storeId,
            Instant.parse("2026-01-16T00:00:00Z"), Instant.parse("2026-01-15T00:00:00Z"), "UTC"
        )
        assertEquals(GenerateReportResult.InvalidRange, result)
    }

    // -------------------------------------------------------------------------
    // createSchedule
    // -------------------------------------------------------------------------

    @Test
    fun `createSchedule computes nextRunAt as the next store-local day boundary for a DAILY frequency`() {
        val h = newHarness(now = Instant.parse("2026-01-15T18:00:00Z"))
        val storeId = seedStore(h.stores, timezone = "America/New_York")

        val result = h.finance.createSchedule(
            storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "America/New_York",
            ScheduleFrequency.DAILY, listOf("finance@example.com"), "admin-1"
        )

        val success = assertIs<CreateScheduleResult.Success>(result)
        // 18:00 UTC on Jan 15 is 13:00 local (New York, UTC-5) - the next local midnight is the start of Jan 16.
        assertEquals(Instant.parse("2026-01-16T05:00:00Z"), success.schedule.nextRunAt)
    }

    @Test
    fun `createSchedule rejects an unknown store, invalid timezone, and malformed recipients`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        assertEquals(
            CreateScheduleResult.StoreNotFound,
            h.finance.createSchedule("no-such-store", FinanceReportType.SALES, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.DAILY, listOf("a@b.com"), null)
        )
        assertEquals(
            CreateScheduleResult.InvalidTimezone("nope"),
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "nope", ScheduleFrequency.DAILY, listOf("a@b.com"), null)
        )
        assertEquals(
            CreateScheduleResult.EmptyRecipients,
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.DAILY, emptyList(), null)
        )
        assertEquals(
            CreateScheduleResult.InvalidRecipient("not-an-email"),
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.DAILY, listOf("not-an-email"), null)
        )
    }

    // -------------------------------------------------------------------------
    // runDueSchedulesNow / delivery
    // -------------------------------------------------------------------------

    @Test
    fun `a due DAILY schedule is generated and emailed to every recipient, then advances to the next day`() = runTest {
        val h = newHarness(now = Instant.parse("2026-01-15T10:00:00Z"))
        val storeId = seedStore(h.stores, timezone = "UTC")
        seedOrder(h.orders, storeId, 40.0, Instant.parse("2026-01-14T15:00:00Z"))

        val created = assertIs<CreateScheduleResult.Success>(
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.PDF, "UTC", ScheduleFrequency.DAILY, listOf("owner@example.com", "cfo@example.com"), "admin-1")
        )
        val originalNextRunAt = created.schedule.nextRunAt
        assertEquals(Instant.parse("2026-01-16T00:00:00Z"), originalNextRunAt)

        // Not due yet.
        h.finance.runDueSchedulesNow()
        assertTrue(h.gateway.sentTo.isEmpty())

        // Advance past the boundary and re-check.
        h.clock.instant = originalNextRunAt.plusSeconds(1)
        h.finance.runDueSchedulesNow()

        assertEquals(2, h.gateway.sentTo.size)
        assertEquals(setOf("owner@example.com", "cfo@example.com"), h.gateway.sentTo.map { it.first }.toSet())

        val runs = h.finance.listRuns(created.schedule.id)
        assertEquals(1, runs.size)
        assertEquals(ScheduledReportRunStatus.SUCCESS, runs.single().status)
        assertEquals(Instant.parse("2026-01-15T00:00:00Z"), runs.single().periodStart)
        assertEquals(originalNextRunAt, runs.single().periodEnd)

        val updated = h.finance.getSchedule(created.schedule.id)!!
        assertEquals(Instant.parse("2026-01-17T00:00:00Z"), updated.nextRunAt)
        assertEquals(ScheduledReportRunStatus.SUCCESS.name, updated.lastRunStatus)
    }

    @Test
    fun `a bounced recipient is recorded as a partial failure without blocking the other recipients`() = runTest {
        val gateway = RecordingEmailGateway(bounceRecipients = setOf("bounced@example.com"))
        val h = newHarness(now = Instant.parse("2026-01-15T10:00:00Z"), gateway = gateway)
        val storeId = seedStore(h.stores, timezone = "UTC")

        val created = assertIs<CreateScheduleResult.Success>(
            h.finance.createSchedule(storeId, FinanceReportType.TAX, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.DAILY, listOf("bounced@example.com", "good@example.com"), null)
        )
        h.clock.instant = created.schedule.nextRunAt.plusSeconds(1)

        h.finance.runDueSchedulesNow()

        val run = h.finance.listRuns(created.schedule.id).single()
        assertEquals(ScheduledReportRunStatus.PARTIAL_FAILURE, run.status)
        assertEquals(listOf("good@example.com"), run.deliveredTo)
        assertEquals(listOf("bounced@example.com"), run.failedRecipients)
    }

    @Test
    fun `a deleted schedule is skipped by the due-check`() = runTest {
        val h = newHarness(now = Instant.parse("2026-01-15T10:00:00Z"))
        val storeId = seedStore(h.stores, timezone = "UTC")
        val created = assertIs<CreateScheduleResult.Success>(
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.DAILY, listOf("a@b.com"), null)
        )
        h.finance.deleteSchedule(created.schedule.id)
        h.clock.instant = created.schedule.nextRunAt.plusSeconds(1)

        h.finance.runDueSchedulesNow()

        assertTrue(h.gateway.sentTo.isEmpty())
    }

    @Test
    fun `runScheduleNow delivers immediately without waiting for the schedule's own due time`() = runTest {
        val h = newHarness(now = Instant.parse("2026-01-15T10:00:00Z"))
        val storeId = seedStore(h.stores, timezone = "UTC")
        seedOrder(h.orders, storeId, 25.0, Instant.parse("2026-01-14T15:00:00Z"))
        val created = assertIs<CreateScheduleResult.Success>(
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.DAILY, listOf("owner@example.com"), null)
        )

        val result = h.finance.runScheduleNow(created.schedule.id)

        val success = assertIs<RunScheduleResult.Success>(result)
        assertEquals(ScheduledReportRunStatus.SUCCESS, success.run.status)
        assertEquals(1, h.gateway.sentTo.size)
        // The schedule's own cadence is untouched by an ad-hoc run-now.
        assertEquals(created.schedule.nextRunAt, h.finance.getSchedule(created.schedule.id)!!.nextRunAt)
    }

    @Test
    fun `runScheduleNow on an unknown schedule returns NotFound`() = runTest {
        val h = newHarness()
        val result = h.finance.runScheduleNow("does-not-exist")
        assertEquals(RunScheduleResult.NotFound, result)
    }

    @Test
    fun `deleteSchedule removes it from listSchedules`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)
        val created = assertIs<CreateScheduleResult.Success>(
            h.finance.createSchedule(storeId, FinanceReportType.SALES, FinanceReportFormat.CSV, "UTC", ScheduleFrequency.WEEKLY, listOf("a@b.com"), null)
        )
        assertEquals(1, h.finance.listSchedules(storeId).size)

        assertTrue(h.finance.deleteSchedule(created.schedule.id))

        assertTrue(h.finance.listSchedules(storeId).isEmpty())
        assertTrue(!h.finance.deleteSchedule(created.schedule.id))
    }
}
