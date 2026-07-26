package com.beettechnologies.posly.pos

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.orders.ConfirmPaymentOutcome
import com.beettechnologies.posly.orders.GetOrderOutcome
import com.beettechnologies.posly.orders.OrderApi
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.orders.RefundLineItemRequest
import com.beettechnologies.posly.orders.RefundLineItemResponse
import com.beettechnologies.posly.orders.RefundOutcome
import com.beettechnologies.posly.orders.RefundRecordResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private fun screenTestItem(id: String = "item-1", quantity: Int = 2) = CartItemResponse(
    id = id,
    productId = "product-1",
    productName = "Widget",
    quantity = quantity,
    unitPrice = 10.0,
    taxCategory = "STANDARD",
    selectedModifiers = emptyList(),
    discount = null,
    lineSubtotal = 10.0 * quantity,
    lineDiscountAmount = 0.0,
    lineTotal = 10.0 * quantity
)

private fun screenTestOrder() = OrderResponse(
    id = "order-1",
    cartId = "cart-1",
    storeId = "store-1",
    items = listOf(screenTestItem()),
    discount = null,
    totals = CartTotalsResponse(
        subtotal = 20.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 20.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 20.0
    ),
    idempotencyKey = "key-1",
    checkedOutAt = "2026-01-01T00:00:00Z",
    status = "PAID",
    amountPaid = 20.0,
    remainingBalance = 0.0,
    refunds = emptyList(),
    amountRefunded = 0.0,
    remainingRefundable = 20.0
)

/** [refundOutcome], when set, overrides the default success behavior for every [refund] call. */
private class FakeScreenOrderApi(
    private var order: OrderResponse,
    private val refundOutcome: RefundOutcome? = null
) : OrderApi {
    override suspend fun getOrder(id: String): GetOrderOutcome =
        if (id == order.id) GetOrderOutcome.Success(order) else GetOrderOutcome.NotFound

    override suspend fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?): ConfirmPaymentOutcome =
        error("not used in this test")

    override suspend fun refund(
        orderId: String,
        refundId: String,
        method: String,
        lineItems: List<RefundLineItemRequest>,
        reason: String?
    ): RefundOutcome {
        refundOutcome?.let { return it }
        val amount = lineItems.sumOf { it.quantity * 10.0 }
        order = order.copy(
            status = if (amount >= order.remainingRefundable - 0.001) "REFUNDED" else "PARTIALLY_REFUNDED",
            refunds = order.refunds + RefundRecordResponse(
                refundId = refundId, method = method,
                lineItems = lineItems.map { RefundLineItemResponse(it.cartItemId, it.quantity, it.quantity * 10.0, it.restock) },
                amount = amount, reason = reason, refundedBy = "manager-1", refundedAt = "2026-01-01T00:00:00Z"
            ),
            amountRefunded = order.amountRefunded + amount,
            remainingRefundable = (order.remainingRefundable - amount).coerceAtLeast(0.0)
        )
        return RefundOutcome.Success(order)
    }
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RefundScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading an order by id shows its refundable line and maximum refundable amount`() = runComposeUiTest {
        val viewModel = RefundViewModel(FakeScreenOrderApi(screenTestOrder()))

        setContent { RefundScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(RefundScreenTags.ORDER_ID_FIELD).performTextInput("order-1")
        onNodeWithTag(RefundScreenTags.LOAD_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(RefundScreenTags.MAX_REFUNDABLE_TEXT).assertIsDisplayed()
        onNodeWithTag(RefundScreenTags.LINE_PREFIX + "item-1").assertIsDisplayed()
        onNodeWithTag(RefundScreenTags.SUBMIT_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun `entering a quantity for a line enables the submit button`() = runComposeUiTest {
        val viewModel = RefundViewModel(FakeScreenOrderApi(screenTestOrder()))

        setContent { RefundScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(RefundScreenTags.ORDER_ID_FIELD).performTextInput("order-1")
        onNodeWithTag(RefundScreenTags.LOAD_BUTTON).performClick()
        waitForIdle()

        viewModel.updateLineQuantity("item-1", 1)
        waitForIdle()

        onNodeWithTag(RefundScreenTags.SUBMIT_BUTTON).assertIsEnabled()
    }

    @Test
    fun `switching to manual requires a reason before the submit button enables`() = runComposeUiTest {
        val viewModel = RefundViewModel(FakeScreenOrderApi(screenTestOrder()))

        setContent { RefundScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(RefundScreenTags.ORDER_ID_FIELD).performTextInput("order-1")
        onNodeWithTag(RefundScreenTags.LOAD_BUTTON).performClick()
        waitForIdle()
        viewModel.updateLineQuantity("item-1", 1)
        onNodeWithTag(RefundScreenTags.METHOD_MANUAL_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(RefundScreenTags.REASON_FIELD).assertIsDisplayed()
        onNodeWithTag(RefundScreenTags.SUBMIT_BUTTON).assertIsNotEnabled()

        onNodeWithTag(RefundScreenTags.REASON_FIELD).performTextInput("Customer request")
        waitForIdle()

        onNodeWithTag(RefundScreenTags.SUBMIT_BUTTON).assertIsEnabled()
    }

    @Test
    fun `submitting a full refund shows the completed outcome`() = runComposeUiTest {
        val viewModel = RefundViewModel(FakeScreenOrderApi(screenTestOrder()))

        setContent { RefundScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(RefundScreenTags.ORDER_ID_FIELD).performTextInput("order-1")
        onNodeWithTag(RefundScreenTags.LOAD_BUTTON).performClick()
        waitForIdle()
        viewModel.updateLineQuantity("item-1", 2)
        onNodeWithTag(RefundScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(RefundScreenTags.OUTCOME_TEXT).assertIsDisplayed()
        onNodeWithTag(RefundScreenTags.ORDER_STATUS_TEXT).assertIsDisplayed()
    }

    @Test
    fun `a card gateway failure offers a manual fallback that reveals the reason field`() = runComposeUiTest {
        val viewModel = RefundViewModel(
            FakeScreenOrderApi(screenTestOrder(), refundOutcome = RefundOutcome.GatewayError("The card terminal is unavailable"))
        )

        setContent { RefundScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(RefundScreenTags.ORDER_ID_FIELD).performTextInput("order-1")
        onNodeWithTag(RefundScreenTags.LOAD_BUTTON).performClick()
        waitForIdle()
        viewModel.updateLineQuantity("item-1", 1)
        onNodeWithTag(RefundScreenTags.SUBMIT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(RefundScreenTags.CARD_FAILED_TEXT).assertIsDisplayed()
        onNodeWithTag(RefundScreenTags.USE_MANUAL_FALLBACK_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(RefundScreenTags.METHOD_MANUAL_BUTTON).assertIsDisplayed()
        onNodeWithTag(RefundScreenTags.REASON_FIELD).assertIsDisplayed()
    }
}
