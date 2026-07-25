package com.beettechnologies.posly.pos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.cart.TaxBreakdownLineResponse
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.PaymentRecordResponse
import com.beettechnologies.posly.receipts.EmailReceiptOutcome
import com.beettechnologies.posly.receipts.EmailReceiptResponse
import com.beettechnologies.posly.receipts.ListPrintersOutcome
import com.beettechnologies.posly.receipts.PrintJobResponse
import com.beettechnologies.posly.receipts.PrintReceiptOutcome
import com.beettechnologies.posly.receipts.PrinterResponse
import com.beettechnologies.posly.receipts.ReceiptApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

private class FakeModalReceiptApi(
    private val printers: List<PrinterResponse> = emptyList(),
    private val printOutcome: PrintReceiptOutcome? = null,
    private val emailOutcome: EmailReceiptOutcome? = null
) : ReceiptApi {
    override suspend fun listPrinters(storeId: String): ListPrintersOutcome = ListPrintersOutcome.Success(printers)

    override suspend fun printReceipt(orderId: String, printerId: String): PrintReceiptOutcome =
        printOutcome ?: PrintReceiptOutcome.Printed(PrintJobResponse("job-1", orderId, printerId, "PRINTED", null, "2026-01-01T00:00:00Z"))

    override suspend fun emailReceipt(orderId: String, recipient: String): EmailReceiptOutcome =
        emailOutcome ?: EmailReceiptOutcome.Sent(EmailReceiptResponse("email-1", orderId, recipient, "SENT", null, "2026-01-01T00:00:00Z"))
}

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

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ReceiptModalTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `receipt renders itemized lines, totals breakdown and each tender's payment info`() = runComposeUiTest {
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = {}, viewModel = ReceiptViewModel(FakeModalReceiptApi())) }

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
        setContent { ReceiptModal(order = order, onDismiss = {}, viewModel = ReceiptViewModel(FakeModalReceiptApi())) }

        val discountNodes = onNodeWithTag(ReceiptModalTags.DISCOUNT_TEXT)
        assertTrue(runCatching { discountNodes.assertIsDisplayed() }.isFailure)
    }

    @Test
    fun `each backend tax breakdown line is shown verbatim on the receipt, alongside the rolled-up total`() = runComposeUiTest {
        val order = receiptTestOrder().copy(
            totals = receiptTestOrder().totals.copy(
                taxBreakdown = listOf(
                    TaxBreakdownLineResponse("GST", 5.0, 10.0),
                    TaxBreakdownLineResponse("PST", 7.0, 14.7)
                ),
                totalTax = 24.7,
                total = receiptTestOrder().totals.subtotal + 24.7
            )
        )
        setContent { ReceiptModal(order = order, onDismiss = {}, viewModel = ReceiptViewModel(FakeModalReceiptApi())) }

        onNodeWithTag(ReceiptModalTags.TAX_LINE_PREFIX + 0).assertTextContains("GST", substring = true)
        onNodeWithTag(ReceiptModalTags.TAX_LINE_PREFIX + 0).assertTextContains("10.0", substring = true)
        onNodeWithTag(ReceiptModalTags.TAX_LINE_PREFIX + 1).assertTextContains("PST", substring = true)
        onNodeWithTag(ReceiptModalTags.TAX_LINE_PREFIX + 1).assertTextContains("14.7", substring = true)
        onNodeWithTag(ReceiptModalTags.TAX_TEXT).assertTextContains("24.7", substring = true)
    }

    @Test
    fun `an all-exempt order shows no breakdown lines but still shows zero tax`() = runComposeUiTest {
        val order = receiptTestOrder().copy(
            totals = receiptTestOrder().totals.copy(taxBreakdown = emptyList(), totalTax = 0.0)
        )
        setContent { ReceiptModal(order = order, onDismiss = {}, viewModel = ReceiptViewModel(FakeModalReceiptApi())) }

        onNodeWithTag(ReceiptModalTags.TAX_LINE_PREFIX + 0).assertDoesNotExist()
        onNodeWithTag(ReceiptModalTags.TAX_TEXT).assertTextContains("0.0", substring = true)
    }

    @Test
    fun `clicking New Sale invokes onDismiss`() = runComposeUiTest {
        var dismissed = false
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = { dismissed = true }, viewModel = ReceiptViewModel(FakeModalReceiptApi())) }

        onNodeWithTag(ReceiptModalTags.NEW_SALE_BUTTON).performClick()
        waitForIdle()

        assertTrue(dismissed)
    }

    @Test
    fun `clicking Print with an online printer configured reports success`() = runComposeUiTest {
        val printers = listOf(PrinterResponse("printer-1", "store-1", "Front", "USB", "ONLINE", "2026-01-01T00:00:00Z"))
        val viewModel = ReceiptViewModel(FakeModalReceiptApi(printers = printers))
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ReceiptModalTags.PRINT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ReceiptModalTags.PRINT_STATUS_TEXT).assertTextContains("Receipt sent to the printer.", substring = true)
    }

    @Test
    fun `a queued print job shows the offline message and offers the email fallback`() = runComposeUiTest {
        val printers = listOf(PrinterResponse("printer-1", "store-1", "Front", "USB", "OFFLINE", "2026-01-01T00:00:00Z"))
        val queuedJob = PrintJobResponse("job-1", "order-1", "printer-1", "QUEUED", "Printer is offline", "2026-01-01T00:00:00Z")
        val viewModel = ReceiptViewModel(FakeModalReceiptApi(printers = printers, printOutcome = PrintReceiptOutcome.Queued(queuedJob)))
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ReceiptModalTags.PRINT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ReceiptModalTags.PRINT_STATUS_TEXT).assertTextContains("Printer is offline", substring = true)
        onNodeWithTag(ReceiptModalTags.EMAIL_FALLBACK_PROMPT).assertIsDisplayed()
    }

    @Test
    fun `emailing the receipt to a typed address reports success`() = runComposeUiTest {
        val viewModel = ReceiptViewModel(FakeModalReceiptApi())
        setContent { ReceiptModal(order = receiptTestOrder(), onDismiss = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(ReceiptModalTags.EMAIL_FIELD).performTextInput("customer@example.com")
        onNodeWithTag(ReceiptModalTags.EMAIL_SEND_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(ReceiptModalTags.EMAIL_STATUS_TEXT).assertTextContains("Receipt emailed to customer@example.com", substring = true)
    }
}
