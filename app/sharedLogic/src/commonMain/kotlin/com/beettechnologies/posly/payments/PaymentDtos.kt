package com.beettechnologies.posly.payments

import kotlinx.serialization.Serializable

@Serializable
data class CreatePaymentRequest(
    val orderId: String,
    val amount: Double,
    val currency: String = "USD"
)

@Serializable
data class PaymentResponse(
    val id: String,
    val orderId: String,
    val terminalTransactionId: String,
    val amount: Double,
    val currency: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val declineReason: String? = null,
    val refundId: String? = null,
    val refundedAmount: Double? = null,
    val maskedCardNumber: String? = null
)
