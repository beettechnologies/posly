package com.beettechnologies.posly.pos

import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.ConfirmPaymentOutcome
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.PaymentRecordResponse
import com.beettechnologies.posly.payments.CreatePaymentOutcome
import com.beettechnologies.posly.payments.GetPaymentOutcome
import com.beettechnologies.posly.payments.PaymentApi
import com.beettechnologies.posly.payments.PaymentResponse
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

private fun testOrder(total: Double, status: String = "PENDING", amountPaid: Double = 0.0) = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = emptyList(),
    discount = null,
    totals = CartTotalsResponse(
        subtotal = total,
        itemDiscountTotal = 0.0,
        cartDiscountAmount = 0.0,
        taxableAmount = total,
        taxBreakdown = emptyList(),
        totalTax = 0.0,
        total = total
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T00:00:00Z",
    status = status,
    amountPaid = amountPaid,
    remainingBalance = total - amountPaid
)

private class FakeOrderApi(
    private var order: OrderResponse,
    private val confirmPaymentOutcome: ConfirmPaymentOutcome? = null
) : OrderApi {
    var getOrderCalls = 0
    var confirmPaymentCalls = 0
    var lastConfirmPayment: Triple<String, Double, String?>? = null

    override suspend fun getOrder(id: String): GetOrderOutcome {
        getOrderCalls++
        return GetOrderOutcome.Success(order)
    }

    override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?): ConfirmPaymentOutcome {
        confirmPaymentCalls++
        lastConfirmPayment = Triple(method, amount, reference)
        confirmPaymentOutcome?.let { return it }
        val newPayments = order.payments + PaymentRecordResponse(
            method = method,
            amount = amount,
            reference = reference,
            confirmedBy = "cashier-1",
            confirmedAt = "2026-01-01T00:00:00Z"
        )
        val newAmountPaid = newPayments.sumOf { it.amount }
        val newRemaining = order.totals.total - newAmountPaid
        order = order.copy(
            status = if (newRemaining <= 0.001) "PAID" else "PENDING",
            payments = newPayments,
            amountPaid = newAmountPaid,
            remainingBalance = newRemaining.coerceAtLeast(0.0)
        )
        return ConfirmPaymentOutcome.Success(order)
    }
}

/**
 * [statusSequence] is what successive [getPayment] calls return, holding at the last entry once
 * exhausted. When [orderApi] is supplied, the first time a poll observes "APPROVED" this also
 * confirms the payment against it - mirroring how the real webhook confirms the order server-side
 * in the very same event that flips the gateway payment to APPROVED.
 */
