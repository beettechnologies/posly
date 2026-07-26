package com.beettechnologies.posly.pos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private fun screenTestItem() = CartItemResponse(
    id = "item-1",
    productId = "product-1",
    productName = "Widget",
    quantity = 1,
    unitPrice = 10.0,
    taxCategory = "STANDARD",
    selectedModifiers = emptyList(),
    discount = null,
    lineSubtotal = 10.0,
    lineDiscountAmount = 0.0,
    lineTotal = 10.0
)

private fun screenTestOrder() = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = listOf(screenTestItem()),
    discount = null,
    totals = CartTotalsResponse(
        subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 10.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 10.0
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T10:00:00Z",
    status = "PAID"
)

private class FakeTransactionScreenOrderApi(private val outcome: ListOrdersOutcome) : OrderApi {
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

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class TransactionListScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `orders in the window render as rows`() = runComposeUiTest {
        val viewModel = TransactionListViewModel(FakeTransactionScreenOrderApi(ListOrdersOutcome.Success(listOf(screenTestOrder()))))

        setContent {
            TransactionListScreen(
                storeId = "store-1",
                from = "2026-01-01T00:00:00Z",
                to = "2026-01-02T00:00:00Z",
                productId = null,
                productName = null,
                onBack = {},
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(TransactionListScreenTags.ORDER_ROW_PREFIX + "order-1").assertIsDisplayed()
    }

    @Test
    fun `a product drill-down shows the filter label`() = runComposeUiTest {
        val viewModel = TransactionListViewModel(FakeTransactionScreenOrderApi(ListOrdersOutcome.Success(listOf(screenTestOrder()))))

        setContent {
            TransactionListScreen(
                storeId = "store-1",
                from = "2026-01-01T00:00:00Z",
                to = "2026-01-02T00:00:00Z",
                productId = "product-1",
                productName = "Widget",
                onBack = {},
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(TransactionListScreenTags.FILTER_TEXT).assertIsDisplayed()
    }

    @Test
    fun `an empty window shows the empty-state text`() = runComposeUiTest {
        val viewModel = TransactionListViewModel(FakeTransactionScreenOrderApi(ListOrdersOutcome.Success(emptyList())))

        setContent {
            TransactionListScreen(
                storeId = "store-1",
                from = "2026-01-01T00:00:00Z",
                to = "2026-01-02T00:00:00Z",
                productId = null,
                productName = null,
                onBack = {},
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(TransactionListScreenTags.EMPTY_TEXT).assertIsDisplayed()
    }

    @Test
    fun `a forbidden response shows the error text`() = runComposeUiTest {
        val viewModel = TransactionListViewModel(FakeTransactionScreenOrderApi(ListOrdersOutcome.Forbidden))

        setContent {
            TransactionListScreen(
                storeId = "store-1",
                from = "2026-01-01T00:00:00Z",
                to = "2026-01-02T00:00:00Z",
                productId = null,
                productName = null,
                onBack = {},
                viewModel = viewModel
            )
        }
        waitForIdle()

        onNodeWithTag(TransactionListScreenTags.ERROR_TEXT).assertIsDisplayed()
    }
}
