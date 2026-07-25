package com.beettechnologies.posly.pos

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
import com.beettechnologies.posly.cart.UpdateCartItemQuantityOutcome
import com.beettechnologies.posly.devices.DeviceCredentials
import com.beettechnologies.posly.devices.DeviceCredentialsStore
import com.beettechnologies.posly.orders.OrderResponse
import com.beettechnologies.posly.products.ProductSearchApi
import com.beettechnologies.posly.products.SearchOutcome
import com.beettechnologies.posly.products.SearchResponse
import com.beettechnologies.posly.products.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

private fun emptyTotals() = CartTotalsResponse(
    subtotal = 0.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
    taxableAmount = 0.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 0.0
)

private fun cart(id: String = "cart-1", items: List<CartItemResponse> = emptyList(), status: String = "OPEN") = CartResponse(
    id = id,
    storeId = "store-1",
    status = status,
    items = items,
    discount = null,
    totals = emptyTotals(),
    createdAt = "2026-01-01T00:00:00Z",
    updatedAt = "2026-01-01T00:00:00Z"
)

private fun product(id: String, name: String, price: Double = 5.0, barcode: String? = null) = SearchResultItem(
    id = id, sku = "SKU-$id", name = name, price = price, category = null, inStock = true, barcode = barcode
)

private fun cartItem(
    id: String = "item-1",
    productId: String = "p1",
    productName: String = "Widget",
    quantity: Int = 1,
    unitPrice: Double = 5.0
) = CartItemResponse(
    id = id,
    productId = productId,
    productName = productName,
    quantity = quantity,
    unitPrice = unitPrice,
    taxCategory = "STANDARD",
    selectedModifiers = emptyList(),
    discount = null,
    lineSubtotal = unitPrice * quantity,
    lineDiscountAmount = 0.0,
    lineTotal = unitPrice * quantity
)

private class FakeDeviceCredentialsStore(private val storeId: String? = "store-1") : DeviceCredentialsStore {
    override suspend fun isPaired(): Boolean = storeId != null
    override suspend fun getCredentials(): DeviceCredentials? =
        storeId?.let { DeviceCredentials("device-1", it, "client-1", "secret-1") }
    override suspend fun saveCredentials(credentials: DeviceCredentials) = error("not used in these tests")
    override suspend fun clear() = error("not used in these tests")
}

private class FakeCartSessionStore(private var cartId: String? = null) : CartSessionStore {
    override suspend fun getCurrentCartId(): String? = cartId
    override suspend fun setCurrentCartId(id: String?) {
        cartId = id
    }
}

private class FakeCartApi(
    private var currentCart: CartResponse = cart(),
    private val getCartOutcome: GetCartOutcome? = null,
    private val updateQuantityOutcome: UpdateCartItemQuantityOutcome? = null,
    private val removeItemOutcome: RemoveCartItemOutcome? = null,
    private val checkoutOutcome: CheckoutOutcome? = null
) : CartApi {
    var createCartCalls = 0
    var addItemCalls = 0
    var checkoutCalls = 0
    var lastUpdateQuantityCall: Pair<String, Int>? = null
    var lastRemoveItemCall: Pair<String, String?>? = null
    var lastSetDiscountCall: DiscountDto? = null
    var lastCheckoutIdempotencyKey: String? = null

    override suspend fun createCart(storeId: String): CreateCartOutcome {
        createCartCalls++
        currentCart = cart(id = "cart-${createCartCalls + 1}", items = emptyList())
        return CreateCartOutcome.Success(currentCart)
    }

    override suspend fun getCart(id: String): GetCartOutcome = getCartOutcome ?: GetCartOutcome.Success(currentCart)

    override suspend fun addItem(
        cartId: String,
        productId: String,
        quantity: Int,
        selectedModifiers: List<SelectedModifierRequest>,
        discount: DiscountDto?
    ): AddCartItemOutcome {
        addItemCalls++
        val newItem = CartItemResponse(
            id = "item-${currentCart.items.size + 1}",
            productId = productId,
            productName = "Product $productId",
            quantity = quantity,
            unitPrice = 5.0,
            taxCategory = "STANDARD",
            selectedModifiers = emptyList(),
            discount = null,
            lineSubtotal = 5.0 * quantity,
            lineDiscountAmount = 0.0,
            lineTotal = 5.0 * quantity
        )
        currentCart = currentCart.copy(items = currentCart.items + newItem)
        return AddCartItemOutcome.Success(currentCart)
    }

    override suspend fun updateItemQuantity(cartId: String, itemId: String, quantity: Int): UpdateCartItemQuantityOutcome {
        lastUpdateQuantityCall = itemId to quantity
        updateQuantityOutcome?.let { return it }
        currentCart = currentCart.copy(
            items = currentCart.items.map {
                if (it.id == itemId) {
                    it.copy(quantity = quantity, lineSubtotal = it.unitPrice * quantity, lineTotal = it.unitPrice * quantity)
                } else {
                    it
                }
            }
        )
        return UpdateCartItemQuantityOutcome.Success(currentCart)
    }

    override suspend fun removeItem(cartId: String, itemId: String, reason: String?): RemoveCartItemOutcome {
        lastRemoveItemCall = itemId to reason
        removeItemOutcome?.let { return it }
        currentCart = currentCart.copy(items = currentCart.items.filterNot { it.id == itemId })
        return RemoveCartItemOutcome.Success(currentCart)
    }

    override suspend fun setCartDiscount(cartId: String, discount: DiscountDto?): SetCartDiscountOutcome {
        lastSetDiscountCall = discount
        currentCart = currentCart.copy(discount = discount)
        return SetCartDiscountOutcome.Success(currentCart)
    }

    override suspend fun checkout(cartId: String, idempotencyKey: String): CheckoutOutcome {
        checkoutCalls++
        lastCheckoutIdempotencyKey = idempotencyKey
        checkoutOutcome?.let { return it }
        currentCart = currentCart.copy(status = "CHECKED_OUT")
        val order = OrderResponse(
            id = "order-1",
            cartId = cartId,
            storeId = currentCart.storeId,
            items = currentCart.items,
            discount = currentCart.discount,
            totals = currentCart.totals,
            idempotencyKey = idempotencyKey,
            checkedOutAt = "2026-01-01T00:00:00Z",
            status = "PENDING"
        )
        return CheckoutOutcome.Success(order, replayed = false)
    }
}

