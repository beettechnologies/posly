package com.beettechnologies.posly.pos

import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.receipts.EmailReceiptOutcome
import com.beettechnologies.posly.receipts.EmailReceiptResponse
import com.beettechnologies.posly.receipts.ListPrintersOutcome
import com.beettechnologies.posly.receipts.PrintJobResponse
import com.beettechnologies.posly.receipts.PrintReceiptOutcome
import com.beettechnologies.posly.receipts.PrinterResponse
import com.beettechnologies.posly.receipts.ReceiptApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun testOrder() = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = emptyList(),
    discount = null,
    totals = CartTotalsResponse(
        subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 10.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 10.0
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T00:00:00Z",
    status = "PAID",
    amountPaid = 10.0,
    remainingBalance = 0.0
)

private class FakeReceiptApi(
    private val printers: List<PrinterResponse> = listOf(
        PrinterResponse("printer-1", "store-1", "Front", "USB", "ONLINE", "2026-01-01T00:00:00Z")
    ),
    private val printOutcome: PrintReceiptOutcome? = null,
    private val emailOutcome: EmailReceiptOutcome? = null
) : ReceiptApi {
    var printCalls = 0
    var emailCalls = 0

    override suspend fun listPrinters(storeId: String): ListPrintersOutcome = ListPrintersOutcome.Success(printers)

    override suspend fun printReceipt(orderId: String, printerId: String): PrintReceiptOutcome {
        printCalls++
        return printOutcome ?: PrintReceiptOutcome.Printed(
            PrintJobResponse("job-1", orderId, printerId, "PRINTED", null, "2026-01-01T00:00:00Z")
        )
    }

    override suspend fun emailReceipt(orderId: String, recipient: String): EmailReceiptOutcome {
        emailCalls++
        return emailOutcome ?: EmailReceiptOutcome.Sent(
            EmailReceiptResponse("email-1", orderId, recipient, "SENT", null, "2026-01-01T00:00:00Z")
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialize picks the store's first registered printer`() = runTest(dispatcher) {
        val viewModel = ReceiptViewModel(FakeReceiptApi())

        viewModel.initialize(testOrder())
        advanceUntilIdle()

        assertEquals("printer-1", viewModel.uiState.value.printerId)
        assertEquals(false, viewModel.uiState.value.isLoadingPrinter)
    }

    @Test
    fun `printing to an online printer reports success with no fallback offered`() = runTest(dispatcher) {
        val viewModel = ReceiptViewModel(FakeReceiptApi())
        viewModel.initialize(testOrder())
        advanceUntilIdle()

        viewModel.print()
        advanceUntilIdle()

        assertEquals(PrintState.PRINTED, viewModel.uiState.value.printState)
        assertEquals(false, viewModel.uiState.value.showEmailFallback)
    }

    @Test
    fun `a queued print job offers the email fallback`() = runTest(dispatcher) {
        val queuedJob = PrintJobResponse("job-1", "order-1", "printer-1", "QUEUED", "Printer is offline", "2026-01-01T00:00:00Z")
        val viewModel = ReceiptViewModel(FakeReceiptApi(printOutcome = PrintReceiptOutcome.Queued(queuedJob)))
        viewModel.initialize(testOrder())
        advanceUntilIdle()

        viewModel.print()
        advanceUntilIdle()

        assertEquals(PrintState.QUEUED, viewModel.uiState.value.printState)
        assertEquals("Printer is offline", viewModel.uiState.value.printMessage)
        assertEquals(true, viewModel.uiState.value.showEmailFallback)
    }

    @Test
    fun `with no printer configured, printing errors and offers the email fallback without calling the API`() = runTest(dispatcher) {
        val api = FakeReceiptApi(printers = emptyList())
        val viewModel = ReceiptViewModel(api)
        viewModel.initialize(testOrder())
        advanceUntilIdle()

        viewModel.print()
        advanceUntilIdle()

        assertEquals(PrintState.ERROR, viewModel.uiState.value.printState)
        assertEquals(true, viewModel.uiState.value.showEmailFallback)
        assertEquals(0, api.printCalls)
    }

    @Test
    fun `emailing a receipt to a valid address reports success`() = runTest(dispatcher) {
        val viewModel = ReceiptViewModel(FakeReceiptApi())
        viewModel.initialize(testOrder())
        advanceUntilIdle()

        viewModel.updateEmailAddress("customer@example.com")
        viewModel.sendEmail()
        advanceUntilIdle()

        assertEquals(EmailState.SENT, viewModel.uiState.value.emailState)
        assertEquals(false, viewModel.uiState.value.showEmailFallback)
    }

    @Test
    fun `a failed email send reports the failure message`() = runTest(dispatcher) {
        val failedEmail = EmailReceiptResponse("email-1", "order-1", "customer+bounce@example.com", "FAILED", "Simulated permanent bounce", "2026-01-01T00:00:00Z")
        val viewModel = ReceiptViewModel(FakeReceiptApi(emailOutcome = EmailReceiptOutcome.Failed(failedEmail)))
        viewModel.initialize(testOrder())
        advanceUntilIdle()

        viewModel.updateEmailAddress("customer+bounce@example.com")
        viewModel.sendEmail()
        advanceUntilIdle()

        assertEquals(EmailState.ERROR, viewModel.uiState.value.emailState)
        assertEquals("Simulated permanent bounce", viewModel.uiState.value.emailMessage)
    }

    @Test
    fun `an invalid email address reports the validation message`() = runTest(dispatcher) {
        val viewModel = ReceiptViewModel(FakeReceiptApi(emailOutcome = EmailReceiptOutcome.InvalidEmail("'not-an-email' is not a valid email address")))
        viewModel.initialize(testOrder())
        advanceUntilIdle()

        viewModel.updateEmailAddress("not-an-email")
        viewModel.sendEmail()
        advanceUntilIdle()

        assertEquals(EmailState.ERROR, viewModel.uiState.value.emailState)
        assertEquals("'not-an-email' is not a valid email address", viewModel.uiState.value.emailMessage)
    }

    @Test
    fun `dismissing the email fallback prompt hides it`() = runTest(dispatcher) {
        val queuedJob = PrintJobResponse("job-1", "order-1", "printer-1", "QUEUED", "Printer is offline", "2026-01-01T00:00:00Z")
        val viewModel = ReceiptViewModel(FakeReceiptApi(printOutcome = PrintReceiptOutcome.Queued(queuedJob)))
        viewModel.initialize(testOrder())
        advanceUntilIdle()
        viewModel.print()
        advanceUntilIdle()

        viewModel.dismissEmailFallback()

        assertEquals(false, viewModel.uiState.value.showEmailFallback)
    }

    @Test
    fun `updating the email address clears any prior email status message`() = runTest(dispatcher) {
        val viewModel = ReceiptViewModel(FakeReceiptApi(emailOutcome = EmailReceiptOutcome.InvalidEmail("bad email")))
        viewModel.initialize(testOrder())
        advanceUntilIdle()
        viewModel.updateEmailAddress("not-an-email")
        viewModel.sendEmail()
        advanceUntilIdle()

        viewModel.updateEmailAddress("customer@example.com")

        assertNull(viewModel.uiState.value.emailMessage)
    }
}
