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
    val payments: List<PaymentRecordResponse> = emptyList(),
    val amountPaid: Double = 0.0,
    val remainingBalance: Double = 0.0,
    val refund: RefundRecordResponse? = null
)

@Serializable
data class ConfirmPaymentRequest(
    val method: String,
    val amount: Double,
    val reference: String? = null
)
