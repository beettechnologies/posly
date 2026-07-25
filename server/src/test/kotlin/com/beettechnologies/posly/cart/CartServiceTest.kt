package com.beettechnologies.posly.cart

import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ModifierRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import com.beettechnologies.posly.stores.TaxRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Harness(beforeCartCommitForTesting: (() -> Unit)? = null) {
    val products = ProductService()
    val taxProfiles = TaxProfileService()
    val stores = StoreService(taxProfiles)
    val orders = OrderService()
    val carts = CartService(products, stores, taxProfiles, orders, beforeCartCommitForTesting = beforeCartCommitForTesting)

    fun seedTaxProfile(ratePercent: Double = 10.0): String =
        taxProfiles.createProfile(name = "Sales Tax", rates = listOf(TaxRate("Sales Tax", ratePercent))).id

    fun seedStore(taxProfileId: String? = null): String {
        val result = stores.createStore(
            name = "Downtown",
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = taxProfileId
        )
        return (result as CreateStoreResult.Created).store.id
    }

    fun seedProduct(
        price: Double,
        taxCategory: String = "STANDARD",
        modifiers: List<ModifierRequest> = emptyList()
    ): String {
        val result = products.createProduct(
            CreateProductRequest(sku = "SKU-${products.listProducts().size + 1}", name = "Widget", price = price, taxCategory = taxCategory, modifiers = modifiers)
        )
        return (result as ProductResult.Created).product.id
    }

    fun modifierId(productId: String): String = products.getProduct(productId)!!.modifiers.first().id
}

class CartServiceTest {

    @Test
    fun `creating a cart for an unknown store is rejected`() {
        val h = Harness()
        assertEquals(CreateCartResult.StoreNotFound, h.carts.createCart("does-not-exist", createdBy = null))
    }