private class FakePaymentApi(
    var createOutcomeOverride: CreatePaymentOutcome? = null,
    private val statusSequence: List<String> = listOf("APPROVED"),
    private val orderApi: FakeOrderApi? = null
) : PaymentApi {
    var createPaymentCalls = 0
    var getPaymentCalls = 0
    private var lastAmount = 0.0
    private var approvalApplied = false

    override suspend fun createPayment(orderId: String, amount: Double, currency: String): CreatePaymentOutcome {
        createPaymentCalls++
        lastAmount = amount
        approvalApplied = false
        createOutcomeOverride?.let { return it }
        return CreatePaymentOutcome.Success(
            PaymentResponse(
                id = "payment-1",
                orderId = orderId,
                terminalTransactionId = "term-1",
                amount = amount,
                currency = currency,
                status = "INITIATED",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z"
            )
        )
    }

    override suspend fun getPayment(id: String): GetPaymentOutcome {
        getPaymentCalls++
        val status = statusSequence.getOrElse(getPaymentCalls - 1) { statusSequence.last() }
        if (status == "APPROVED" && !approvalApplied) {
            approvalApplied = true
            orderApi?.confirmPayment("order-1", "TERMINAL", lastAmount, "term-1")
        }
        return GetPaymentOutcome.Success(
            PaymentResponse(
                id = id,
                orderId = "order-1",
                terminalTransactionId = "term-1",
                amount = lastAmount,
                currency = "USD",
                status = status,
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
                declineReason = if (status == "DECLINED") "Card declined (simulated)" else null
            )
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewModelTest {

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
    fun `loading an order populates the total`() = runTest(dispatcher) {
        val order = testOrder(total = 24.5)
        val viewModel = PaymentViewModel(FakeOrderApi(order), FakePaymentApi())

        viewModel.load(order.id)
        advanceUntilIdle()

        assertEquals(24.5, viewModel.uiState.value.total)
    }

    @Test
    fun `cash change is only computed once the tendered amount covers the total`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val viewModel = PaymentViewModel(FakeOrderApi(order), FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()
        viewModel.selectTender(Tender.CASH)

        viewModel.updateCashTendered("5")
        assertNull(viewModel.uiState.value.changeDue)

        viewModel.updateCashTendered("15")
        assertEquals(5.0, viewModel.uiState.value.changeDue)
    }

    @Test
    fun `confirming a cash payment charges the total and records the tendered amount as the reference`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val viewModel = PaymentViewModel(orderApi, FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()
        viewModel.selectTender(Tender.CASH)
        viewModel.updateCashTendered("15")

        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        assertEquals(Triple("CASH", 10.0, "15"), orderApi.lastConfirmPayment)
        assertEquals("PAID", viewModel.uiState.value.completedOrder?.status)
    }

    @Test
    fun `confirming a cash payment below the total is a no-op`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val viewModel = PaymentViewModel(orderApi, FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()
        viewModel.selectTender(Tender.CASH)
        viewModel.updateCashTendered("5")

        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        assertEquals(0, orderApi.confirmPaymentCalls)
        assertNull(viewModel.uiState.value.completedOrder)
    }

    @Test
    fun `confirming a gift card payment charges the exact total with no reference`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val viewModel = PaymentViewModel(orderApi, FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()
        viewModel.selectTender(Tender.GIFT_CARD)

        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        assertEquals(Triple("GIFT_CARD", 10.0, null), orderApi.lastConfirmPayment)
    }

    @Test
    fun `starting the terminal polls until it resolves as approved`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(statusSequence = listOf("INITIATED", "APPROVED"), orderApi = orderApi)
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 1000)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.startTerminal()
        advanceUntilIdle()

        assertEquals(TerminalState.APPROVED, viewModel.uiState.value.terminalState)
        assertEquals(2, orderApi.getOrderCalls, "the order should be re-fetched once the payment approves")
        assertEquals(order.id, viewModel.uiState.value.completedOrder?.id)
        assertEquals("PAID", viewModel.uiState.value.completedOrder?.status)
    }

    @Test
    fun `starting the terminal surfaces a decline reason`() = runTest(dispatcher) {
        val order = testOrder(total = 10.13)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(statusSequence = listOf("DECLINED"))
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 1000)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.startTerminal()
        advanceUntilIdle()

        assertEquals(TerminalState.DECLINED, viewModel.uiState.value.terminalState)
        assertEquals(
            "Card declined (simulated) - retry or choose another tender below.",
            viewModel.uiState.value.errorMessage
        )
        assertNull(viewModel.uiState.value.completedOrder)
    }

    @Test
    fun `starting the terminal times out if it never resolves`() = runTest(dispatcher) {
        val order = testOrder(total = 10.99)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(statusSequence = listOf("INITIATED"))
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 30)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.startTerminal()
        advanceUntilIdle()

        assertEquals(TerminalState.TIMED_OUT, viewModel.uiState.value.terminalState)
        assertEquals(
            "The terminal did not respond in time - retry or choose another tender below.",
            viewModel.uiState.value.errorMessage
        )
    }

    @Test
    fun `starting the terminal while already polling is a no-op`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(statusSequence = listOf("INITIATED", "INITIATED", "APPROVED"))
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 1000)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.startTerminal()
        viewModel.startTerminal()
        viewModel.startTerminal()
        advanceUntilIdle()

        assertEquals(1, paymentApi.createPaymentCalls)
    }

    @Test
    fun `a network error creating the payment surfaces an error a retry can recover from`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(
            createOutcomeOverride = CreatePaymentOutcome.NetworkError("Connection timed out"),
            orderApi = orderApi
        )
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 1000)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.startTerminal()
        advanceUntilIdle()

        assertEquals(TerminalState.ERROR, viewModel.uiState.value.terminalState)
        assertEquals("Connection timed out", viewModel.uiState.value.errorMessage)

        paymentApi.createOutcomeOverride = null
        viewModel.startTerminal()
        advanceUntilIdle()

        assertEquals(TerminalState.APPROVED, viewModel.uiState.value.terminalState)
    }

    @Test
    fun `switching tenders while idle resets the cash amount and terminal state`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val viewModel = PaymentViewModel(FakeOrderApi(order), FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()
        viewModel.selectTender(Tender.CASH)
        viewModel.updateCashTendered("20")

        viewModel.selectTender(Tender.GIFT_CARD)

        assertEquals("", viewModel.uiState.value.cashTendered)
        assertEquals(Tender.GIFT_CARD, viewModel.uiState.value.selectedTender)
    }

    @Test
    fun `a split tender - cash then gift card - marks the order paid with a breakdown of both`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val viewModel = PaymentViewModel(orderApi, FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.selectTender(Tender.CASH)
        viewModel.updateAmountToApply("4")
        viewModel.updateCashTendered("4")
        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        // First tender only partially covers the order - no completion yet, remaining balance updated.
        assertNull(viewModel.uiState.value.completedOrder)
        assertEquals(6.0, viewModel.uiState.value.remainingBalance)
        assertEquals(TerminalState.IDLE, viewModel.uiState.value.terminalState)

        viewModel.selectTender(Tender.GIFT_CARD)
        assertEquals("6.0", viewModel.uiState.value.amountToApply)
        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        val completed = viewModel.uiState.value.completedOrder
        assertEquals("PAID", completed?.status)
        assertEquals(listOf("CASH", "GIFT_CARD"), completed?.payments?.map { it.method })
        assertEquals(listOf(4.0, 6.0), completed?.payments?.map { it.amount })
        assertEquals(0.0, completed?.remainingBalance)
    }

    @Test
    fun `a partial cash payment followed by a card tender for the remainder reconciles the order as paid`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(orderApi = orderApi)
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 1000)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.selectTender(Tender.CASH)
        viewModel.updateAmountToApply("3")
        viewModel.updateCashTendered("3")
        viewModel.confirmNonCardPayment()
        advanceUntilIdle()
        assertEquals(7.0, viewModel.uiState.value.remainingBalance)

        viewModel.selectTender(Tender.CARD)
        assertEquals("7.0", viewModel.uiState.value.amountToApply)
        viewModel.startTerminal()
        advanceUntilIdle()

        val completed = viewModel.uiState.value.completedOrder
        assertEquals("PAID", completed?.status)
        assertEquals(listOf(3.0, 7.0), completed?.payments?.map { it.amount })
        assertEquals(0.0, completed?.remainingBalance)
    }

    @Test
    fun `an amount above the remaining balance cannot be applied`() = runTest(dispatcher) {
        val order = testOrder(total = 10.0)
        val orderApi = FakeOrderApi(order)
        val viewModel = PaymentViewModel(orderApi, FakePaymentApi())
        viewModel.load(order.id)
        advanceUntilIdle()
        viewModel.selectTender(Tender.GIFT_CARD)

        viewModel.updateAmountToApply("15")
        assertNull(viewModel.uiState.value.amountToApplyValue)

        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        assertEquals(0, orderApi.confirmPaymentCalls, "an over-the-balance amount must never reach the API")
    }

    @Test
    fun `a decline lets the cashier retry the card or switch to an alternate tender, preserving the order`() = runTest(dispatcher) {
        val order = testOrder(total = 10.13)
        val orderApi = FakeOrderApi(order)
        val paymentApi = FakePaymentApi(statusSequence = listOf("DECLINED"), orderApi = orderApi)
        val viewModel = PaymentViewModel(orderApi, paymentApi, pollIntervalMillis = 10, pollTimeoutMillis = 1000)
        viewModel.load(order.id)
        advanceUntilIdle()

        viewModel.startTerminal()
        advanceUntilIdle()
        assertEquals(TerminalState.DECLINED, viewModel.uiState.value.terminalState)

        // The order is untouched by the decline - full balance still owed, cashier can pick another tender.
        assertEquals(10.13, viewModel.uiState.value.remainingBalance)
        assertNull(viewModel.uiState.value.completedOrder)

        viewModel.selectTender(Tender.CASH)
        viewModel.updateCashTendered("10.13")
        viewModel.confirmNonCardPayment()
        advanceUntilIdle()

        assertEquals("PAID", viewModel.uiState.value.completedOrder?.status)
        assertEquals(listOf("CASH"), viewModel.uiState.value.completedOrder?.payments?.map { it.method })
    }
}
