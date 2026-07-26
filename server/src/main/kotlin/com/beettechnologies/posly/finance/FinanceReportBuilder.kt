package com.beettechnologies.posly.finance

import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.shifts.Shift
import com.beettechnologies.posly.shifts.ShiftVarianceCause
import com.beettechnologies.posly.shifts.ShiftVarianceEngine
import com.beettechnologies.posly.stores.StoreTimeZone
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the tabular content for each finance report type, then renders that content to CSV or
 * PDF bytes. Pure and stateless - given the same orders/shifts and timezone, always produces the
 * same table. Mirrors [com.beettechnologies.posly.receipts.ReceiptRenderer]'s line-based approach,
 * generalized from one fixed receipt layout to an arbitrary headers+rows table.
 */
object FinanceReportBuilder {

    fun buildTaxTable(orders: List<Order>): ReportTable {
        data class RateKey(val name: String, val ratePercent: Double)
        val byRate = linkedMapOf<RateKey, MutableList<Double>>()
        orders.forEach { order ->
            order.totals.taxBreakdown.forEach { line ->
                val key = RateKey(line.name, line.ratePercent)
                byRate.getOrPut(key) { mutableListOf() }.add(line.amount)
            }
        }
        val rows = byRate.entries
            .sortedBy { it.key.name }
            .map { (key, amounts) -> listOf(key.name, "${key.ratePercent}%", amounts.size.toString(), money(amounts.sum())) }
        val totalTax = byRate.values.sumOf { it.sum() }
        return ReportTable(
            title = "Tax Report",
            headers = listOf("Tax Rate", "Rate %", "Orders", "Tax Collected"),
            rows = rows + listOf(listOf("TOTAL", "", orders.size.toString(), money(totalTax)))
        )
    }

    fun buildSalesTable(orders: List<Order>, timezone: String): ReportTable {
        val byDay = orders.groupBy { StoreTimeZone.toLocalDate(it.checkedOutAt, timezone) }
        val rows = byDay.entries
            .sortedBy { it.key }
            .map { (date, dayOrders) ->
                listOf(
                    date.toString(),
                    dayOrders.size.toString(),
                    dayOrders.sumOf { order -> order.items.sumOf { it.quantity } }.toString(),
                    money(dayOrders.sumOf { it.totals.subtotal }),
                    money(dayOrders.sumOf { it.totals.itemDiscountTotal + it.totals.cartDiscountAmount }),
                    money(dayOrders.sumOf { it.totals.totalTax }),
                    money(dayOrders.sumOf { it.amountRefunded }),
                    money(dayOrders.sumOf { it.totals.total })
                )
            }
        val totalRow = listOf(
            "TOTAL",
            orders.size.toString(),
            orders.sumOf { order -> order.items.sumOf { it.quantity } }.toString(),
            money(orders.sumOf { it.totals.subtotal }),
            money(orders.sumOf { it.totals.itemDiscountTotal + it.totals.cartDiscountAmount }),
            money(orders.sumOf { it.totals.totalTax }),
            money(orders.sumOf { it.amountRefunded }),
            money(orders.sumOf { it.totals.total })
        )
        return ReportTable(
            title = "Sales Report",
            headers = listOf("Date", "Orders", "Items Sold", "Gross Sales", "Discounts", "Tax Collected", "Refunds", "Net Sales"),
            rows = rows + listOf(totalRow)
        )
    }

    fun buildReconciliationTable(shifts: List<Shift>, timezone: String): ReportTable {
        val zone = ZoneId.of(timezone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val rows = shifts.sortedBy { it.closedAt }.map { shift ->
            val variance = shift.variance ?: 0.0
            listOf(
                shift.id,
                shift.cashierId ?: "-",
                formatter.format(shift.openedAt),
                shift.closedAt?.let { formatter.format(it) } ?: "-",
                money(shift.openingFloat),
                shift.closingCount?.let { money(it) } ?: "-",
                shift.expectedCash?.let { money(it) } ?: "-",
                money(variance),
                ShiftVarianceEngine.causeFor(variance).name
            )
        }
        val totalVariance = shifts.sumOf { it.variance ?: 0.0 }
        val overageCount = shifts.count { ShiftVarianceEngine.causeFor(it.variance ?: 0.0) == ShiftVarianceCause.OVER }
        val shortageCount = shifts.count { ShiftVarianceEngine.causeFor(it.variance ?: 0.0) == ShiftVarianceCause.SHORT }
        val totalRow = listOf(
            "TOTAL (${shifts.size} shifts)", "", "", "", "", "", "",
            money(totalVariance), "$overageCount over / $shortageCount short"
        )
        return ReportTable(
            title = "Reconciliation Report",
            headers = listOf("Shift ID", "Cashier", "Opened At", "Closed At", "Opening Float", "Closing Count", "Expected Cash", "Variance", "Cause"),
            rows = rows + listOf(totalRow)
        )
    }

    fun renderCsv(table: ReportTable): ByteArray {
        val output = ByteArrayOutputStream()
        OutputStreamWriter(output).use { writer ->
            val format = CSVFormat.DEFAULT.builder().setHeader(*table.headers.toTypedArray()).build()
            CSVPrinter(writer, format).use { printer ->
                table.rows.forEach { row -> printer.printRecord(row) }
            }
        }
        return output.toByteArray()
    }

    private const val PDF_FONT_SIZE = 9f
    private const val PDF_LEADING = 13f
    private const val PDF_MARGIN = 40f
    private val PDF_PAGE = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)

    fun renderPdf(table: ReportTable, generatedAt: Instant, timezone: String): ByteArray {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of(timezone))
        val columnWidths = table.headers.indices.map { col ->
            val headerLen = table.headers[col].length
            val maxRowLen = table.rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0
            maxOf(headerLen, maxRowLen) + 2
        }
        fun formatRow(cells: List<String>): String =
            cells.indices.joinToString("") { i -> cells[i].padEnd(columnWidths.getOrElse(i) { 10 }) }

        val headerLine = formatRow(table.headers)
        val separatorLine = "-".repeat(headerLine.length)
        val dataLines = table.rows.map { formatRow(it) }

        val linesPerPage = ((PDF_PAGE.height - 2 * PDF_MARGIN - 40f) / PDF_LEADING).toInt().coerceAtLeast(10)
        val pages = dataLines.chunked(linesPerPage).ifEmpty { listOf(emptyList()) }

        PDDocument().use { document ->
            val font = PDType1Font(Standard14Fonts.FontName.COURIER)
            pages.forEachIndexed { pageIndex, pageLines ->
                val page = PDPage(PDF_PAGE)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, PDF_FONT_SIZE)
                    stream.newLineAtOffset(PDF_MARGIN, PDF_PAGE.height - PDF_MARGIN)
                    if (pageIndex == 0) {
                        stream.showText(table.title)
                        stream.newLineAtOffset(0f, -PDF_LEADING)
                        stream.showText("Generated at: ${formatter.format(generatedAt)} ($timezone)")
                        stream.newLineAtOffset(0f, -PDF_LEADING * 1.5f)
                    }
                    stream.showText(headerLine)
                    stream.newLineAtOffset(0f, -PDF_LEADING)
                    stream.showText(separatorLine)
                    stream.newLineAtOffset(0f, -PDF_LEADING)
                    pageLines.forEach { line ->
                        stream.showText(line)
                        stream.newLineAtOffset(0f, -PDF_LEADING)
                    }
                    stream.endText()
                }
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        }
    }

    private fun money(amount: Double): String = String.format("%.2f", amount)
}
