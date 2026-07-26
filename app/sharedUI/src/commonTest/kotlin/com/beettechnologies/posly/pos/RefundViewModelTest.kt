package com.beettechnologies.posly.pos

import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.ConfirmPaymentOutcome
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.ListOrdersOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.RefundLineItemRequest
import com.beettechnologies.posly.orders.RefundLineItemResponse
import com.beettechnologies.posly.orders.RefundOutcome
import com.beettechnologies.posly.orders.RefundRecordResponse
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun testItem(id: String = "item-1", quantity: Int = 2, unitPrice: Double = 10.0) = CartItemResponse(
    id = id,
    productId = "product-1",
    productName = "Widget",
    quantity = quantity,
    unitPrice = unitPrice,
    taxCategory = "STANDARD",
    selectedModifiers = emptyList(),
    discount = null,
    lineSubtotal = unitPrice * quantity,
    lineDiscountAmount = 0.0,
    lineTotal = unitPrice * quantity
)

private fun testOrder(
    items: List<CartItemResponse> = listOf(testItem()),
    status: String = "PAID",
    refunds: List<RefundRecordResponse> = emptyList(),
    amountRefunded: Double = 0.0,
    remainingRefundable: Double = 20.0
) = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = items,
    discount = null,
    totals = CartTotalsResponse(
        subtotal = 20.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 20.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 20.0
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T00:00:00Z",
    status = status,
    amountPaid = 20.0,
    remainingBalance = 0.0,
    refunds = refunds,
    amountRefunded = amountRefunded,
    remainingRefundable = remainingRefundable
)

private class FakeRefundOrderApi(
    private var order: OrderResponse,
    private val refundOutcome: RefundOutcome? = null
) : OrderApi {
    var refundCalls = 0
    var lastRefundId: String? = null
    var lastRefund: List<RefundLineItemRequest>? = null
    var lastMethod: String? = null
    var lastReason: String? = null

    override suspend fun getOrder(id: String): GetOrderOutcome = GetOrderOutcome.Success(order)

    override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?): ConfirmPaymentOutcome =
        error("not used in this test")

    override suspend fun refund(
        orderId: String,
        refundId: String,
        method: String,
        lineItems: List<RefundLineItemRequest>,
        reason: String?
    ): RefundOutcome {
        refundCalls++
        lastRefundId = refundId
        lastRefund = lineItems
        lastMethod = method
        lastReason = reason
        refundOutcome?.let { return it }

        val refundedAmount = lineItems.sumOf { it.quantity * 10.0 }
        val newRefunds = order.refunds + RefundRecordResponse(
            refundId = refundId,
            method = method,
            lineItems = lineItems.map { RefundLineItemResponse(it.cartItemId, it.quantity, it.quantity * 10.0, it.restock) },
            amount = refundedAmount,
            reason = reason,
            refundedBy = "manager-1",
            refundedAt = "2026-01-01T00:00:00Z"
        )
        val newAmountRefunded = order.amountRefunded + refundedAmount
        val newRemaining = (order.remainingRefundable - refundedAmount).coerceAtLeast(0.0)
        order = order.copy(
            status = if (newRemaining <= 0.001) "REFUNDED" else "PARTIALLY_REFUNDED",
            refunds = newRefunds,
            amountRefunded = newAmountRefunded,
            remainingRefundable = newRemaining
        )
        return RefundOutcome.Success(order)
    }

    override suspend fun listOrders(storeId: String, from: String, to: String): ListOrdersOutcome = error("not used in this test")
}

@OptIn(ExperimentalCoroutinesApi::class)
class RefundViewModelTest {

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
    fun `loading an order populates refundable lines with available quantity`() = runTest(dispatcher) {
        val viewModel = RefundViewModel(FakeRefundOrderApi(testOrder()))

        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("order-1", state.order?.id)
        assertEquals(1, state.lines.size)
        assertEquals(2, state.lines.single().availableQuantity)
        assertEquals(20.0, state.maxRefundableAmount)
    }

