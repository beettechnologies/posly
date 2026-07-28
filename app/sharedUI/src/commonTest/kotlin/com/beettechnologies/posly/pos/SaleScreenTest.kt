package com.beettechnologies.posly.pos

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.beettechnologies.posly.accessibility.hasLiveRegion
import com.beettechnologies.posly.accessibility.hasOnClickLabel
import com.beettechnologies.posly.accessibility.hasRole
import com.beettechnologies.posly.cart.AddCartItemOutcome
import com.beettechnologies.posly.cart.CartApi
import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartResponse
import com.beettechnologies.posly.cart.CartSessionStore
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.cart.CheckoutOutcome
import com.beettechnologies.posly.cart.CreateCartOutcome
import com.beettechnologies.posly.cart.DiscountDto
import com.beettechnologies.posly.cart.GetCartOutcome
import com.beettechnologies.posly.cart.RemoveCartItemOutcome
import com.beettechnologies.posly.cart.SelectedModifierRequest
import com.beettechnologies.posly.cart.SetCartDiscountOutcome
import com.beettechnologies.posly.cart.TaxBreakdownLineResponse
import com.beettechnologies.posly.cart.UpdateCartItemQuantityOutcome
import com.beettechnologies.posly.devices.DeviceCredentials
import com.beettechnologies.posly.devices.DeviceCredentialsStore
import com.beettechnologies.posly.products.ProductSearchApi
import com.beettechnologies.posly.products.SearchOutcome
import com.beettechnologies.posly.products.SearchResponse
import com.beettechnologies.posly.products.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private fun emptyTotals() = CartTotalsResponse(
    subtotal = 0.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
    taxableAmount = 0.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 0.0
)

private fun screenCartItem(id: String, name: String, quantity: Int, unitPrice: Double = 5.0) = CartItemResponse(
    id = id,
    productId = "p-$id",
    productName = name,
    quantity = quantity,
    unitPrice = unitPrice,
    taxCategory = "STANDARD",
    selectedModifiers = emptyList(),
    discount = null,
    lineSubtotal = unitPrice * quantity,
    lineDiscountAmount = 0.0,
    lineTotal = unitPrice * quantity
)

private fun screenCart(items: List<CartItemResponse>, discount: DiscountDto? = null, totals: CartTotalsResponse = emptyTotals()) = CartResponse(
    id = "cart-1",
    storeId = "store-1",
    status = "OPEN",
    items = items,
    discount = discount,
    totals = totals,
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z"
)

private class FakeScreenDeviceCredentialsStore : DeviceCredentialsStore {
    override suspend fun isPaired(): Boolean = true
    override suspend fun getCredentials(): DeviceCredentials? =
        DeviceCredentials("device-1", "store-1", "client-1", "secret-1")
    override suspend fun saveCredentials(credentials: DeviceCredentials) = error("not used in these tests")
    override suspend fun clear() = error("not used in these tests")
}

private class FakeScreenCartSessionStore(private var cartId: String?) : CartSessionStore {
    override suspend fun getCurrentCartId(): String? = cartId
    override suspend fun setCurrentCartId(id: String?) {
        cartId = id
    }
}

private class FakeScreenProductSearchApi : ProductSearchApi {
    override suspend fun search(
        query: String?,
        barcode: String?,
        category: String?,
        inStock: Boolean?,
        page: Int,
        size: Int
    ): SearchOutcome = SearchOutcome.Success(SearchResponse(results = emptyList(), page = 0, size = 20, total = 0))
}

/** A minimal, self-consistent in-memory cart used to drive the screen through real quantity/void/discount round-trips. */
private class FakeScreenCartApi(initialCart: CartResponse) : CartApi {
    private var currentCart = initialCart

    override suspend fun createCart(storeId: String): CreateCartOutcome = CreateCartOutcome.Success(currentCart)

    override suspend fun getCart(id: String): GetCartOutcome = GetCartOutcome.Success(currentCart)

    override suspend fun addItem(
        cartId: String,
        productId: String,
        quantity: Int,
        selectedModifiers: List<SelectedModifierRequest>,
        discount: DiscountDto?
    ): AddCartItemOutcome {
        val newItem = screenCartItem(id = "item-${currentCart.items.size + 1}", name = "Restored Item", quantity = quantity)
        currentCart = currentCart.copy(items = currentCart.items + newItem)
        return AddCartItemOutcome.Success(currentCart)
    }

