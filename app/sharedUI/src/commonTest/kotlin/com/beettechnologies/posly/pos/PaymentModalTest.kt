package com.beettechnologies.posly.pos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.ConfirmPaymentOutcome
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.payments.CreatePaymentOutcome
import com.beettechnologies.posly.payments.GetPaymentOutcome
import com.beettechnologies.posly.payments.PaymentApi
import com.beettechnologies.posly.payments.PaymentResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun modalTestOrder(total: Double) = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = emptyList(),
    discount = null,
    totals = CartTotalsResponse(
        subtotal = total, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = total, taxBreakdown = emptyList(), totalTax = 0.0, total = total
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T00:00:00Z",
    status = "PENDING"
)

private class FakeModalOrderApi(private var order: OrderResponse) : OrderApi {
    var lastConfirmPayment: Triple<String, Double, String?>? = null

    override suspend fun getOrder(id: String): GetOrderOutcome = GetOrderOutcome.Success(order)

    override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?): ConfirmPaymentOutcome {
        lastConfirmPayment = Triple(method, amount, reference)
        order = order.copy(status = "PAID")
        return ConfirmPaymentOutcome.Success(order)
    }
}

private class FakeModalPaymentApi : PaymentApi {
    var createPaymentCalls = 0

    override suspend fun createPayment(orderId: String, amount: Double, currency: String): CreatePaymentOutcome {
        createPaymentCalls++
        return CreatePaymentOutcome.Success(
            PaymentResponse(
                id = "payment-1", orderId = orderId, terminalTransactionId = "term-1",
                amount = amount, currency = currency, status = "INITIATED",
                createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
            )
        )
    }

    override suspend fun getPayment(id: String): GetPaymentOutcome = GetPaymentOutcome.Success(
        PaymentResponse(
            id = id, orderId = "order-1", terminalTransactionId = "term-1",
            amount = 10.0, currency = "USD", status = "INITIATED",
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
        )
    )
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PaymentModalTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the order total and tender options are displayed`() = runComposeUiTest {
        val viewModel = PaymentViewModel(FakeModalOrderApi(modalTestOrder(10.0)), FakeModalPaymentApi())

        setContent { PaymentModal(orderId = "order-1", onDismiss = {}, onCompleted = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(PaymentModalTags.TOTAL_TEXT).assertIsDisplayed()
        onNodeWithTag(PaymentModalTags.TENDER_CARD_BUTTON).assertIsDisplayed()
        onNodeWithTag(PaymentModalTags.TENDER_CASH_BUTTON).assertIsDisplayed()
        onNodeWithTag(PaymentModalTags.TENDER_GIFT_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `selecting cash shows the amount field and the confirm button stays disabled until it covers the total`() = runComposeUiTest {
        val viewModel = PaymentViewModel(FakeModalOrderApi(modalTestOrder(10.0)), FakeModalPaymentApi())

        setContent { PaymentModal(orderId = "order-1", onDismiss = {}, onCompleted = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(PaymentModalTags.TENDER_CASH_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(PaymentModalTags.CASH_AMOUNT_FIELD).assertIsDisplayed()
        onNodeWithTag(PaymentModalTags.CONFIRM_BUTTON).assertIsNotEnabled()

        onNodeWithTag(PaymentModalTags.CASH_AMOUNT_FIELD).performTextInput("15")
        waitForIdle()

        onNodeWithTag(PaymentModalTags.CHANGE_DUE_TEXT).assertIsDisplayed()
        assertEquals(5.0, viewModel.uiState.value.changeDue)
    }

    @Test
    fun `confirming a gift card payment completes the order`() = runComposeUiTest {
        val orderApi = FakeModalOrderApi(modalTestOrder(10.0))
        val viewModel = PaymentViewModel(orderApi, FakeModalPaymentApi())
        var completed: OrderResponse? = null

        setContent { PaymentModal(orderId = "order-1", onDismiss = {}, onCompleted = { completed = it }, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(PaymentModalTags.TENDER_GIFT_BUTTON).performClick()
        waitForIdle()
        onNodeWithTag(PaymentModalTags.CONFIRM_BUTTON).performClick()
        waitForIdle()

        assertEquals(Triple("GIFT_CARD", 10.0, null), orderApi.lastConfirmPayment)
        assertEquals("PAID", completed?.status)
    }

    @Test
    fun `starting the terminal shows the waiting state and disables switching tenders`() = runComposeUiTest {
        val paymentApi = FakeModalPaymentApi()
        val viewModel = PaymentViewModel(FakeModalOrderApi(modalTestOrder(10.0)), paymentApi)

        setContent { PaymentModal(orderId = "order-1", onDismiss = {}, onCompleted = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(PaymentModalTags.START_TERMINAL_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(PaymentModalTags.TERMINAL_STATE_TEXT).assertIsDisplayed()
        onNodeWithTag(PaymentModalTags.START_TERMINAL_BUTTON).assertIsNotEnabled()
        onNodeWithTag(PaymentModalTags.TENDER_CASH_BUTTON).assertIsNotEnabled()
        assertEquals(TerminalState.POLLING, viewModel.uiState.value.terminalState)

        onNodeWithTag(PaymentModalTags.START_TERMINAL_BUTTON).performClick()
        waitForIdle()
        assertEquals(1, paymentApi.createPaymentCalls, "clicking again while polling must not start a second payment")
    }

    @Test
    fun `an order that fails to load shows an error instead of the payment form`() = runComposeUiTest {
        val viewModel = PaymentViewModel(
            object : OrderApi {
                override suspend fun getOrder(id: String): GetOrderOutcome = GetOrderOutcome.NotFound
                override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?) =
                    error("not used in this test")
            },
            FakeModalPaymentApi()
        )

        setContent { PaymentModal(orderId = "order-1", onDismiss = {}, onCompleted = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(PaymentModalTags.LOAD_ERROR_TEXT).assertIsDisplayed()
        assertNull(viewModel.uiState.value.order)
    }
}
