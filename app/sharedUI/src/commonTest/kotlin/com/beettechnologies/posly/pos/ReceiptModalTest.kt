package com.beettechnologies.posly.pos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.PaymentRecordResponse
import kotlin.test.Test
import kotlin.test.assertTrue

private fun receiptTestOrder() = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = listOf(
        CartItemResponse(
            id = "item-1", productId = "prod-1", productName = "Widget", quantity = 2,
            unitPrice = 4.0, taxCategory = "standard", selectedModifiers = emptyList(),
            discount = null, lineSubtotal = 8.0, lineDiscountAmount = 0.0, lineTotal = 8.0
        ),
        CartItemResponse(
            id = "item-2", productId = "prod-2", productName = "Gadget", quantity = 1,
            unitPrice = 5.0, taxCategory = "standard", selectedModifiers = emptyList(),
            discount = null, lineSubtotal = 5.0, lineDiscountAmount = 1.0, lineTotal = 4.0
        )
    ),
    discount = null,
    totals = CartTotalsResponse(
        subtotal = 13.0, itemDiscountTotal = 1.0, cartDiscountAmount = 0.0,
        taxableAmount = 12.0, taxBreakdown = emptyList(), totalTax = 1.2, total = 13.2
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T00:00:00Z",
    status = "PAID",
    payments = listOf(
        PaymentRecordResponse(
            method = "CASH", amount = 7.2, reference = null,
            confirmedBy = "cashier-1", confirmedAt = "2026-01-01T00:00:00Z"
        ),
        PaymentRecordResponse(
            method = "TERMINAL", amount = 6.0, reference = "term_ABC123",
            confirmedBy = "cashier-1", confirmedAt = "2026-01-01T00:00:00Z",
            maskedCardNumber = "•••• •••• •••• 4242"
        )
    ),
    amountPaid = 13.2,
    remainingBalance = 0.0
)

@OptIn(ExperimentalTestApi::class)
class ReceiptModalTest {

    @Test
    fun `receipt renders itemized lines, totals breakdown and each tender's payment info`() = runComposeUiTest {
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = {}) }

        onNodeWithTag(ReceiptModalTags.CONTAINER).assertIsDisplayed()
        onNodeWithTag(ReceiptModalTags.ITEM_PREFIX + 0).assertIsDisplayed()
        onNodeWithTag(ReceiptModalTags.ITEM_PREFIX + 1).assertIsDisplayed()
        onNodeWithText("Widget", substring = true).assertIsDisplayed()
        onNodeWithText("Gadget", substring = true).assertIsDisplayed()
        onNodeWithTag(ReceiptModalTags.SUBTOTAL_TEXT).assertTextContains("13.0", substring = true)
        onNodeWithTag(ReceiptModalTags.DISCOUNT_TEXT).assertTextContains("1.0", substring = true)
        onNodeWithTag(ReceiptModalTags.TAX_TEXT).assertTextContains("1.2", substring = true)
        onNodeWithTag(ReceiptModalTags.TOTAL_TEXT).assertTextContains("13.2", substring = true)

        onNodeWithTag(ReceiptModalTags.PAYMENT_PREFIX + 0).assertTextContains("CASH", substring = true)
        onNodeWithTag(ReceiptModalTags.PAYMENT_PREFIX + 0).assertTextContains("7.2", substring = true)

        onNodeWithTag(ReceiptModalTags.PAYMENT_PREFIX + 1).assertTextContains("TERMINAL", substring = true)
        onNodeWithTag(ReceiptModalTags.PAYMENT_PREFIX + 1).assertTextContains("6.0", substring = true)
        onNodeWithTag(ReceiptModalTags.PAYMENT_PREFIX + 1).assertTextContains("•••• •••• •••• 4242", substring = true)
        onNodeWithTag(ReceiptModalTags.PAYMENT_PREFIX + 1).assertTextContains("term_ABC123", substring = true)
    }

    @Test
    fun `an order with no discount omits the discount line`() = runComposeUiTest {
        val order = receiptTestOrder().copy(
            totals = receiptTestOrder().totals.copy(itemDiscountTotal = 0.0, cartDiscountAmount = 0.0)
        )
        setContent { ReceiptModal(order = order, onDismiss = {}) }

        val discountNodes = onNodeWithTag(ReceiptModalTags.DISCOUNT_TEXT)
        assertTrue(runCatching { discountNodes.assertIsDisplayed() }.isFailure)
    }

    @Test
    fun `clicking New Sale invokes onDismiss`() = runComposeUiTest {
        var dismissed = false
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = { dismissed = true }) }

        onNodeWithTag(ReceiptModalTags.NEW_SALE_BUTTON).performClick()
        waitForIdle()

        assertTrue(dismissed)
    }
}