    override suspend fun updateItemQuantity(cartId: String, itemId: String, quantity: Int): UpdateCartItemQuantityOutcome {
        currentCart = currentCart.copy(
            items = currentCart.items.map {
                if (it.id == itemId) it.copy(quantity = quantity, lineTotal = it.unitPrice * quantity) else it
            }
        )
        return UpdateCartItemQuantityOutcome.Success(currentCart)
    }

    override suspend fun removeItem(cartId: String, itemId: String, reason: String?): RemoveCartItemOutcome {
        currentCart = currentCart.copy(items = currentCart.items.filterNot { it.id == itemId })
        return RemoveCartItemOutcome.Success(currentCart)
    }

    override suspend fun setCartDiscount(cartId: String, discount: DiscountDto?): SetCartDiscountOutcome {
        currentCart = currentCart.copy(discount = discount)
        return SetCartDiscountOutcome.Success(currentCart)
    }

    override suspend fun checkout(cartId: String, idempotencyKey: String): CheckoutOutcome =
        error("not used in these tests")
}

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SaleScreenTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelWith(cart: CartResponse) = SaleViewModel(
        FakeScreenDeviceCredentialsStore(),
        FakeScreenCartSessionStore(cartId = cart.id),
        FakeScreenCartApi(cart),
        FakeScreenProductSearchApi()
    )

    @Test
    fun `a cart item shows its name, quantity, and line total`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 2, unitPrice = 5.0)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.CART_ITEM_PREFIX + "item-1").assertIsDisplayed()
        onNodeWithTag(SaleScreenTags.ITEM_QUANTITY_TEXT_PREFIX + "item-1").assertTextEquals("2")
    }

    @Test
    fun `incrementing quantity updates the displayed count and persists it`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.ITEM_QUANTITY_INCREMENT_PREFIX + "item-1").performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.ITEM_QUANTITY_TEXT_PREFIX + "item-1").assertTextEquals("2")
    }

    @Test
    fun `the decrement button is disabled at a quantity of one`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.ITEM_QUANTITY_DECREMENT_PREFIX + "item-1").assertIsNotEnabled()
    }

    @Test
    fun `voiding an item with a reason removes it and shows an undo banner`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.ITEM_VOID_BUTTON_PREFIX + "item-1").performClick()
        waitForIdle()
        onNodeWithTag(SaleScreenTags.VOID_REASON_FIELD).performTextInput("Customer changed their mind")
        onNodeWithTag(SaleScreenTags.VOID_CONFIRM_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.CART_ITEM_PREFIX + "item-1").assertDoesNotExist()
        onNodeWithTag(SaleScreenTags.UNDO_BANNER).assertIsDisplayed()
    }

    @Test
    fun `canceling the void dialog leaves the item in the cart`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.ITEM_VOID_BUTTON_PREFIX + "item-1").performClick()
        waitForIdle()
        onNodeWithTag(SaleScreenTags.VOID_CANCEL_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.CART_ITEM_PREFIX + "item-1").assertIsDisplayed()
        onNodeWithTag(SaleScreenTags.VOID_REASON_FIELD).assertDoesNotExist()
    }

    @Test
    fun `undo after a void restores the item and hides the banner`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(SaleScreenTags.ITEM_VOID_BUTTON_PREFIX + "item-1").performClick()
        waitForIdle()
        onNodeWithTag(SaleScreenTags.VOID_CONFIRM_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.UNDO_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.UNDO_BANNER).assertDoesNotExist()
        onNodeWithTag(SaleScreenTags.CART_ITEM_PREFIX + "item-1").assertIsDisplayed()
    }

    @Test
    fun `applying a quick discount shows the clear-discount option`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.QUICK_DISCOUNT_PREFIX + "10").performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.CLEAR_DISCOUNT_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `clearing a discount hides the clear-discount option`() = runComposeUiTest {
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)))
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()
        onNodeWithTag(SaleScreenTags.QUICK_DISCOUNT_PREFIX + "10").performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.CLEAR_DISCOUNT_BUTTON).performClick()
        waitForIdle()

        onNodeWithTag(SaleScreenTags.CLEAR_DISCOUNT_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `each backend tax breakdown line is displayed verbatim, in order, alongside the total`() = runComposeUiTest {
        val totals = CartTotalsResponse(
            subtotal = 200.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0, taxableAmount = 200.0,
            taxBreakdown = listOf(
                TaxBreakdownLineResponse("GST", 5.0, 10.0),
                TaxBreakdownLineResponse("PST", 7.0, 14.7)
            ),
            totalTax = 24.7, total = 224.7
        )
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)), totals = totals)
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.TAX_LINE_PREFIX + 0).assertTextEquals("GST (5.0%): \$10.00")
        onNodeWithTag(SaleScreenTags.TAX_LINE_PREFIX + 1).assertTextEquals("PST (7.0%): \$14.70")
        onNodeWithTag(SaleScreenTags.TAX_TOTAL_TEXT).assertTextEquals("Tax: \$24.70")
        onNodeWithTag(SaleScreenTags.TOTAL_TEXT).assertTextEquals("Total: \$224.70")
    }

    @Test
    fun `an all-exempt cart shows no breakdown lines and zero tax`() = runComposeUiTest {
        val totals = CartTotalsResponse(
            subtotal = 50.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0, taxableAmount = 0.0,
            taxBreakdown = emptyList(), totalTax = 0.0, total = 50.0
        )
        val cart = screenCart(items = listOf(screenCartItem(id = "item-1", name = "Widget", quantity = 1)), totals = totals)
        val viewModel = viewModelWith(cart)

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        waitForIdle()

        onNodeWithTag(SaleScreenTags.TAX_LINE_PREFIX + 0).assertDoesNotExist()
        onNodeWithTag(SaleScreenTags.TAX_TOTAL_TEXT).assertTextEquals("Tax: \$0.00")
    }

    // -------------------------------------------------------------------------
    // Accessibility
    // -------------------------------------------------------------------------

    @Test
    fun `a search suggestion is announced as a button with a descriptive action label`() = runComposeUiTest {
        val cart = screenCart(items = emptyList())
        val searchApi = object : ProductSearchApi {
            override suspend fun search(
                query: String?,
                barcode: String?,
                category: String?,
                inStock: Boolean?,
                page: Int,
                size: Int
            ): SearchOutcome = SearchOutcome.Success(
                SearchResponse(
                    results = listOf(SearchResultItem(id = "p-1", sku = "SKU-1", name = "Widget", price = 9.99)),
                    page = 0, size = 20, total = 1
                )
            )
        }
        val viewModel = SaleViewModel(
            FakeScreenDeviceCredentialsStore(),
            FakeScreenCartSessionStore(cartId = cart.id),
            FakeScreenCartApi(cart),
            searchApi,
            debounceMillis = 0
        )

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        onNodeWithTag(SaleScreenTags.SEARCH_FIELD).performTextInput("Widget")
        waitForIdle()

        onNodeWithTag(SaleScreenTags.SUGGESTION_PREFIX + "p-1")
            .assert(hasRole(Role.Button))
            .assert(hasOnClickLabel("Add Widget to cart"))
    }

    @Test
    fun `the search error message is a live region so it's announced without navigating to it`() = runComposeUiTest {
        val cart = screenCart(items = emptyList())
        val searchApi = object : ProductSearchApi {
            override suspend fun search(
                query: String?,
                barcode: String?,
                category: String?,
                inStock: Boolean?,
                page: Int,
                size: Int
            ): SearchOutcome = SearchOutcome.NetworkError("Search is temporarily unavailable")
        }
        val viewModel = SaleViewModel(
            FakeScreenDeviceCredentialsStore(),
            FakeScreenCartSessionStore(cartId = cart.id),
            FakeScreenCartApi(cart),
            searchApi,
            debounceMillis = 0
        )

        setContent { SaleScreen(onBack = {}, viewModel = viewModel) }
        onNodeWithTag(SaleScreenTags.SEARCH_FIELD).performTextInput("Widget")
        waitForIdle()

        onNodeWithTag(SaleScreenTags.ERROR_TEXT).assert(hasLiveRegion())
    }
}
