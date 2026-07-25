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
    val refundedAmount: Double? = null,
    /** Fabricated by the simulator - there is no real terminal/PAN behind this, only a display stand-in for receipts. */
    val maskedCardNumber: String = "•••• •••• •••• ${(1000..9999).random()}"
)

enum class RefundAttemptStatus { SUCCEEDED, FAILED }

/**
 * A finance-facing audit trail of every refund attempt against the gateway, keyed by [refundId] -
 * separate from [GatewayPayment] because a failed attempt (see [PaymentGatewayService.refund])
 * leaves the payment itself untouched, with nothing else recording that the attempt ever happened.
 * A retry with the same [refundId] updates this same record in place (e.g. FAILED -> SUCCEEDED),
 * incrementing [attempts]; reconciliation surfaces whatever is still [RefundAttemptStatus.FAILED].
 */
data class RefundAttempt(
    val refundId: String,
    val paymentId: String,
    val orderId: String,
    val amount: Double,
    val status: RefundAttemptStatus,
    val attempts: Int,
    val lastError: String?,
    val requestedAt: Instant,
    val resolvedAt: Instant?
)