    @Test
    fun `adding an item recalculates totals with tax applied`() {
        val h = Harness()
        val taxProfileId = h.seedTaxProfile(ratePercent = 10.0)
        val storeId = h.seedStore(taxProfileId)
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, "cashier-1") as CreateCartResult.Success).cart

        val result = h.carts.addItem(cart.id, productId, quantity = 2)
        val updated = (assertIs<AddItemResult.Success>(result)).cart

        val totals = h.carts.getTotals(updated)
        assertEquals(20.0, totals.subtotal)
        assertEquals(20.0, totals.taxableAmount)
        assertEquals(2.0, totals.totalTax)
        assertEquals(22.0, totals.total)
    }

    @Test
    fun `exempt items are excluded from the taxable amount`() {
        val h = Harness()
        val taxProfileId = h.seedTaxProfile(ratePercent = 10.0)
        val storeId = h.seedStore(taxProfileId)
        val standardProductId = h.seedProduct(price = 10.0, taxCategory = "STANDARD")
        val exemptProductId = h.seedProduct(price = 5.0, taxCategory = "EXEMPT")
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart

        h.carts.addItem(cart.id, standardProductId, quantity = 1)
        val afterSecond = (assertIs<AddItemResult.Success>(h.carts.addItem(cart.id, exemptProductId, quantity = 1))).cart

        val totals = h.carts.getTotals(afterSecond)
        assertEquals(15.0, totals.subtotal)
        assertEquals(10.0, totals.taxableAmount, "only the STANDARD item's subtotal should be taxable")
        assertEquals(1.0, totals.totalTax)
        assertEquals(16.0, totals.total)
    }

    @Test
    fun `cart discount is allocated proportionally between taxable and non-taxable subtotals`() {
        val h = Harness()
        val taxProfileId = h.seedTaxProfile(ratePercent = 10.0)
        val storeId = h.seedStore(taxProfileId)
        val standardProductId = h.seedProduct(price = 10.0, taxCategory = "STANDARD")
        val exemptProductId = h.seedProduct(price = 10.0, taxCategory = "EXEMPT")
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        h.carts.addItem(cart.id, standardProductId, quantity = 1)
        h.carts.addItem(cart.id, exemptProductId, quantity = 1)

        val discountResult = h.carts.setCartDiscount(cart.id, Discount(DiscountType.PERCENTAGE, 10.0))
        val updated = (assertIs<SetCartDiscountResult.Success>(discountResult)).cart

        val totals = h.carts.getTotals(updated)
        assertEquals(20.0, totals.subtotal)
        assertEquals(2.0, totals.cartDiscountAmount)
        assertEquals(9.0, totals.taxableAmount, "half the 2.0 cart discount should reduce the 10.0 taxable share")
        assertEquals(0.9, totals.totalTax)
        assertEquals(18.9, totals.total)
    }

    @Test
    fun `removing an item recalculates totals for the remaining items`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productA = h.seedProduct(price = 10.0)
        val productB = h.seedProduct(price = 5.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        val afterAdds = (assertIs<AddItemResult.Success>(h.carts.addItem(cart.id, productA, quantity = 1))).cart
        val secondAdd = (assertIs<AddItemResult.Success>(h.carts.addItem(cart.id, productB, quantity = 1))).cart
        val itemToRemove = secondAdd.items.first { it.productId == productA }

        val result = h.carts.removeItem(cart.id, itemToRemove.id)
        val updated = (assertIs<RemoveItemResult.Success>(result)).cart

        assertEquals(1, updated.items.size)
        assertEquals(5.0, h.carts.getTotals(updated).subtotal)
    }

    @Test
    fun `removing an unknown item is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        assertEquals(RemoveItemResult.ItemNotFound, h.carts.removeItem(cart.id, "does-not-exist"))
    }

    @Test
    fun `valid modifier selection applies its additional cost`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(
            price = 10.0,
            modifiers = listOf(ModifierRequest(name = "Size", options = listOf("Large"), additionalCost = 1.5))
        )
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        val modifierId = h.modifierId(productId)

        val result = h.carts.addItem(
            cart.id,
            productId,
            quantity = 1,
            selectedModifiers = listOf(ModifierSelection(modifierId, "Large"))
        )
        val updated = (assertIs<AddItemResult.Success>(result)).cart

        assertEquals(11.5, updated.items.single().lineSubtotal)
    }

    @Test
    fun `unknown modifier option is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(
            price = 10.0,
            modifiers = listOf(ModifierRequest(name = "Size", options = listOf("Large"), additionalCost = 1.5))
        )
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        val modifierId = h.modifierId(productId)

        val result = h.carts.addItem(
            cart.id,
            productId,
            quantity = 1,
            selectedModifiers = listOf(ModifierSelection(modifierId, "Extra Large"))
        )
        assertIs<AddItemResult.InvalidModifier>(result)
    }

    @Test
    fun `selecting an out-of-stock modifier option is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(
            price = 10.0,
            modifiers = listOf(
                ModifierRequest(
                    name = "Size",
                    options = listOf("Small", "Large"),
                    additionalCost = 1.5,
                    unavailableOptions = listOf("Large")
                )
            )
        )
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        val modifierId = h.modifierId(productId)

        val rejected = h.carts.addItem(
            cart.id,
            productId,
            quantity = 1,
            selectedModifiers = listOf(ModifierSelection(modifierId, "Large"))
        )
        assertIs<AddItemResult.InvalidModifier>(rejected)

        val accepted = h.carts.addItem(
            cart.id,
            productId,
            quantity = 1,
            selectedModifiers = listOf(ModifierSelection(modifierId, "Small"))
        )
        assertIs<AddItemResult.Success>(accepted)
    }

    @Test
    fun `adding an item to an unknown cart is rejected`() {
        val h = Harness()
        h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        assertEquals(AddItemResult.CartNotFound, h.carts.addItem("does-not-exist", productId, quantity = 1))
    }

    @Test
    fun `adding an unknown product is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        assertEquals(AddItemResult.ProductNotFound, h.carts.addItem(cart.id, "does-not-exist", quantity = 1))
    }

    @Test
    fun `zero or negative quantity is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        assertEquals(AddItemResult.InvalidQuantity, h.carts.addItem(cart.id, productId, quantity = 0))
        assertEquals(AddItemResult.InvalidQuantity, h.carts.addItem(cart.id, productId, quantity = -1))
    }

    @Test
    fun `checking out a cart twice with the same idempotency key creates only one order`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        h.carts.addItem(cart.id, productId, quantity = 1)

        val first = assertIs<CheckoutResult.Success>(h.carts.checkout(cart.id, "retry-key-1"))
        val second = assertIs<CheckoutResult.Success>(h.carts.checkout(cart.id, "retry-key-1"))

        assertEquals(false, first.replayed)
        assertEquals(true, second.replayed)
        assertEquals(first.order.id, second.order.id)
        assertNotNull(h.orders.getOrder(first.order.id))
    }

    @Test
    fun `checkout creates a pending order awaiting payment confirmation`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        h.carts.addItem(cart.id, productId, quantity = 1)

        val result = assertIs<CheckoutResult.Success>(h.carts.checkout(cart.id, "key-1"))

        assertEquals(OrderStatus.PENDING, result.order.status)
    }

    @Test
    fun `a failure between order creation and cart commit rolls back the order and a retry then succeeds`() {
        var shouldFail = true
        val h = Harness(
            beforeCartCommitForTesting = {
                if (shouldFail) {
                    shouldFail = false
                    throw RuntimeException("simulated crash between order creation and cart commit")
                }
            }
        )
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        h.carts.addItem(cart.id, productId, quantity = 1)

        assertFailsWith<RuntimeException> { h.carts.checkout(cart.id, "key-1") }

        // No half-created order or inconsistent cart survives the failure.
        assertEquals(0, h.orders.count())
        val cartAfterFailure = h.carts.getCart(cart.id)!!
        assertEquals(CartStatus.OPEN, cartAfterFailure.status)
        assertNull(cartAfterFailure.orderId)

        // Retrying (the injected fault only fires once) succeeds cleanly with exactly one order.
        val retryResult = assertIs<CheckoutResult.Success>(h.carts.checkout(cart.id, "key-1"))
        assertEquals(false, retryResult.replayed)
        assertEquals(OrderStatus.PENDING, retryResult.order.status)
        assertEquals(1, h.orders.count())
    }

    @Test
    fun `checking out with a different idempotency key after checkout is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        h.carts.addItem(cart.id, productId, quantity = 1)

        h.carts.checkout(cart.id, "key-1")
        val result = h.carts.checkout(cart.id, "key-2")

        assertEquals(CheckoutResult.CartAlreadyCheckedOut, result)
    }

    @Test
    fun `checking out an empty cart is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        assertEquals(CheckoutResult.EmptyCart, h.carts.checkout(cart.id, "key-1"))
    }

    @Test
    fun `checking out an unknown cart is rejected`() {
        val h = Harness()
        assertEquals(CheckoutResult.CartNotFound, h.carts.checkout("does-not-exist", "key-1"))
    }

    @Test
    fun `mutating a checked-out cart is rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart
        h.carts.addItem(cart.id, productId, quantity = 1)
        h.carts.checkout(cart.id, "key-1")

        assertEquals(AddItemResult.CartNotOpen, h.carts.addItem(cart.id, productId, quantity = 1))
        val checkedOutCart = h.carts.getCart(cart.id)!!
        val itemId = checkedOutCart.items.first().id
        assertEquals(RemoveItemResult.CartNotOpen, h.carts.removeItem(cart.id, itemId))
        assertEquals(
            SetCartDiscountResult.CartNotOpen,
            h.carts.setCartDiscount(cart.id, Discount(DiscountType.FIXED_AMOUNT, 1.0))
        )
    }

    @Test
    fun `resuming a cart by id restores its items and totals`() {
        val h = Harness()
        val storeId = h.seedStore()
        val productId = h.seedProduct(price = 10.0)
        val created = (h.carts.createCart(storeId, "cashier-1") as CreateCartResult.Success).cart
        h.carts.addItem(created.id, productId, quantity = 3)

        val resumed = h.carts.getCart(created.id)

        assertNotNull(resumed)
        assertEquals(1, resumed.items.size)
        assertEquals(3, resumed.items.single().quantity)
        assertEquals(30.0, h.carts.getTotals(resumed).subtotal)
    }

    @Test
    fun `invalid discount values are rejected`() {
        val h = Harness()
        val storeId = h.seedStore()
        val cart = (h.carts.createCart(storeId, null) as CreateCartResult.Success).cart

        assertTrue(h.carts.setCartDiscount(cart.id, Discount(DiscountType.PERCENTAGE, 150.0)) is SetCartDiscountResult.InvalidDiscount)
        assertTrue(h.carts.setCartDiscount(cart.id, Discount(DiscountType.FIXED_AMOUNT, -5.0)) is SetCartDiscountResult.InvalidDiscount)
    }
}
