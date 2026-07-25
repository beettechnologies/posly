package com.beettechnologies.posly.pos

import com.beettechnologies.posly.cart.AddCartItemOutcome
import com.beettechnologies.posly.cart.CartApi
import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.cart.CheckoutOutcome
import com.beettechnologies.posly.cart.CreateCartOutcome
import com.beettechnologies.posly.cart.DiscountDto
import com.beettechnologies.posly.cart.GetCartOutcome
import com.beettechnologies.posly.cart.RemoveCartItemOutcome
import com.beettechnologies.posly.cart.SelectedModifierRequest
import com.beettechnologies.posly.cart.SetCartDiscountOutcome
import com.beettechnologies.posly.cart.UpdateCartItemQuantityOutcome
import com.beettechnologies.posly.products.GetProductOutcome
import com.beettechnologies.posly.products.ModifierResponse
import com.beettechnologies.posly.products.ProductApi
import com.beettechnologies.posly.products.ProductResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun sizeModifier(unavailableOptions: List<String> = emptyList()) = ModifierResponse(
    id = "mod-size",
    name = "Size",
    options = listOf("Small", "Medium", "Large"),
    additionalCost = 1.5,
    unavailableOptions = unavailableOptions
)

private fun extrasModifier() = ModifierResponse(
    id = "mod-extras",
    name = "Extras",
    options = listOf("Whipped Cream"),
    additionalCost = 0.75
)

private fun product(
    modifiers: List<ModifierResponse> = listOf(sizeModifier()),
    price: Double = 4.0
) = ProductResponse(
    id = "product-1",
    sku = "SKU-1",
    name = "Latte",
    description = "A coffee drink",
    price = price,
    taxCategory = "STANDARD",
    modifiers = modifiers,
    imageUrls = emptyList(),
    createdAt = 0,
    updatedAt = 0
)

private class FakeProductApi(private val outcome: GetProductOutcome) : ProductApi {
    override suspend fun getProduct(id: String): GetProductOutcome = outcome
}

