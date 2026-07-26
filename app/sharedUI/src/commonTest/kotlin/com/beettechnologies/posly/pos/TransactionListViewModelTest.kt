package com.beettechnologies.posly.pos

import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.ConfirmPaymentOutcome
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.ListOrdersOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.RefundLineItemRequest
import com.beettechnologies.posly.orders.RefundOutcome
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
import kotlin.test.assertTrue

private fun testItem(productId: String = "product-1", productName: String = "Widget") = CartItemResponse(
    id = "item-1",
    productId = productId,
    productName = productName,
    quantity = 1,
    unitPrice = 10.0,
    taxCategory = "STANDARD",
    selectedModifiers = emptyList(),
    discount = null,
    lineSubtotal = 10.0,
    lineDiscountAmount = 0.0,
    lineTotal = 10.0
)

private fun testOrder(id: String, items: List<CartItemResponse>) = OrderResponse(
    id = id,
    cartId = "cart-$id",
    storeId = "store-1",
    items = items,
    discount = null,
    totals = CartTotalsResponse(
        subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 10.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 10.0
    ),
    idempotencyKey = "key-$id",
    checkedOutAt = "2026-01-01T10:00:00Z",
    status = "PAID"
)

private class FakeTransactionOrderApi(private val outcome: ListOrdersOutcome) : OrderApi {
    override suspend fun getOrder(id: String): GetOrderOutcome = error("not used in this test")
    override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?): ConfirmPaymentOutcome =
        error("not used in this test")
    override suspend fun refund(
        orderId: String,
        refundId: String,
        method: String,
        lineItems: List<RefundLineItemRequest>,
        reason: String?
    ): RefundOutcome = error("not used in this test")

    override suspend fun listOrders(storeId: String, from: String, to: String): ListOrdersOutcome = outcome
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionListViewModelTest {

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
    fun `initialize loads every order in the window when no product filter is given`() = runTest(dispatcher) {
        val orders = listOf(testOrder("order-1", listOf(testItem("product-1"))), testOrder("order-2", listOf(testItem("product-2"))))
        val viewModel = TransactionListViewModel(FakeTransactionOrderApi(ListOrdersOutcome.Success(orders)))

        viewModel.initialize("store-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.orders.size)
        assertNull(viewModel.uiState.value.filterProductName)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `initialize with a productId narrows to orders containing that product`() = runTest(dispatcher) {
        val orders = listOf(
            testOrder("order-1", listOf(testItem("product-1", "Widget"))),
            testOrder("order-2", listOf(testItem("product-2", "Gadget")))
        )
        val viewModel = TransactionListViewModel(FakeTransactionOrderApi(ListOrdersOutcome.Success(orders)))

        viewModel.initialize("store-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", productId = "product-1", productName = "Widget")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.orders.size)
        assertEquals("order-1", viewModel.uiState.value.orders.first().id)
        assertEquals("Widget", viewModel.uiState.value.filterProductName)
    }

    @Test
    fun `a forbidden response surfaces a permission error`() = runTest(dispatcher) {
        val viewModel = TransactionListViewModel(FakeTransactionOrderApi(ListOrdersOutcome.Forbidden))

        viewModel.initialize("store-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
        advanceUntilIdle()

        assertEquals("You don't have permission to view transactions", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.orders.isEmpty())
    }

    @Test
    fun `a second initialize call with different args is ignored once already initialized`() = runTest(dispatcher) {
        val orders = listOf(testOrder("order-1", listOf(testItem())))
        val viewModel = TransactionListViewModel(FakeTransactionOrderApi(ListOrdersOutcome.Success(orders)))

        viewModel.initialize("store-1", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")
        advanceUntilIdle()
        viewModel.initialize("store-2", "2026-02-01T00:00:00Z", "2026-02-02T00:00:00Z", productId = "product-9", productName = "Ignored")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.filterProductName)
        assertEquals(1, viewModel.uiState.value.orders.size)
    }
}