    @Test
    fun `a line already fully refunded is excluded from the refundable list`() = runTest(dispatcher) {
        val order = testOrder(
            refunds = listOf(
                RefundRecordResponse(
                    refundId = "refund-0", method = "MANUAL",
                    lineItems = listOf(RefundLineItemResponse("item-1", 2, 20.0, false)),
                    amount = 20.0, reason = "x", refundedBy = "manager-1", refundedAt = "2026-01-01T00:00:00Z"
                )
            ),
            amountRefunded = 20.0,
            remainingRefundable = 0.0
        )
        val viewModel = RefundViewModel(FakeRefundOrderApi(order))

        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.lines.isEmpty())
    }

    @Test
    fun `an unknown order shows a load error`() = runTest(dispatcher) {
        val orderApi = object : OrderApi {
            override suspend fun getOrder(id: String) = GetOrderOutcome.NotFound
            override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?) =
                error("not used in this test")
            override suspend fun refund(orderId: String, refundId: String, method: String, lineItems: List<RefundLineItemRequest>, reason: String?) =
                error("not used in this test")
            override suspend fun listOrders(storeId: String, from: String, to: String): ListOrdersOutcome = error("not used in this test")
        }
        val viewModel = RefundViewModel(orderApi)

        viewModel.updateOrderIdInput("does-not-exist")
        viewModel.loadOrder()
        advanceUntilIdle()

        assertEquals("Order not found", viewModel.uiState.value.loadError)
        assertNull(viewModel.uiState.value.order)
    }

    @Test
    fun `submit is disabled until at least one line has a positive quantity`() = runTest(dispatcher) {
        val viewModel = RefundViewModel(FakeRefundOrderApi(testOrder()))
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.updateLineQuantity("item-1", 1)
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `a manual refund requires a non-blank reason before it can submit`() = runTest(dispatcher) {
        val viewModel = RefundViewModel(FakeRefundOrderApi(testOrder()))
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()
        viewModel.updateLineQuantity("item-1", 1)
        viewModel.selectMethod("MANUAL")

        assertFalse(viewModel.uiState.value.canSubmit)

        viewModel.updateReason("Customer request")
        assertTrue(viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `a line quantity is clamped to the available quantity`() = runTest(dispatcher) {
        val viewModel = RefundViewModel(FakeRefundOrderApi(testOrder(items = listOf(testItem(quantity = 2)))))
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()

        viewModel.updateLineQuantity("item-1", 5)

        assertEquals(2, viewModel.uiState.value.lines.single().selectedQuantity)
    }

    @Test
    fun `submitting a partial refund sends only the selected line and reduces remaining availability`() = runTest(dispatcher) {
        val orderApi = FakeRefundOrderApi(testOrder(items = listOf(testItem(quantity = 2))))
        val viewModel = RefundViewModel(orderApi)
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()
        viewModel.updateLineQuantity("item-1", 1)
        viewModel.toggleRestock("item-1")

        viewModel.submit()
        advanceUntilIdle()

        assertEquals(listOf(RefundLineItemRequest("item-1", 1, restock = true)), orderApi.lastRefund)
        assertEquals("CARD", orderApi.lastMethod)
        val state = viewModel.uiState.value
        assertEquals("PARTIALLY_REFUNDED", state.completedOrder?.status)
        assertEquals(1, state.lines.single().availableQuantity, "the remaining unrefunded unit must still be selectable")
    }

    @Test
    fun `submitting a full refund completes the order`() = runTest(dispatcher) {
        val orderApi = FakeRefundOrderApi(testOrder(items = listOf(testItem(quantity = 2))))
        val viewModel = RefundViewModel(orderApi)
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()
        viewModel.updateLineQuantity("item-1", 2)

        viewModel.submit()
        advanceUntilIdle()

        assertEquals("REFUNDED", viewModel.uiState.value.completedOrder?.status)
        assertTrue(viewModel.uiState.value.lines.isEmpty(), "a fully refunded order has nothing left to refund")
    }

    @Test
    fun `a card gateway failure prompts a manual fallback that reuses the same refundId`() = runTest(dispatcher) {
        val orderApi = FakeRefundOrderApi(testOrder(), refundOutcome = RefundOutcome.GatewayError("The card terminal is unavailable"))
        val viewModel = RefundViewModel(orderApi)
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()
        viewModel.updateLineQuantity("item-1", 1)

        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.cardFailed)
        assertEquals("The card terminal is unavailable", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.completedOrder)

        viewModel.useManualFallback()
        assertEquals("MANUAL", viewModel.uiState.value.method)
        assertFalse(viewModel.uiState.value.cardFailed)
        val firstAttemptRefundId = orderApi.lastRefundId

        viewModel.updateReason("Card unavailable, processed manually")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals(firstAttemptRefundId, orderApi.lastRefundId, "the fallback must complete the same refund attempt, not start a new one")
        assertEquals("MANUAL", orderApi.lastMethod)
    }

    @Test
    fun `a rejected refund surfaces the server's message without completing`() = runTest(dispatcher) {
        val orderApi = FakeRefundOrderApi(testOrder(), refundOutcome = RefundOutcome.Rejected("The refund window for this order has expired"))
        val viewModel = RefundViewModel(orderApi)
        viewModel.updateOrderIdInput("order-1")
        viewModel.loadOrder()
        advanceUntilIdle()
        viewModel.updateLineQuantity("item-1", 1)

        viewModel.submit()
        advanceUntilIdle()

        assertEquals("The refund window for this order has expired", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.cardFailed, "a validation rejection is not a gateway failure - no manual-fallback prompt")
        assertNull(viewModel.uiState.value.completedOrder)
    }
}
