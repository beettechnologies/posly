package com.beettechnologies.posly.orders

import com.beettechnologies.posly.cart.CartItemResponse
import com.beettechnologies.posly.cart.CartTotalsResponse
import com.beettechnologies.posly.cart.DiscountDto
import kotlinx.serialization.Serializable

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
data class RefundLineItemResponse(
    val cartItemId: String,
    val quantity: Int,
    val amount: Double,
    val restock: Boolean
)

@Serializable
data class RefundRecordResponse(
    val refundId: String,
    val method: String,
    val lineItems: List<RefundLineItemResponse> = emptyList(),
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
    val currency: String = "USD",
    val status: String,
    val payments: List<PaymentRecordResponse> = emptyList(),
    val amountPaid: Double = 0.0,
    val remainingBalance: Double = 0.0,
    val refunds: List<RefundRecordResponse> = emptyList(),
    val amountRefunded: Double = 0.0,
    val remainingRefundable: Double = 0.0
)

@Serializable
data class ConfirmPaymentRequest(
    val method: String,
    val amount: Double,
    val reference: String? = null
)

@Serializable
data class RefundLineItemRequest(
    val cartItemId: String,
    val quantity: Int,
    val restock: Boolean = false
)

@Serializable
data class RefundRequest(
    val refundId: String,
    val method: String,
    val lineItems: List<RefundLineItemRequest>,
    val reason: String? = null
)
