package com.beettechnologies.posly.receipts

import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.format.CurrencyFormat
import com.beettechnologies.posly.stores.Store
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Standard thermal receipt paper width, in characters, for an 80mm printer at 12cpi. */
private const val THERMAL_WIDTH = 32

private val log = LoggerFactory.getLogger("ReceiptRenderer")

/**
 * Renders an [Order] as receipt content, shared by both the print flow (fixed-width monospace
 * text formatted for a thermal printer) and the email flow (a genuine one-page PDF for
 * attachment). Pure and stateless - given the same order/store, always produces the same output.
 *
 * Money amounts format using the order's own snapshotted [Order.currency] (never the store's
 * *current* currency setting - see [com.beettechnologies.posly.cart.CartModels.Order]'s doc
 * comment) combined with the store's *current* [Store.locale] for formatting conventions; dates
 * use the store's current [Store.timezone]/[Store.locale].
 *
 * A logo is only embeddable on the PDF path - PDFBox's [PDImageXObject] draws a real bitmap. The
 * thermal path is plain ASCII text with no ESC/POS raster-image command support anywhere in this
 * codebase's `printing` package (a simulated [com.beettechnologies.posly.printing.PrintGateway],
 * not a real hardware driver), so it falls back to printing the store name as a text header - a
 * disclosed, literal scope boundary for the print channel, not a silent downgrade.
 */
object ReceiptRenderer {

    fun renderThermalText(order: Order, store: Store): String {
        val lines = mutableListOf<String>()
        lines += "=".repeat(THERMAL_WIDTH)
        lines += center(store.name.uppercase())
        lines += center(formattedDate(order, store))
        lines += "=".repeat(THERMAL_WIDTH)
        order.items.forEach { item ->
            lines += twoColumn("${item.quantity}x ${item.productName}", money(item.lineTotal, order, store))
        }
        lines += "-".repeat(THERMAL_WIDTH)
        lines += twoColumn("Subtotal:", money(order.totals.subtotal, order, store))
        val discountTotal = order.totals.cartDiscountAmount + order.totals.itemDiscountTotal
        if (discountTotal > 0.0) {
            lines += twoColumn("Discount:", "-${money(discountTotal, order, store)}")
        }
        order.totals.taxBreakdown.forEach { line ->
            lines += twoColumn("${line.name} (${line.ratePercent}%):", money(line.amount, order, store))
        }
        lines += twoColumn("Tax:", money(order.totals.totalTax, order, store))
        lines += "-".repeat(THERMAL_WIDTH)
        lines += twoColumn("TOTAL:", money(order.totals.total, order, store))
        lines += "-".repeat(THERMAL_WIDTH)
        order.payments.forEach { payment ->
            lines += tenderLine(payment.method, payment.amount, payment.maskedCardNumber, payment.reference, order, store)
        }
        lines += "=".repeat(THERMAL_WIDTH)
        lines += center("Thank you!")
        lines += "=".repeat(THERMAL_WIDTH)
        return lines.joinToString("\n")
    }

    fun renderPdf(order: Order, store: Store, logoBytes: ByteArray? = null): ByteArray {
        val lines = mutableListOf<String>()
        lines += store.name
        lines += formattedDate(order, store)
        lines += ""
        order.items.forEach { item -> lines += "${item.quantity}x ${item.productName} - ${money(item.lineTotal, order, store)}" }
        lines += ""
        lines += "Subtotal: ${money(order.totals.subtotal, order, store)}"
        val discountTotal = order.totals.cartDiscountAmount + order.totals.itemDiscountTotal
        if (discountTotal > 0.0) lines += "Discount: -${money(discountTotal, order, store)}"
        order.totals.taxBreakdown.forEach { line -> lines += "${line.name} (${line.ratePercent}%): ${money(line.amount, order, store)}" }
        lines += "Tax: ${money(order.totals.totalTax, order, store)}"
        lines += "Total: ${money(order.totals.total, order, store)}"
        lines += ""
        lines += "Payment"
        order.payments.forEach { payment ->
            lines += tenderLine(payment.method, payment.amount, payment.maskedCardNumber, payment.reference, order, store)
        }

        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val fontSize = 12f
            val leading = 16f
            var textTop = page.mediaBox.height - 60f

            PDPageContentStream(document, page).use { stream ->
                if (logoBytes != null) {
                    val logoHeight = drawLogo(document, stream, page, logoBytes)
                    if (logoHeight != null) textTop -= (logoHeight + 20f)
                }
                stream.beginText()
                stream.setFont(font, fontSize)
                stream.newLineAtOffset(60f, textTop)
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

    /** Returns the height the logo was drawn at (for laying out the text below it), or null if the bytes couldn't be decoded as an image - never throws, so a corrupt/legacy logo blob degrades to no-logo rather than blocking receipt rendering entirely. */
    private fun drawLogo(document: PDDocument, stream: PDPageContentStream, page: PDPage, logoBytes: ByteArray): Float? {
        return runCatching {
            val image = PDImageXObject.createFromByteArray(document, logoBytes, "logo")
            val maxWidth = 100f
            val maxHeight = 50f
            val scale = minOf(maxWidth / image.width, maxHeight / image.height, 1f)
            val width = image.width * scale
            val height = image.height * scale
            val x = 60f
            val y = page.mediaBox.height - 40f - height
            stream.drawImage(image, x, y, width, height)
            height
        }.onFailure { e -> log.warn("Failed to embed store logo in receipt PDF, continuing without it: {}", e.message) }
            .getOrNull()
    }

    private fun tenderLine(method: String, amount: Double, maskedCardNumber: String?, reference: String?, order: Order, store: Store): String {
        val cardDetail = maskedCardNumber?.let { masked -> " - $masked, auth $reference" }.orEmpty()
        return "$method: ${money(amount, order, store)}$cardDetail"
    }

    private fun money(amount: Double, order: Order, store: Store): String =
        CurrencyFormat.format(amount, order.currency, store.locale)

    private fun formattedDate(order: Order, store: Store): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.forLanguageTag(store.locale))
            .withZone(ZoneId.of(store.timezone))
            .format(order.checkedOutAt)

    private fun center(text: String): String {
        val padding = ((THERMAL_WIDTH - text.length) / 2).coerceAtLeast(0)
        return " ".repeat(padding) + text
    }

    private fun twoColumn(label: String, value: String): String {
        val space = (THERMAL_WIDTH - label.length - value.length).coerceAtLeast(1)
        return label + " ".repeat(space) + value
    }
}