private class FakeDetailCartApi(
    private val addResult: (String, Int, List<SelectedModifierRequest>) -> AddCartItemOutcome = { productId, quantity, _ ->
        AddCartItemOutcome.Success(
            CartResponse(
                id = "cart-1",
                storeId = "store-1",
                status = "OPEN",
                items = listOf(
                    CartItemResponse(
                        id = "item-1",
                        productId = productId,
                        productName = "Latte",
                        quantity = quantity,
                        unitPrice = 4.0,
                        taxCategory = "STANDARD",
                        selectedModifiers = emptyList(),
                        discount = null,
                        lineSubtotal = 4.0 * quantity,
                        lineDiscountAmount = 0.0,
                        lineTotal = 4.0 * quantity
                    )
                ),
                discount = null,
                totals = CartTotalsResponse(0.0, 0.0, 0.0, 0.0, emptyList(), 0.0, 0.0),
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z"
            )
        )
    }
) : CartApi {
    var lastAddItemCall: Triple<String, Int, List<SelectedModifierRequest>>? = null

    override suspend fun createCart(storeId: String): CreateCartOutcome = error("not used in these tests")
    override suspend fun getCart(id: String): GetCartOutcome = error("not used in these tests")

    override suspend fun addItem(
        cartId: String,
        productId: String,
        quantity: Int,
        selectedModifiers: List<SelectedModifierRequest>,
        discount: DiscountDto?
    ): AddCartItemOutcome {
        lastAddItemCall = Triple(productId, quantity, selectedModifiers)
        return addResult(productId, quantity, selectedModifiers)
    }

    override suspend fun updateItemQuantity(cartId: String, itemId: String, quantity: Int): UpdateCartItemQuantityOutcome =
        error("not used in these tests")

    override suspend fun removeItem(cartId: String, itemId: String, reason: String?): RemoveCartItemOutcome =
        error("not used in these tests")

    override suspend fun setCartDiscount(cartId: String, discount: DiscountDto?): SetCartDiscountOutcome =
        error("not used in these tests")

    override suspend fun checkout(cartId: String, idempotencyKey: String): CheckoutOutcome =
        error("not used in these tests")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading a product populates the detail state`() = runTest {
        val viewModel = ProductDetailViewModel(FakeProductApi(GetProductOutcome.Success(product())), FakeDetailCartApi())

        viewModel.load("product-1")

        val state = viewModel.uiState.value
        assertEquals("Latte", state.product?.name)
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `selecting modifiers updates the price in real time`() = runTest {
        val viewModel = ProductDetailViewModel(
            FakeProductApi(GetProductOutcome.Success(product(modifiers = listOf(sizeModifier(), extrasModifier()), price = 4.0))),
            FakeDetailCartApi()
        )
        viewModel.load("product-1")
        assertEquals(4.0, viewModel.uiState.value.unitPrice)

        viewModel.selectOption("mod-size", "Large")
        assertEquals(5.5, viewModel.uiState.value.unitPrice)

        viewModel.selectOption("mod-extras", "Whipped Cream")
        assertEquals(6.25, viewModel.uiState.value.unitPrice)

        // Re-selecting a different option within the same modifier group replaces, not adds.
        viewModel.selectOption("mod-size", "Medium")
        assertEquals(6.25, viewModel.uiState.value.unitPrice)
    }

    @Test
    fun `quantity changes scale the line total`() = runTest {
        val viewModel = ProductDetailViewModel(FakeProductApi(GetProductOutcome.Success(product(price = 4.0))), FakeDetailCartApi())
        viewModel.load("product-1")

        viewModel.incrementQuantity()
        viewModel.incrementQuantity()
        assertEquals(3, viewModel.uiState.value.quantity)
        assertEquals(12.0, viewModel.uiState.value.lineTotal)

        viewModel.decrementQuantity()
        assertEquals(2, viewModel.uiState.value.quantity)
        assertEquals(8.0, viewModel.uiState.value.lineTotal)
    }

    @Test
    fun `quantity cannot go below one`() = runTest {
        val viewModel = ProductDetailViewModel(FakeProductApi(GetProductOutcome.Success(product())), FakeDetailCartApi())
        viewModel.load("product-1")

        viewModel.decrementQuantity()

        assertEquals(1, viewModel.uiState.value.quantity)
    }

    @Test
    fun `selecting an out-of-stock option is a no-op`() = runTest {
        val viewModel = ProductDetailViewModel(
            FakeProductApi(GetProductOutcome.Success(product(modifiers = listOf(sizeModifier(unavailableOptions = listOf("Large")))))),
            FakeDetailCartApi()
        )
        viewModel.load("product-1")

        viewModel.selectOption("mod-size", "Large")

        assertEquals(emptyMap(), viewModel.uiState.value.selectedOptions)
        assertEquals(4.0, viewModel.uiState.value.unitPrice)
    }

    @Test
    fun `adding to cart passes the selected modifiers and quantity through`() = runTest {
        val cartApi = FakeDetailCartApi()
        val viewModel = ProductDetailViewModel(
            FakeProductApi(GetProductOutcome.Success(product(modifiers = listOf(sizeModifier())))),
            cartApi
        )
        viewModel.load("product-1")
        viewModel.selectOption("mod-size", "Medium")
        viewModel.incrementQuantity()

        viewModel.addToCart("cart-1")

        assertEquals(Triple("product-1", 2, listOf(SelectedModifierRequest("mod-size", "Medium"))), cartApi.lastAddItemCall)
        assertNotNull(viewModel.uiState.value.added)
    }

    @Test
    fun `a rejected add-to-cart surfaces the server's error message`() = runTest {
        val cartApi = FakeDetailCartApi(addResult = { _, _, _ -> AddCartItemOutcome.Rejected("Option is out of stock") })
        val viewModel = ProductDetailViewModel(FakeProductApi(GetProductOutcome.Success(product())), cartApi)
        viewModel.load("product-1")

        viewModel.addToCart("cart-1")

        assertEquals("Option is out of stock", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.added)
    }
}
