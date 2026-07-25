package com.beettechnologies.posly.cart

import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.CalculateTaxResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class CreateCartResult {
    data class Success(val cart: Cart) : CreateCartResult()
    data object StoreNotFound : CreateCartResult()
}

sealed class AddItemResult {
    data class Success(val cart: Cart) : AddItemResult()
    data object CartNotFound : AddItemResult()
    data object CartNotOpen : AddItemResult()
    data object ProductNotFound : AddItemResult()
    data object InvalidQuantity : AddItemResult()
    data class InvalidModifier(val message: String) : AddItemResult()
    data class InvalidDiscount(val message: String) : AddItemResult()
}

sealed class RemoveItemResult {
    data class Success(val cart: Cart) : RemoveItemResult()
    data object CartNotFound : RemoveItemResult()
    data object CartNotOpen : RemoveItemResult()
    data object ItemNotFound : RemoveItemResult()
}

sealed class UpdateItemQuantityResult {
    data class Success(val cart: Cart) : UpdateItemQuantityResult()
    data object CartNotFound : UpdateItemQuantityResult()
    data object CartNotOpen : UpdateItemQuantityResult()
    data object ItemNotFound : UpdateItemQuantityResult()
    data object InvalidQuantity : UpdateItemQuantityResult()
}

sealed class SetCartDiscountResult {
    data class Success(val cart: Cart) : SetCartDiscountResult()
    data object CartNotFound : SetCartDiscountResult()
    data object CartNotOpen : SetCartDiscountResult()
    data class InvalidDiscount(val message: String) : SetCartDiscountResult()
}

sealed class CheckoutResult {
    data class Success(val order: Order, val replayed: Boolean) : CheckoutResult()
    data object CartNotFound : CheckoutResult()
    data object EmptyCart : CheckoutResult()
    data object CartAlreadyCheckedOut : CheckoutResult()
}

/**
 * In-memory cart lifecycle: create -> add/remove items -> optional cart-level discount ->
 * checkout. Single-item mutations (add/remove/discount) are guarded per-cart via
 * ConcurrentHashMap.compute; checkout additionally creates an Order via [OrderService] so it
 * takes a dedicated lock to keep "check status, create order, flip cart to CHECKED_OUT" atomic.
 *
 * [beforeCartCommitForTesting], if supplied, runs immediately after the order is created and
 * immediately before the cart is committed to CHECKED_OUT - purely a test seam for simulating a
 * crash in that narrow window and asserting the resulting rollback leaves no orphaned order and
 * an OPEN cart that can be retried cleanly. It is never used in production (default: no-op).
 */
