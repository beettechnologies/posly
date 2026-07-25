package com.beettechnologies.posly.payments

import java.time.Instant
import java.util.UUID

enum class GatewayPaymentStatus { INITIATED, APPROVED, DECLINED, REFUNDED }

/**
 * Our record of a payment session against the gateway, distinct from the Order it pays for.
 * One order can, in principle, have more than one payment attempt (e.g. a declined card
 * followed by a retry on a different card) - this is the microservice's own aggregate.
 */
data class GatewayPayment(
    val id: String = UUID.randomUUID().toString(),
    val orderId: String,
    val terminalTransactionId: String,
    val amount: Double,
    val currency: String,
    val status: GatewayPaymentStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val declineReason: String? = null,
    val refundId: String? = null,
    val refundedAmount: Double? = null
)
