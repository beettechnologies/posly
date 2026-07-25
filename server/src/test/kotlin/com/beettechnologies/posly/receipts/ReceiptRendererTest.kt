package com.beettechnologies.posly.receipts

import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.PaymentRecord
import com.beettechnologies.posly.cart.TaxBreakdownLine
import com.beettechnologies.posly.products.TaxCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun testOrder() = Order(
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
    payments = listOf(
        PaymentRecord(
            method = "TERMINAL", amount = 11.2, reference = "term_ABC123",
            confirmedBy = "cashier-1", confirmedAt = Instant.parse("2026-01-01T00:00:00Z"),
            maskedCardNumber = "•••• •••• •••• 4242"
        )
    )
)

class ReceiptRendererTest {

    @Test
    fun `thermal text includes items, tax breakdown lines, total and payment info`() {
        val text = ReceiptRenderer.renderThermalText(testOrder())

        assertTrue(text.contains("2x Widget"))
        assertTrue(text.contains("GST (5.0%)"))
        assertTrue(text.contains("PST (7.0%)"))
        assertTrue(text.contains("11.2"), "total should appear in the receipt")
        assertTrue(text.contains("TERMINAL"))
        assertTrue(text.contains("term_ABC123"))
    }

    @Test
    fun `two-column lines (subtotal, tax, total) align to the thermal paper width`() {
        val text = ReceiptRenderer.renderThermalText(testOrder())

        val totalLine = text.lines().single { it.startsWith("TOTAL:") }
        assertEquals(32, totalLine.length)
    }

    @Test
    fun `renderPdf produces a genuine, non-empty PDF document`() {
        val bytes = ReceiptRenderer.renderPdf(testOrder())

        assertTrue(bytes.isNotEmpty())
        val header = bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII)
        assertEquals("%PDF-", header)
    }

    @Test
    fun `thermal text rendering is deterministic for the same order`() {
        val order = testOrder()

        assertEquals(ReceiptRenderer.renderThermalText(order), ReceiptRenderer.renderThermalText(order))
    }
}
