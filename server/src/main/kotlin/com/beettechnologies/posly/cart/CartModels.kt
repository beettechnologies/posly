package com.beettechnologies.posly.cart

import com.beettechnologies.posly.products.TaxCategory
import java.time.Instant
import java.util.UUID
import kotlin.math.round

enum class CartStatus { OPEN, CHECKED_OUT }

enum class DiscountType { PERCENTAGE, FIXED_AMOUNT }

/** [value] is a percent (0-100) for PERCENTAGE, or an absolute currency amount for FIXED_AMOUNT. */
data class Discount(
    val type: DiscountType,
    val value: Double
) {
    /** Amount to subtract from [amount], clamped so applying it never drives the amount below zero. */
    fun amountOff(amount: Double): Double = when (type) {
        DiscountType.PERCENTAGE -> (amount * (value / 100.0)).coerceIn(0.0, amount)
        DiscountType.FIXED_AMOUNT -> value.coerceIn(0.0, amount)
    }
}

data class SelectedModifier(
    val modifierId: String,
    val option: String,
    val additionalCost: Double
)

/** A caller-requested modifier selection, before its additionalCost is resolved from the product's catalog entry. */
data class ModifierSelection(val modifierId: String, val option: String)

data class CartItem(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val taxCategory: TaxCategory,
    val selectedModifiers: List<SelectedModifier> = emptyList(),
    val discount: Discount? = null
) {
    val modifiersTotal: Double get() = selectedModifiers.sumOf { it.additionalCost }
    val lineSubtotal: Double get() = roundCents((unitPrice + modifiersTotal) * quantity)
    val lineDiscountAmount: Double get() = roundCents(discount?.amountOff(lineSubtotal) ?: 0.0)
    val lineTotal: Double get() = roundCents(lineSubtotal - lineDiscountAmount)
}

data class Cart(
    val id: String,
    val storeId: String,
    val createdBy: String?,
    val items: List<CartItem> = emptyList(),
    val discount: Discount? = null,
    val status: CartStatus = CartStatus.OPEN,
    val checkoutIdempotencyKey: String? = null,
    val orderId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

private val TAXABLE_CATEGORIES = setOf(TaxCategory.STANDARD, TaxCategory.REDUCED)

data class TaxBreakdownLine(val name: String, val ratePercent: Double, val amount: Double)

data class CartTotals(
    val subtotal: Double,
    val itemDiscountTotal: Double,
    val cartDiscountAmount: Double,
    val taxableAmount: Double,
    val taxBreakdown: List<TaxBreakdownLine>,
    val totalTax: Double,
    val total: Double
)

/**
 * Server-side total calculation. EXEMPT/ZERO taxCategory items are excluded from the taxable
 * base; the cart-level discount is allocated proportionally between taxable and non-taxable
 * subtotals so exempt items aren't taxed via a discount-driven rounding artifact.
 */
fun computeTotals(cart: Cart, calculateTax: (Double) -> Pair<List<TaxBreakdownLine>, Double>): CartTotals {
    val subtotal = roundCents(cart.items.sumOf { it.lineSubtotal })
    val itemDiscountTotal = roundCents(cart.items.sumOf { it.lineDiscountAmount })
    val afterItemDiscounts = roundCents(subtotal - itemDiscountTotal)

    val taxableSubtotal = roundCents(
        cart.items.filter { it.taxCategory in TAXABLE_CATEGORIES }.sumOf { it.lineTotal }
    )

    val cartDiscountAmount = roundCents(cart.discount?.amountOff(afterItemDiscounts) ?: 0.0)
    val taxableShare = if (afterItemDiscounts > 0) taxableSubtotal / afterItemDiscounts else 0.0
    val taxableAmount = roundCents((taxableSubtotal - cartDiscountAmount * taxableShare).coerceAtLeast(0.0))

    val totalBeforeTax = roundCents(afterItemDiscounts - cartDiscountAmount)
    val (taxBreakdown, totalTax) = if (taxableAmount > 0) calculateTax(taxableAmount) else emptyList<TaxBreakdownLine>() to 0.0

    return CartTotals(
        subtotal = subtotal,
        itemDiscountTotal = itemDiscountTotal,
        cartDiscountAmount = cartDiscountAmount,
        taxableAmount = taxableAmount,
        taxBreakdown = taxBreakdown,
        totalTax = totalTax,
        total = roundCents(totalBeforeTax + totalTax)
    )
}

data class Order(
    val id: String = UUID.randomUUID().toString(),
    val cartId: String,
    val storeId: String,
    val createdBy: String?,
    val items: List<CartItem>,
    val discount: Discount?,
    val totals: CartTotals,
    val idempotencyKey: String,
    val checkedOutAt: Instant
)

internal fun roundCents(value: Double): Double = round(value * 100.0) / 100.0
