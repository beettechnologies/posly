package com.beettechnologies.posly.receipts

import com.beettechnologies.posly.cart.Order
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/** Standard thermal receipt paper width, in characters, for an 80mm printer at 12cpi. */
private const val THERMAL_WIDTH = 32

/**
 * Renders an [Order] as receipt content, shared by both the print flow (fixed-width monospace
 * text formatted for a thermal printer) and the email flow (a genuine one-page PDF for
 * attachment). Pure and stateless - given the same order, always produces the same output.
 */
object ReceiptRenderer {

    fun renderThermalText(order: Order): String {
        val lines = mutableListOf<String>()
        lines += "=".repeat(THERMAL_WIDTH)
        lines += center("RECEIPT")
        lines += "=".repeat(THERMAL_WIDTH)
        order.items.forEach { item ->
            lines += twoColumn("${item.quantity}x ${item.productName}", money(item.lineTotal))
        }
        lines += "-".repeat(THERMAL_WIDTH)
        lines += twoColumn("Subtotal:", money(order.totals.subtotal))
        val discountTotal = order.totals.cartDiscountAmount + order.totals.itemDiscountTotal
        if (discountTotal > 0.0) {
            lines += twoColumn("Discount:", "-${money(discountTotal)}")
        }
        order.totals.taxBreakdown.forEach { line ->
            lines += twoColumn("${line.name} (${line.ratePercent}%):", money(line.amount))
        }
        lines += twoColumn("Tax:", money(order.totals.totalTax))
        lines += "-".repeat(THERMAL_WIDTH)
        lines += twoColumn("TOTAL:", money(order.totals.total))
        lines += "-".repeat(THERMAL_WIDTH)
        order.payments.forEach { payment ->
            lines += tenderLine(payment.method, payment.amount, payment.maskedCardNumber, payment.reference)
        }
        lines += "=".repeat(THERMAL_WIDTH)
        lines += center("Thank you!")
        lines += "=".repeat(THERMAL_WIDTH)
        return lines.joinToString("\n")
    }

    fun renderPdf(order: Order): ByteArray {
        val lines = mutableListOf<String>()
        lines += "Receipt"
        lines += ""
        order.items.forEach { item -> lines += "${item.quantity}x ${item.productName} - ${money(item.lineTotal)}" }
        lines += ""
        lines += "Subtotal: ${money(order.totals.subtotal)}"
        val discountTotal = order.totals.cartDiscountAmount + order.totals.itemDiscountTotal
        if (discountTotal > 0.0) lines += "Discount: -${money(discountTotal)}"
        order.totals.taxBreakdown.forEach { line -> lines += "${line.name} (${line.ratePercent}%): ${money(line.amount)}" }
        lines += "Tax: ${money(order.totals.totalTax)}"
        lines += "Total: ${money(order.totals.total)}"
        lines += ""
        lines += "Payment"
        order.payments.forEach { payment ->
            lines += tenderLine(payment.method, payment.amount, payment.maskedCardNumber, payment.reference)
        }

        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val fontSize = 12f
            val leading = 16f
            val y = page.mediaBox.height - 60f
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(font, fontSize)
                stream.newLineAtOffset(60f, y)
                lines.forEach { line ->
                    stream.showText(line)
                    stream.newLineAtOffset(0f, -leading)
                }
                stream.endText()
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        }
    }

    private fun tenderLine(method: String, amount: Double, maskedCardNumber: String?, reference: String?): String {
        val cardDetail = maskedCardNumber?.let { masked -> " - $masked, auth $reference" }.orEmpty()
        return "$method: ${money(amount)}$cardDetail"
    }

    private fun money(amount: Double): String = "$$amount"

    private fun center(text: String): String {
        val padding = ((THERMAL_WIDTH - text.length) / 2).coerceAtLeast(0)
        return " ".repeat(padding) + text
    }

    private fun twoColumn(label: String, value: String): String {
        val space = (THERMAL_WIDTH - label.length - value.length).coerceAtLeast(1)
        return label + " ".repeat(space) + value
    }
}
