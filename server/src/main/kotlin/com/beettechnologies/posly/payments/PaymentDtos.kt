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
    val refundedAmount: Double? = null
)

@Serializable
data class RefundPaymentRequest(
    val refundId: String,
    val amount: Double
)

@Serializable
data class WebhookPayload(
    val eventId: String,
    val terminalTransactionId: String,
    val outcome: String,
    val declineReason: String? = null
)

@Serializable
data class RefundAttemptResponse(
    val refundId: String,
    val paymentId: String,
    val orderId: String,
    val amount: Double,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val requestedAt: String,
    val resolvedAt: String?
)

fun RefundAttempt.toResponse() = RefundAttemptResponse(
    refundId = refundId,
    paymentId = paymentId,
    orderId = orderId,
    amount = amount,
    status = status.name,
    attempts = attempts,
    lastError = lastError,
    requestedAt = requestedAt.toString(),
    resolvedAt = resolvedAt?.toString()
)

fun GatewayPayment.toResponse() = PaymentResponse(
    id = id,
    orderId = orderId,
    terminalTransactionId = terminalTransactionId,
    amount = amount,
    currency = currency,
    status = status.name,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    declineReason = declineReason,
    refundId = refundId,
    refundedAmount = refundedAmount
)
