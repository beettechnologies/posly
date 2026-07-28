package com.beettechnologies.posly.receipts

import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.PaymentRecord
import com.beettechnologies.posly.cart.TaxBreakdownLine
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.Store
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun testOrder(currency: String = "USD") = Order(
    cartId = "cart-1",
    storeId = "store-1",
    createdBy = "cashier-1",
    items = listOf(
        CartItem(
            productId = "product-1", productName = "Widget", quantity = 2,
            unitPrice = 5.0, taxCategory = TaxCategory.STANDARD
        )
    ),
    discount = null,
    totals = CartTotals(
        subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 10.0,
        taxBreakdown = listOf(TaxBreakdownLine("GST", 5.0, 0.5), TaxBreakdownLine("PST", 7.0, 0.7)),
        totalTax = 1.2, total = 11.2
    ),
    idempotencyKey = "key-1",
    checkedOutAt = Instant.parse("2026-01-01T00:00:00Z"),
    currency = currency,
    payments = listOf(
        PaymentRecord(
            method = "TERMINAL", amount = 11.2, reference = "term_ABC123",
            confirmedBy = "cashier-1", confirmedAt = Instant.parse("2026-01-01T00:00:00Z"),
            maskedCardNumber = "•••• •••• •••• 4242"
        )
    )
)

private fun testStore(locale: String = "en-US", timezone: String = "UTC") = Store(
    id = "store-1",
    name = "Downtown",
    address = Address(line1 = "1 Main St", city = "NY", postalCode = "10001", country = "US"),
    timezone = timezone,
    currency = "USD",
    locale = locale
)

private fun pngBytes(): ByteArray {
    val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}

class ReceiptRendererTest {

    @Test
    fun `thermal text includes items, tax breakdown lines, total and payment info`() {
        val text = ReceiptRenderer.renderThermalText(testOrder(), testStore())

        assertTrue(text.contains("2x Widget"))
        assertTrue(text.contains("GST (5.0%)"))
        assertTrue(text.contains("PST (7.0%)"))
        assertTrue(text.contains("11.20"), "total should appear in the receipt")
        assertTrue(text.contains("TERMINAL"))
        assertTrue(text.contains("term_ABC123"))
    }

    @Test
    fun `thermal text shows the store name as a text header, since no bitmap logo can render there`() {
        val text = ReceiptRenderer.renderThermalText(testOrder(), testStore())
        assertTrue(text.contains("DOWNTOWN"))
    }

    @Test
    fun `two-column lines (subtotal, tax, total) align to the thermal paper width`() {
        val text = ReceiptRenderer.renderThermalText(testOrder(), testStore())

        val totalLine = text.lines().single { it.startsWith("TOTAL:") }
        assertEquals(32, totalLine.length)
    }

    @Test
    fun `a wider locale-formatted currency string still produces a well-formed, non-negative-padded line`() {
        val text = ReceiptRenderer.renderThermalText(testOrder(currency = "EUR"), testStore(locale = "de-DE"))

        val totalLine = text.lines().single { it.startsWith("TOTAL:") }
        assertTrue(totalLine.length >= "TOTAL:".length)
        assertTrue(totalLine.contains("€"))
    }

    @Test
    fun `money amounts format per the store's locale combined with the order's own currency`() {
        val usText = ReceiptRenderer.renderThermalText(testOrder(currency = "USD"), testStore(locale = "en-US"))
        assertTrue(usText.contains("$11.20"))

        val deText = ReceiptRenderer.renderThermalText(testOrder(currency = "EUR"), testStore(locale = "de-DE"))
        assertTrue(deText.contains("11,20"))
    }

    @Test
    fun `the receipt shows a locale-formatted date derived from the store's locale and timezone`() {
        val text = ReceiptRenderer.renderThermalText(testOrder(), testStore(locale = "en-US", timezone = "UTC"))
        assertTrue(text.contains("2026") && text.contains("Jan"), "expected an en-US-formatted date on the receipt: $text")
    }

    @Test
    fun `renderPdf produces a genuine, non-empty PDF document`() {
        val bytes = ReceiptRenderer.renderPdf(testOrder(), testStore())

        assertTrue(bytes.isNotEmpty())
        val header = bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII)
        assertEquals("%PDF-", header)
    }

    @Test
    fun `renderPdf embeds a real image XObject when logo bytes are provided`() {
        val bytes = ReceiptRenderer.renderPdf(testOrder(), testStore(), pngBytes())

        Loader.loadPDF(bytes).use { document ->
            val page = document.getPage(0)
            val hasImage = page.resources.xObjectNames.any { name -> page.resources.getXObject(name) is PDImageXObject }
            assertTrue(hasImage, "expected the PDF to contain an embedded image XObject for the logo")
        }
    }

    @Test
    fun `renderPdf has no image XObject when no logo is provided`() {
        val bytes = ReceiptRenderer.renderPdf(testOrder(), testStore(), null)

        Loader.loadPDF(bytes).use { document ->
            val page = document.getPage(0)
            val hasImage = page.resources.xObjectNames.any { name -> page.resources.getXObject(name) is PDImageXObject }
            assertFalse(hasImage)
        }
    }

    @Test
    fun `renderPdf falls back to no logo rather than throwing when the logo bytes are corrupt`() {
        val bytes = ReceiptRenderer.renderPdf(testOrder(), testStore(), "not a real image".toByteArray())

        assertTrue(bytes.isNotEmpty())
        val header = bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII)
        assertEquals("%PDF-", header)
    }

    @Test
    fun `thermal text rendering is deterministic for the same order`() {
        val order = testOrder()
        val store = testStore()

        assertEquals(ReceiptRenderer.renderThermalText(order, store), ReceiptRenderer.renderThermalText(order, store))
    }
}
