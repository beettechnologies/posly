package com.beettechnologies.posly.cart

import com.beettechnologies.posly.db.InstantSerializer
import com.beettechnologies.posly.products.TaxCategory
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import kotlin.math.round

enum class CartStatus { OPEN, CHECKED_OUT }

@Serializable
enum class DiscountType { PERCENTAGE, FIXED_AMOUNT }

/** [value] is a percent (0-100) for PERCENTAGE, or an absolute currency amount for FIXED_AMOUNT. */
@Serializable
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

@Serializable
data class SelectedModifier(
    val modifierId: String,
    val option: String,
    val additionalCost: Double
)

/** A caller-requested modifier selection, before its additionalCost is resolved from the product's catalog entry. */
data class ModifierSelection(val modifierId: String, val option: String)

@Serializable
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

internal val TAXABLE_CATEGORIES = setOf(TaxCategory.STANDARD, TaxCategory.REDUCED)

@Serializable
data class TaxBreakdownLine(val name: String, val ratePercent: Double, val amount: Double)

@Serializable
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

enum class OrderStatus { PENDING, PAID, PARTIALLY_REFUNDED, REFUNDED }

@Serializable
data class PaymentRecord(
    val method: String,
    val amount: Double,
    val reference: String?,
    val confirmedBy: String?,
    @Serializable(with = InstantSerializer::class) val confirmedAt: Instant,
    val maskedCardNumber: String? = null
)

/** One refunded cart line within a [RefundRecord] - [amount] already includes this line's fair share of order tax. */
@Serializable
data class RefundLineItem(
    val cartItemId: String,
    val quantity: Int,
    val amount: Double,
    val restock: Boolean
)

@Serializable
data class RefundRecord(
    val refundId: String,
    /** "CARD" (via the payment gateway) or "MANUAL" (cashier/manager asserting they handled it outside the system). */
    val method: String,
    val lineItems: List<RefundLineItem>,
    val amount: Double,
    val reason: String?,
    val refundedBy: String?,
    @Serializable(with = InstantSerializer::class) val refundedAt: Instant
)

data class Order(
    val id: String = UUID.randomUUID().toString(),
    val cartId: String,
    val storeId: String,
    val createdBy: String?,
    val items: List<CartItem>,
    val discount: Discount?,
    val totals: CartTotals,
    val idempotencyKey: String,
    val checkedOutAt: Instant,
    val status: OrderStatus = OrderStatus.PENDING,
    /** One entry per tender applied - a split payment accumulates more than one before the order reaches PAID. */
    val payments: List<PaymentRecord> = emptyList(),
    /** One entry per refund event - partial refunds accumulate until remainingRefundable reaches zero. */
    val refunds: List<RefundRecord> = emptyList()
) {
    val amountPaid: Double get() = roundCents(payments.sumOf { it.amount })
    val remainingBalance: Double get() = roundCents(totals.total - amountPaid)
    val amountRefunded: Double get() = roundCents(refunds.sumOf { it.amount })
    val remainingRefundable: Double get() = roundCents(amountPaid - amountRefunded)

    /** How many units of this cart line have already been refunded across all prior refund events. */
    fun refundedQuantityFor(cartItemId: String): Int =
        refunds.flatMap { it.lineItems }.filter { it.cartItemId == cartItemId }.sumOf { it.quantity }
}

enum class OrderEventType { CREATED, PAYMENT_CONFIRMED, REFUNDED }

data class OrderEvent(
    val timestamp: Instant,
    val orderId: String,
    val type: OrderEventType,
    val actorId: String?,
    val detail: String? = null
)

/** Audit record of a voided (removed) cart line item, kept for traceability even though the item itself is gone from the cart. */
data class CartItemVoidEvent(
    val timestamp: Instant,
    val cartId: String,
    val itemId: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val reason: String?,
    val actorId: String?
)

internal fun roundCents(value: Double): Double = round(value * 100.0) / 100.0
