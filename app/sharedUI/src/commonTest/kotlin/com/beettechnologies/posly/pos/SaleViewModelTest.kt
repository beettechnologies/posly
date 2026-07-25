package com.beettechnologies.posly.pos

import com.beettechnologies.posly.cart.AddCartItemOutcome
import com.beettechnologies.posly.cart.CartApi
import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartResponse
import com.beettechnologies.posly.cart.CartSessionStore
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.cart.CreateCartOutcome
import com.beettechnologies.posly.cart.GetCartOutcome
import com.beettechnologies.posly.devices.DeviceCredentials
import com.beettechnologies.posly.devices.DeviceCredentialsStore
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
    private val getCartOutcome: GetCartOutcome? = null
) : CartApi {
    var createCartCalls = 0

    override suspend fun createCart(storeId: String): CreateCartOutcome {
        createCartCalls++
        return CreateCartOutcome.Success(currentCart)
    }

    override suspend fun getCart(id: String): GetCartOutcome = getCartOutcome ?: GetCartOutcome.Success(currentCart)

    override suspend fun addItem(cartId: String, productId: String, quantity: Int): AddCartItemOutcome {
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
    fun `selecting a suggestion adds it to the cart and clears the search field`() = runTest(dispatcher) {
        val searchApi = FakeProductSearchApi(resultsByQuery = mapOf("widget" to listOf(product("p1", "Widget"))))
        val cartApi = FakeCartApi()
        val viewModel = SaleViewModel(FakeDeviceCredentialsStore(), FakeCartSessionStore(), cartApi, searchApi, debounceMillis = 300L)
        advanceUntilIdle()
        viewModel.onQueryChange("widget")
        advanceTimeBy(300)
        advanceUntilIdle()

        val suggestion = viewModel.uiState.value.suggestions.single()
        viewModel.onSuggestionSelected(suggestion)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.cart?.items?.size)
        assertEquals("", state.searchQuery)
        assertTrue(state.suggestions.isEmpty())
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