private class FakeProductSearchApi(
    private val resultsByQuery: Map<String, List<SearchResultItem>> = emptyMap(),
    private val resultsByBarcode: Map<String, List<SearchResultItem>> = emptyMap()
) : ProductSearchApi {
    val searchCalls = mutableListOf<Pair<String?, String?>>()

    override suspend fun search(
        query: String?,
        barcode: String?,
        category: String?,
        inStock: Boolean?,
        page: Int,
        size: Int
    ): SearchOutcome {
        searchCalls += query to barcode
        val results = when {
            barcode != null -> resultsByBarcode[barcode].orEmpty()
            query != null -> resultsByQuery[query].orEmpty()
            else -> emptyList()
        }
        return SearchOutcome.Success(SearchResponse(results = results, page = 0, size = 20, total = results.size))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SaleViewModelTest {

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
    fun `typing quickly only fires one search after the debounce settles`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi(resultsByQuery = mapOf("widget" to listOf(product("p1", "Widget"))))
        val viewModel = SaleViewModel(
            FakeDeviceCredentialsStore(), FakeCartSessionStore(), FakeCartApi(), searchApi, debounceMillis = 300L
        )
        advanceUntilIdle()

        viewModel.onQueryChange("w")
        advanceTimeBy(100)
        viewModel.onQueryChange("wi")
        advanceTimeBy(100)
        viewModel.onQueryChange("widget")
        advanceTimeBy(350)

        assertEquals(1, searchApi.searchCalls.size, "only the settled query should trigger a search")
        assertEquals("widget" to null, searchApi.searchCalls.single())
        assertEquals(listOf("Widget"), viewModel.uiState.value.suggestions.map { it.name })
    }

    @Test
    fun `no search fires before the debounce window elapses`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi(resultsByQuery = mapOf("widget" to listOf(product("p1", "Widget"))))
        val viewModel = SaleViewModel(
            FakeDeviceCredentialsStore(), FakeCartSessionStore(), FakeCartApi(), searchApi, debounceMillis = 300L
        )
        advanceUntilIdle()

        viewModel.onQueryChange("widget")
        advanceTimeBy(299)

        assertEquals(0, searchApi.searchCalls.size)
    }

    @Test
    fun `selecting a suggestion opens the product detail modal instead of adding directly`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi(resultsByQuery = mapOf("widget" to listOf(product("p1", "Widget"))))
        val cartApi = FakeCartApi()
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), cartApi, searchApi, debounceMillis = 300L)
        advanceUntilIdle()
        viewModel.onQueryChange("widget")
        advanceTimeBy(300)
        advanceUntilIdle()

        val suggestion = viewModel.uiState.value.suggestions.single()
        viewModel.onSuggestionSelected(suggestion)

        val state = viewModel.uiState.value
        assertEquals("p1", state.selectedProductId)
        assertTrue(state.cart?.items.orEmpty().isEmpty(), "the suggestion should not be added to the cart directly")
    }

    @Test
    fun `dismissing the product detail modal clears the selected product`() = runTest(dispatcher) {
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), FakeCartApi(), FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.onSuggestionSelected(product("p1", "Widget"))
        assertEquals("p1", viewModel.uiState.value.selectedProductId)

        viewModel.dismissProductDetail()
        assertEquals(null, viewModel.uiState.value.selectedProductId)
    }

    @Test
    fun `a product added from the modal updates the cart and closes the modal`() = runTest(dispatcher) {
        val cartApi = FakeCartApi()
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.onSuggestionSelected(product("p1", "Widget"))
        viewModel.onQueryChange("widget")

        val updatedCart = cart(items = listOf())
        viewModel.onProductAdded(updatedCart)

        val state = viewModel.uiState.value
        assertEquals(null, state.selectedProductId)
        assertEquals(updatedCart, state.cart)
        assertEquals("", state.searchQuery)
    }

    @Test
    fun `pressing enter with a matching barcode adds the product immediately`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi(resultsByBarcode = mapOf("12345" to listOf(product("p1", "Widget", barcode = "12345"))))
        val cartApi = FakeCartApi()
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), cartApi, searchApi, debounceMillis = 300L)
        advanceUntilIdle()

        viewModel.onQueryChange("12345")
        viewModel.onEnterPressed()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.cart?.items?.size)
        assertEquals("", state.searchQuery)
        assertEquals(1, searchApi.searchCalls.count { it.second == "12345" })
    }

    @Test
    fun `pressing enter with no barcode match shows the no-results CTA`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi()
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), FakeCartApi(), searchApi, debounceMillis = 300L)
        advanceUntilIdle()

        viewModel.onQueryChange("00000")
        viewModel.onEnterPressed()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNoResults)
    }

    @Test
    fun `no typeahead results shows the no-results CTA`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi()
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), FakeCartApi(), searchApi, debounceMillis = 300L)
        advanceUntilIdle()

        viewModel.onQueryChange("nonexistent")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showNoResults)
    }

    @Test
    fun `request assistance and create item CTAs surface an info message`() = runTest(dispatcher) {
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), FakeCartApi(), FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.requestAssistance()
        assertEquals("A store manager has been notified.", viewModel.uiState.value.infoMessage)

        viewModel.dismissInfoMessage()
        assertNull(viewModel.uiState.value.infoMessage)

        viewModel.requestCreateItem()
        assertEquals(
            "Ask a store manager to add this product from the admin console.",
            viewModel.uiState.value.infoMessage
        )
    }

    @Test
    fun `changing an item's quantity updates the cart`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem(id = "item-1", quantity = 1)))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.changeQuantity("item-1", 3)
        advanceUntilIdle()

        assertEquals("item-1" to 3, cartApi.lastUpdateQuantityCall)
        assertEquals(3, viewModel.uiState.value.cart?.items?.single()?.quantity)
    }

    @Test
    fun `changing quantity to zero or negative is a no-op`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem(id = "item-1", quantity = 1)))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.changeQuantity("item-1", 0)
        viewModel.changeQuantity("item-1", -1)
        advanceUntilIdle()

        assertEquals(null, cartApi.lastUpdateQuantityCall)
        assertEquals(1, viewModel.uiState.value.cart?.items?.single()?.quantity)
    }

    @Test
    fun `a rejected quantity update surfaces the server's error message`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem(id = "item-1", quantity = 1)))
        val cartApi = FakeCartApi(
            currentCart = existingCart,
            getCartOutcome = GetCartOutcome.Success(existingCart),
            updateQuantityOutcome = UpdateCartItemQuantityOutcome.Rejected("quantity must be positive")
        )
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.changeQuantity("item-1", 5)
        advanceUntilIdle()

        assertEquals("quantity must be positive", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `voiding an item removes it and remembers it for undo`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem(id = "item-1", productId = "p1", productName = "Widget", quantity = 2)))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.voidItem("item-1", "Customer changed their mind")
        advanceUntilIdle()

        assertEquals("item-1" to "Customer changed their mind", cartApi.lastRemoveItemCall)
        assertTrue(viewModel.uiState.value.cart?.items.orEmpty().isEmpty())
        val voided = viewModel.uiState.value.lastVoidedItem
        assertEquals("p1", voided?.productId)
        assertEquals("Widget", voided?.productName)
        assertEquals(2, voided?.quantity)
    }

    @Test
    fun `undoing a void re-adds the item and clears the undo state`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem(id = "item-1", productId = "p1", quantity = 2)))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.voidItem("item-1")
        advanceUntilIdle()

        viewModel.undoVoid()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.lastVoidedItem)
        assertEquals(1, viewModel.uiState.value.cart?.items?.size)
        assertEquals(1, cartApi.addItemCalls)
    }

    @Test
    fun `dismissing undo clears the state without re-adding the item`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem(id = "item-1", quantity = 1)))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.voidItem("item-1")
        advanceUntilIdle()

        viewModel.dismissUndo()

        assertEquals(null, viewModel.uiState.value.lastVoidedItem)
        assertEquals(0, cartApi.addItemCalls)
    }

    @Test
    fun `applying a quick discount sets a percentage discount on the cart`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.applyQuickDiscount(10.0)
        advanceUntilIdle()

        assertEquals(DiscountDto("PERCENTAGE", 10.0), cartApi.lastSetDiscountCall)
        assertEquals(DiscountDto("PERCENTAGE", 10.0), viewModel.uiState.value.cart?.discount)
    }

    @Test
    fun `clearing the cart discount removes it`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.applyQuickDiscount(10.0)
        advanceUntilIdle()

        viewModel.clearCartDiscount()
        advanceUntilIdle()

        assertEquals(null, cartApi.lastSetDiscountCall)
        assertEquals(null, viewModel.uiState.value.cart?.discount)
    }

    @Test
    fun `charging checks out the cart and opens the payment modal against the resulting order`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.charge()
        advanceUntilIdle()

        assertEquals(1, cartApi.checkoutCalls)
        assertEquals("order-1", viewModel.uiState.value.checkedOutOrderId)
        assertEquals(false, viewModel.uiState.value.isCheckingOut)
    }

    @Test
    fun `charging an empty cart is a no-op`() = runTest(dispatcher) {
        val existingCart = cart(items = emptyList())
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.charge()
        advanceUntilIdle()

        assertEquals(0, cartApi.checkoutCalls)
        assertEquals(null, viewModel.uiState.value.checkedOutOrderId)
    }

    @Test
    fun `a rejected checkout surfaces the server's error message`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(
            currentCart = existingCart,
            getCartOutcome = GetCartOutcome.Success(existingCart),
            checkoutOutcome = CheckoutOutcome.Rejected("Cannot checkout an empty cart")
        )
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        viewModel.charge()
        advanceUntilIdle()

        assertEquals("Cannot checkout an empty cart", viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.uiState.value.checkedOutOrderId)
    }

    @Test
    fun `dismissing the payment modal clears the checked-out order without starting a new sale`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(currentCart = existingCart)
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.charge()
        advanceUntilIdle()

        viewModel.dismissPaymentModal()

        assertEquals(null, viewModel.uiState.value.checkedOutOrderId)
        assertEquals(0, cartApi.createCartCalls, "no new cart should be created just from dismissing")
    }

    @Test
    fun `completing payment clears the checked-out order and shows a receipt without resetting yet`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(currentCart = existingCart)
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.charge()
        advanceUntilIdle()

        val completedOrder = OrderResponse(
            id = "order-1",
            cartId = existingCart.id,
            storeId = existingCart.storeId,
            items = existingCart.items,
            discount = null,
            totals = emptyTotals(),
            idempotencyKey = "key-1",
            checkedOutAt = "2026-01-01T00:00:00Z",
            status = "PAID"
        )
        viewModel.onPaymentCompleted(completedOrder)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.checkedOutOrderId)
        assertEquals(completedOrder, viewModel.uiState.value.receiptOrder)
        // The cart/cashier state is untouched until the receipt is dismissed - no new cart yet.
        assertEquals(1, viewModel.uiState.value.cart?.items?.size)
        assertEquals(0, cartApi.createCartCalls)
    }

    @Test
    fun `dismissing the receipt clears it and starts a new sale`() = runTest(dispatcher) {
        val existingCart = cart(items = listOf(cartItem()))
        val cartApi = FakeCartApi(currentCart = existingCart)
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(cartId = existingCart.id), cartApi, FakeProductSearchApi())
        advanceUntilIdle()
        viewModel.charge()
        advanceUntilIdle()
        val completedOrder = OrderResponse(
            id = "order-1",
            cartId = existingCart.id,
            storeId = existingCart.storeId,
            items = existingCart.items,
            discount = null,
            totals = emptyTotals(),
            idempotencyKey = "key-1",
            checkedOutAt = "2026-01-01T00:00:00Z",
            status = "PAID"
        )
        viewModel.onPaymentCompleted(completedOrder)
        advanceUntilIdle()

        viewModel.dismissReceipt()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.receiptOrder)
        assertEquals(0, viewModel.uiState.value.cart?.items?.size)
    }

    @Test
    fun `resumes a persisted open cart instead of creating a new one`() = runTest(dispatcher) {
        val existingCart = cart(id = "resumed-cart", items = listOf())
        val cartApi = FakeCartApi(currentCart = existingCart, getCartOutcome = GetCartOutcome.Success(existingCart))
        val sessionStore = FakeCartSessionStore(cartId = "resumed-cart")
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), sessionStore, cartApi, FakeProductSearchApi())
        advanceUntilIdle()

        assertEquals("resumed-cart", viewModel.uiState.value.cart?.id)
        assertEquals(0, cartApi.createCartCalls)
    }
}