class CartService(
    private val productService: ProductService,
    private val storeService: StoreService,
    private val taxProfileService: TaxProfileService,
    private val orderService: OrderService,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val beforeCartCommitForTesting: (() -> Unit)? = null
) {
    private val carts = ConcurrentHashMap<String, Cart>()
    private val checkoutLock = Any()
    private val voidEvents = mutableListOf<CartItemVoidEvent>()

    fun createCart(storeId: String, createdBy: String?): CreateCartResult {
        if (storeService.getStore(storeId) == null) return CreateCartResult.StoreNotFound
        val now = nowProvider()
        val cart = Cart(
            id = UUID.randomUUID().toString(),
            storeId = storeId,
            createdBy = createdBy,
            createdAt = now,
            updatedAt = now
        )
        carts[cart.id] = cart
        return CreateCartResult.Success(cart)
    }

    fun getCart(id: String): Cart? = carts[id]

    fun getTotals(cart: Cart): CartTotals = computeTotals(cart) { amount -> calculateTax(cart.storeId, amount) }

    fun addItem(
        cartId: String,
        productId: String,
        quantity: Int,
        selectedModifiers: List<ModifierSelection> = emptyList(),
        discount: Discount? = null
    ): AddItemResult {
        if (quantity <= 0) return AddItemResult.InvalidQuantity
        discount?.let { d -> validateDiscount(d)?.let { return AddItemResult.InvalidDiscount(it) } }

        val product = productService.getProduct(productId) ?: return AddItemResult.ProductNotFound

        val resolvedModifiers = mutableListOf<SelectedModifier>()
        for (selection in selectedModifiers) {
            val modifier = product.modifiers.find { it.id == selection.modifierId }
                ?: return AddItemResult.InvalidModifier("Unknown modifier ${selection.modifierId} for product $productId")
            if (selection.option !in modifier.options) {
                return AddItemResult.InvalidModifier("Option '${selection.option}' is not valid for modifier ${modifier.name}")
            }
            if (selection.option in modifier.unavailableOptions) {
                return AddItemResult.InvalidModifier(
                    "Option '${selection.option}' for modifier ${modifier.name} is out of stock"
                )
            }
            resolvedModifiers += SelectedModifier(modifier.id, selection.option, modifier.additionalCost)
        }

        val item = CartItem(
            productId = product.id,
            productName = product.name,
            quantity = quantity,
            unitPrice = product.price,
            taxCategory = product.taxCategory,
            selectedModifiers = resolvedModifiers,
            discount = discount
        )

        var outcome: AddItemResult = AddItemResult.CartNotFound
        carts.compute(cartId) { _, existing ->
            when {
                existing == null -> {
                    outcome = AddItemResult.CartNotFound
                    null
                }
                existing.status != CartStatus.OPEN -> {
                    outcome = AddItemResult.CartNotOpen
                    existing
                }
                else -> {
                    val updated = existing.copy(items = existing.items + item, updatedAt = nowProvider())
                    outcome = AddItemResult.Success(updated)
                    updated
                }
            }
        }
        return outcome
    }

    fun removeItem(cartId: String, itemId: String, reason: String? = null, actorId: String? = null): RemoveItemResult {
        var outcome: RemoveItemResult = RemoveItemResult.CartNotFound
        var removedItem: CartItem? = null
        carts.compute(cartId) { _, existing ->
            when {
                existing == null -> {
                    outcome = RemoveItemResult.CartNotFound
                    null
                }
                existing.status != CartStatus.OPEN -> {
                    outcome = RemoveItemResult.CartNotOpen
                    existing
                }
                existing.items.none { it.id == itemId } -> {
                    outcome = RemoveItemResult.ItemNotFound
                    existing
                }
                else -> {
                    removedItem = existing.items.first { it.id == itemId }
                    val updated = existing.copy(
                        items = existing.items.filterNot { it.id == itemId },
                        updatedAt = nowProvider()
                    )
                    outcome = RemoveItemResult.Success(updated)
                    updated
                }
            }
        }
        removedItem?.let { item ->
            synchronized(voidEvents) {
                voidEvents += CartItemVoidEvent(
                    timestamp = nowProvider(),
                    cartId = cartId,
                    itemId = item.id,
                    productId = item.productId,
                    productName = item.productName,
                    quantity = item.quantity,
                    reason = reason,
                    actorId = actorId
                )
            }
        }
        return outcome
    }

    fun listVoidEvents(cartId: String): List<CartItemVoidEvent> =
        synchronized(voidEvents) { voidEvents.filter { it.cartId == cartId } }

    fun updateItemQuantity(cartId: String, itemId: String, quantity: Int): UpdateItemQuantityResult {
        if (quantity <= 0) return UpdateItemQuantityResult.InvalidQuantity

        var outcome: UpdateItemQuantityResult = UpdateItemQuantityResult.CartNotFound
        carts.compute(cartId) { _, existing ->
            when {
                existing == null -> {
                    outcome = UpdateItemQuantityResult.CartNotFound
                    null
                }
                existing.status != CartStatus.OPEN -> {
                    outcome = UpdateItemQuantityResult.CartNotOpen
                    existing
                }
                existing.items.none { it.id == itemId } -> {
                    outcome = UpdateItemQuantityResult.ItemNotFound
                    existing
                }
                else -> {
                    val updated = existing.copy(
                        items = existing.items.map { if (it.id == itemId) it.copy(quantity = quantity) else it },
                        updatedAt = nowProvider()
                    )
                    outcome = UpdateItemQuantityResult.Success(updated)
                    updated
                }
            }
        }
        return outcome
    }

    fun setCartDiscount(cartId: String, discount: Discount?): SetCartDiscountResult {
        discount?.let { d -> validateDiscount(d)?.let { return SetCartDiscountResult.InvalidDiscount(it) } }

        var outcome: SetCartDiscountResult = SetCartDiscountResult.CartNotFound
        carts.compute(cartId) { _, existing ->
            when {
                existing == null -> {
                    outcome = SetCartDiscountResult.CartNotFound
                    null
                }
                existing.status != CartStatus.OPEN -> {
                    outcome = SetCartDiscountResult.CartNotOpen
                    existing
                }
                else -> {
                    val updated = existing.copy(discount = discount, updatedAt = nowProvider())
                    outcome = SetCartDiscountResult.Success(updated)
                    updated
                }
            }
        }
        return outcome
    }

    /**
     * Idempotent by [idempotencyKey]: a cart can only ever be checked out once. A retry with the
     * SAME key against an already-checked-out cart replays the original order instead of erroring.
     * A different key against an already-checked-out cart is rejected - that's not a retry.
     */
    fun checkout(cartId: String, idempotencyKey: String): CheckoutResult {
        synchronized(checkoutLock) {
            val cart = carts[cartId] ?: return CheckoutResult.CartNotFound

            if (cart.status == CartStatus.CHECKED_OUT) {
                val existingOrder = cart.orderId?.let { orderService.getOrder(it) }
                return if (cart.checkoutIdempotencyKey == idempotencyKey && existingOrder != null) {
                    CheckoutResult.Success(existingOrder, replayed = true)
                } else {
                    CheckoutResult.CartAlreadyCheckedOut
                }
            }

            if (cart.items.isEmpty()) return CheckoutResult.EmptyCart

            val totals = getTotals(cart)
            val order = orderService.createOrder(cart, totals, idempotencyKey)
            try {
                beforeCartCommitForTesting?.invoke()
                carts[cartId] = cart.copy(
                    status = CartStatus.CHECKED_OUT,
                    checkoutIdempotencyKey = idempotencyKey,
                    orderId = order.id,
                    updatedAt = nowProvider()
                )
            } catch (e: Exception) {
                orderService.deleteOrder(order.id)
                throw e
            }
            return CheckoutResult.Success(order, replayed = false)
        }
    }

    private fun validateDiscount(discount: Discount): String? = when (discount.type) {
        DiscountType.PERCENTAGE ->
            if (discount.value !in 0.0..100.0) "percentage discount must be between 0 and 100" else null
        DiscountType.FIXED_AMOUNT ->
            if (discount.value < 0.0) "fixed discount amount must not be negative" else null
    }

    private fun calculateTax(storeId: String, amount: Double): Pair<List<TaxBreakdownLine>, Double> {
        val taxProfileId = storeService.getStore(storeId)?.taxProfileId
            ?: return emptyList<TaxBreakdownLine>() to 0.0
        return when (val result = taxProfileService.calculateTax(taxProfileId, amount)) {
            is CalculateTaxResult.Success ->
                result.breakdown.map { TaxBreakdownLine(it.name, it.ratePercent, it.amount) } to result.totalTax
            CalculateTaxResult.ProfileNotFound -> emptyList<TaxBreakdownLine>() to 0.0
        }
    }
}
