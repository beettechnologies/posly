package com.beettechnologies.posly.finance

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.TaxBreakdownLine
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.shifts.Shift
import com.beettechnologies.posly.shifts.ShiftStatus
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.io.StringReader
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinanceReportBuilderTest {

    private fun seedOrder(
        orders: OrderService,
        storeId: String,
        amount: Double,
        checkedOutAt: Instant,
        taxBreakdown: List<TaxBreakdownLine> = emptyList(),
        totalTax: Double = 0.0
    ): String {
        val cart = Cart(
            id = "cart-${(0..10_000_000).random()}",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = amount, taxCategory = TaxCategory.STANDARD)),
            createdAt = checkedOutAt,
            updatedAt = checkedOutAt
        )
        val totals = CartTotals(amount, 0.0, 0.0, amount, taxBreakdown, totalTax, amount + totalTax)
        val order = orders.createOrder(cart, totals, "key-${(0..10_000_000).random()}", checkedOutAt = checkedOutAt)
        orders.confirmPayment(order.id, "CASH", amount + totalTax, null, "cashier-1")
        return order.id
    }

    // -------------------------------------------------------------------------
    // Tax table
    // -------------------------------------------------------------------------

    @Test
    fun `buildTaxTable groups tax amounts by rate name across orders`() {
        val orders = OrderService()
        val storeId = "store-1"
        seedOrder(orders, storeId, 100.0, Instant.parse("2026-01-15T09:00:00Z"), listOf(TaxBreakdownLine("VAT", 20.0, 20.0)), 20.0)
        seedOrder(orders, storeId, 50.0, Instant.parse("2026-01-15T10:00:00Z"), listOf(TaxBreakdownLine("VAT", 20.0, 10.0)), 10.0)
        seedOrder(orders, storeId, 200.0, Instant.parse("2026-01-15T11:00:00Z"), listOf(TaxBreakdownLine("City Tax", 2.0, 4.0)), 4.0)

        val table = FinanceReportBuilder.buildTaxTable(orders.listOrders(storeId, Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z")))

        assertEquals(listOf("Tax Rate", "Rate %", "Orders", "Tax Collected"), table.headers)
        val vatRow = table.rows.first { it[0] == "City Tax" }
        assertEquals(listOf("City Tax", "2.0%", "1", "4.00"), vatRow)
        val cityRow = table.rows.first { it[0] == "VAT" }
        assertEquals(listOf("VAT", "20.0%", "2", "30.00"), cityRow)
        val totalRow = table.rows.last()
        assertEquals("TOTAL", totalRow[0])
        assertEquals("34.00", totalRow[3])
    }

    // -------------------------------------------------------------------------
    // Sales table
    // -------------------------------------------------------------------------

    @Test
    fun `buildSalesTable buckets by store-local calendar day, not UTC day`() {
        val orders = OrderService()
        val storeId = "store-1"
        // 23:30 UTC on Jan 15 is already Jan 15 in New York (UTC-5), so both land on the same local day.
        seedOrder(orders, storeId, 10.0, Instant.parse("2026-01-15T14:00:00Z"))
        // 02:30 UTC on Jan 16 is still Jan 15 local time in New York - must bucket with the above, not split into a Jan 16 row.
        seedOrder(orders, storeId, 20.0, Instant.parse("2026-01-16T02:30:00Z"))

        val table = FinanceReportBuilder.buildSalesTable(
            orders.listOrders(storeId, Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-17T00:00:00Z")),
            timezone = "America/New_York"
        )

        val dayRows = table.rows.dropLast(1)
        assertEquals(1, dayRows.size, "both orders fall on the same New York local day")
        assertEquals("2026-01-15", dayRows.single()[0])
        assertEquals("2", dayRows.single()[1])
        assertEquals("30.00", dayRows.single()[3])
    }

    @Test
    fun `buildSalesTable splits orders across UTC-adjacent local days differently per timezone`() {
        val orders = OrderService()
        val storeId = "store-1"
        seedOrder(orders, storeId, 10.0, Instant.parse("2026-01-15T14:00:00Z"))
        seedOrder(orders, storeId, 20.0, Instant.parse("2026-01-16T02:30:00Z"))

        val utcTable = FinanceReportBuilder.buildSalesTable(
            orders.listOrders(storeId, Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-17T00:00:00Z")),
            timezone = "UTC"
        )

        val dayRows = utcTable.rows.dropLast(1)
        assertEquals(2, dayRows.size, "in UTC these two orders fall on different calendar days")
    }

    // -------------------------------------------------------------------------
    // Reconciliation table
    // -------------------------------------------------------------------------

    @Test
    fun `buildReconciliationTable classifies variance cause and totals overage-shortage counts`() {
        val shifts = listOf(
            Shift(
                storeId = "store-1", cashierId = "cashier-1", openingFloat = 100.0,
                openedAt = Instant.parse("2026-01-15T09:00:00Z"), status = ShiftStatus.CLOSED,
                closingCount = 90.0, expectedCash = 100.0, variance = -10.0,
                closedAt = Instant.parse("2026-01-15T17:00:00Z")
            ),
            Shift(
                storeId = "store-1", cashierId = "cashier-2", openingFloat = 100.0,
                openedAt = Instant.parse("2026-01-15T09:00:00Z"), status = ShiftStatus.CLOSED,
                closingCount = 115.0, expectedCash = 100.0, variance = 15.0,
                closedAt = Instant.parse("2026-01-15T18:00:00Z")
            )
        )

        val table = FinanceReportBuilder.buildReconciliationTable(shifts, timezone = "UTC")

        assertEquals(2, table.rows.size - 1)
        val shortRow = table.rows.first { it[1] == "cashier-1" }
        assertEquals("SHORT", shortRow.last())
        val overRow = table.rows.first { it[1] == "cashier-2" }
        assertEquals("OVER", overRow.last())
        val totalRow = table.rows.last()
        assertEquals("1 over / 1 short", totalRow.last())
        assertEquals("5.00", totalRow[7])
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Test
    fun `renderCsv produces a parseable CSV with the correct header and row values`() {
        val table = ReportTable(title = "Test", headers = listOf("A", "B"), rows = listOf(listOf("1", "2"), listOf("3", "4")))

        val bytes = FinanceReportBuilder.renderCsv(table)
        val text = bytes.toString(Charsets.UTF_8)
        val parsed = CSVParser.parse(StringReader(text), CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())
        val records = parsed.records

        assertEquals(listOf("A", "B"), parsed.headerNames)
        assertEquals(2, records.size)
        assertEquals("1", records[0].get("A"))
        assertEquals("4", records[1].get("B"))
    }

    @Test
    fun `renderPdf produces non-empty PDF bytes starting with the PDF magic header`() {
        val table = ReportTable(title = "Test", headers = listOf("A", "B"), rows = listOf(listOf("1", "2")))

        val bytes = FinanceReportBuilder.renderPdf(table, Instant.parse("2026-01-15T12:00:00Z"), "UTC")

        assertTrue(bytes.isNotEmpty())
        assertEquals("%PDF", bytes.decodeToString(0, 4))
    }

    @Test
    fun `renderPdf paginates when the table has more rows than fit on one page`() {
        val manyRows = (1..200).map { listOf(it.toString(), "value-$it") }
        val table = ReportTable(title = "Big Report", headers = listOf("Row", "Value"), rows = manyRows)

        val bytes = FinanceReportBuilder.renderPdf(table, Instant.parse("2026-01-15T12:00:00Z"), "UTC")

        org.apache.pdfbox.Loader.loadPDF(bytes).use { document ->
            assertTrue(document.numberOfPages > 1, "200 rows should not fit on a single page")
        }
    }
}
