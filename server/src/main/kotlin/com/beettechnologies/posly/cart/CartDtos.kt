package com.beettechnologies.posly.cart

import kotlinx.serialization.Serializable

@Serializable
data class CreateCartRequest(val storeId: String)

@Serializable
data class SelectedModifierRequest(val modifierId: String, val option: String)

@Serializable
data class DiscountDto(val type: String, val value: Double)

class InvalidDiscountDtoException(message: String) : Exception(message)

fun DiscountDto.toDomain(): Discount {
    val type = runCatching { DiscountType.valueOf(this.type) }.getOrElse {
        throw InvalidDiscountDtoException("Invalid discount type '${this.type}'")
    }
    return Discount(type, value)
}

@Serializable
data class AddCartItemRequest(
    val productId: String,
    val quantity: Int,
    val selectedModifiers: List<SelectedModifierRequest> = emptyList(),
    val discount: DiscountDto? = null
)

@Serializable
data class SetCartDiscountRequest(val discount: DiscountDto?)

@Serializable
data class UpdateCartItemQuantityRequest(val quantity: Int)

@Serializable
data class VoidCartItemRequest(val reason: String? = null)

@Serializable
data class CheckoutRequest(val idempotencyKey: String)

@Serializable
data class SelectedModifierResponse(val modifierId: String, val option: String, val additionalCost: Double)

@Serializable
data class CartItemResponse(
    val id: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val taxCategory: String,
    val selectedModifiers: List<SelectedModifierResponse>,
    val discount: DiscountDto?,
    val lineSubtotal: Double,
    val lineDiscountAmount: Double,
    val lineTotal: Double
)

@Serializable
data class TaxBreakdownLineResponse(val name: String, val ratePercent: Double, val amount: Double)

@Serializable
data class CartTotalsResponse(
    val subtotal: Double,
    val itemDiscountTotal: Double,
    val cartDiscountAmount: Double,
    val taxableAmount: Double,
    val taxBreakdown: List<TaxBreakdownLineResponse>,
    val totalTax: Double,
    val total: Double
)

@Serializable
data class CartResponse(
    val id: String,
    val storeId: String,
    val status: String,
    val items: List<CartItemResponse>,
    val discount: DiscountDto?,
    val totals: CartTotalsResponse,
    val orderId: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class PaymentRecordResponse(
    val method: String,
    val amount: Double,
    val reference: String?,
    val confirmedBy: String?,
    val confirmedAt: String,
    val maskedCardNumber: String? = null
)

@Serializable
data class RefundRecordResponse(
    val refundId: String,
    val amount: Double,
    val reason: String?,
    val refundedBy: String?,
    val refundedAt: String
)

@Serializable
data class OrderResponse(
    val id: String,
    val cartId: String,
    val storeId: String,
    val items: List<CartItemResponse>,
    val discount: DiscountDto?,
    val totals: CartTotalsResponse,
    val idempotencyKey: String,
    val checkedOutAt: String,
    val status: String,
    // No defaults here: kotlinx.serialization's Json omits any field left at its declared default
    // when encoding (encodeDefaults = false), and 0.0/emptyList() are exactly the values a fully
    // paid/brand-new order legitimately has - a default would make them vanish from the response.
    val payments: List<PaymentRecordResponse>,
    val amountPaid: Double,
    val remainingBalance: Double,
    val refund: RefundRecordResponse? = null
)

@Serializable
data class ConfirmPaymentRequest(
    val method: String,
    val amount: Double,
    val reference: String? = null
)

@Serializable
data class RefundRequest(val refundId: String, val reason: String? = null)

@Serializable
data class OrderEventResponse(
    val timestamp: String,
    val type: String,
    val actorId: String?,
    val detail: String? = null
)

fun Discount.toResponse() = DiscountDto(type = type.name, value = value)

fun CartItem.toResponse() = CartItemResponse(
    id = id,
    productId = productId,
    productName = productName,
    quantity = quantity,
    unitPrice = unitPrice,
    taxCategory = taxCategory.name,
    selectedModifiers = selectedModifiers.map { SelectedModifierResponse(it.modifierId, it.option, it.additionalCost) },
    discount = discount?.toResponse(),
    lineSubtotal = lineSubtotal,
    lineDiscountAmount = lineDiscountAmount,
    lineTotal = lineTotal
)

fun CartTotals.toResponse() = CartTotalsResponse(
    subtotal = subtotal,
    itemDiscountTotal = itemDiscountTotal,
    cartDiscountAmount = cartDiscountAmount,
    taxableAmount = taxableAmount,
    taxBreakdown = taxBreakdown.map { TaxBreakdownLineResponse(it.name, it.ratePercent, it.amount) },
    totalTax = totalTax,
    total = total
)

fun Cart.toResponse(totals: CartTotals) = CartResponse(
    id = id,
    storeId = storeId,
    status = status.name,
    items = items.map { it.toResponse() },
    discount = discount?.toResponse(),
    totals = totals.toResponse(),
    orderId = orderId,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

fun PaymentRecord.toResponse() = PaymentRecordResponse(
    method = method,
    amount = amount,
    reference = reference,
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt.toString(),
    maskedCardNumber = maskedCardNumber
)

fun RefundRecord.toResponse() = RefundRecordResponse(
    refundId = refundId,
    amount = amount,
    reason = reason,
    refundedBy = refundedBy,
    refundedAt = refundedAt.toString()
)

fun Order.toResponse() = OrderResponse(
    id = id,
    cartId = cartId,
    storeId = storeId,
    items = items.map { it.toResponse() },
    discount = discount?.toResponse(),
    totals = totals.toResponse(),
    idempotencyKey = idempotencyKey,
    checkedOutAt = checkedOutAt.toString(),
    status = status.name,
    payments = payments.map { it.toResponse() },
    amountPaid = amountPaid,
    remainingBalance = remainingBalance,
    refund = refund?.toResponse()
)

fun OrderEvent.toResponse() = OrderEventResponse(
    timestamp = timestamp.toString(),
    type = type.name,
    actorId = actorId,
    detail = detail
)
