package com.beettechnologies.posly.payments

import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.OrderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

sealed class CreatePaymentResult {
    data class Success(val payment: GatewayPayment) : CreatePaymentResult()
    data object OrderNotFound : CreatePaymentResult()
    data class GatewayError(val message: String) : CreatePaymentResult()
}

sealed class WebhookResult {
    data class Success(val payment: GatewayPayment) : WebhookResult()
    data object PaymentNotFound : WebhookResult()
    data object AlreadyProcessed : WebhookResult()
}

sealed class RefundPaymentResult {
    data class Success(val payment: GatewayPayment) : RefundPaymentResult()
    data object PaymentNotFound : RefundPaymentResult()
    data object NotApproved : RefundPaymentResult()
    data class GatewayError(val message: String) : RefundPaymentResult()
}

/**
 * The payment microservice's orchestration layer: creates payments against the abstracted
 * [PaymentGateway], applies asynchronous terminal webhooks, and drives refunds - notifying
 * [OrderService] whenever a payment's outcome changes the order it belongs to.
 *
 * There is no real terminal here, so [autoResolveScope], when supplied, stands in for one: after
 * [autoResolveDelayMillis] it resolves a still-INITIATED payment itself, the same way a real
 * terminal's webhook would. The outcome is a deterministic function of the amount's cents so a
 * cashier (or a test) can trigger a given outcome on demand - mirrors this project's existing
 * "test pairing code" convention for demoable, memorable test values:
 *  - a total ending in .13 -> DECLINED
 *  - a total ending in .99 -> left INITIATED forever, simulating a terminal that never responds
 *  - anything else -> APPROVED
 * Left null (the default), auto-resolution is disabled entirely - existing/production behavior
 * where only a real webhook call can move a payment out of INITIATED.
 */
class PaymentGatewayService(
    private val gateway: PaymentGateway,
    private val orderService: OrderService,
    private val webhookSecret: String,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val nowProvider: () -> Instant = { Instant.now() },
    private val autoResolveScope: CoroutineScope? = null,
    private val autoResolveDelayMillis: Long = 2000
) {
    private val payments = ConcurrentHashMap<String, GatewayPayment>()
    private val processedWebhookEventIds = ConcurrentHashMap.newKeySet<String>()
    private val refundAttempts = ConcurrentHashMap<String, RefundAttempt>()

    suspend fun createPayment(orderId: String, amount: Double, currency: String): CreatePaymentResult {
        if (orderService.getOrder(orderId) == null) return CreatePaymentResult.OrderNotFound

        return try {
            val terminalTransactionId = retryPolicy.withBackoff { gateway.createPayment(orderId, amount, currency) }
            val now = nowProvider()
            val payment = GatewayPayment(
                orderId = orderId,
                terminalTransactionId = terminalTransactionId,
                amount = amount,
                currency = currency,
                status = GatewayPaymentStatus.INITIATED,
                createdAt = now,
                updatedAt = now
            )
            payments[payment.id] = payment
            scheduleAutoResolve(payment)
            CreatePaymentResult.Success(payment)
        } catch (e: GatewayException) {
            CreatePaymentResult.GatewayError(e.message ?: "Gateway error")
        }
    }

    private fun scheduleAutoResolve(payment: GatewayPayment) {
        val scope = autoResolveScope ?: return
        scope.launch {
            delay(autoResolveDelayMillis)
            when ((payment.amount * 100).roundToInt() % 100) {
                13 -> handleWebhook(
                    eventId = "auto-resolve-${payment.id}",
                    terminalTransactionId = payment.terminalTransactionId,
                    approved = false,
                    declineReason = "Card declined (simulated)"
                )
                99 -> Unit
                else -> handleWebhook(
                    eventId = "auto-resolve-${payment.id}",
                    terminalTransactionId = payment.terminalTransactionId,
                    approved = true,
                    declineReason = null
                )
            }
        }
    }

    fun getPayment(id: String): GatewayPayment? = payments[id]

    /** Verifies an HMAC-SHA256 signature over the exact raw webhook body, using a constant-time comparison. */
    fun verifySignature(rawBody: String, signatureHeader: String?): Boolean {
        if (signatureHeader.isNullOrBlank()) return false
        val expected = hmacSha256Hex(webhookSecret, rawBody)
        return MessageDigest.isEqual(expected.toByteArray(), signatureHeader.toByteArray())
    }

    /**
     * Applies an asynchronous terminal webhook. Idempotent by [eventId] - a redelivered webhook
     * (the gateway's own retry, not ours) is a safe no-op rather than a double-processed event.
     * An approved payment confirms its order via [OrderService.confirmPayment].
     */
    fun handleWebhook(eventId: String, terminalTransactionId: String, approved: Boolean, declineReason: String?): WebhookResult {
        if (!processedWebhookEventIds.add(eventId)) return WebhookResult.AlreadyProcessed

        val existing = payments.values.find { it.terminalTransactionId == terminalTransactionId }
            ?: return WebhookResult.PaymentNotFound

        var updated: GatewayPayment = existing
        payments.compute(existing.id) { _, current ->
            val target = current ?: existing
            updated = target.copy(
                status = if (approved) GatewayPaymentStatus.APPROVED else GatewayPaymentStatus.DECLINED,
                declineReason = if (approved) null else declineReason,
                updatedAt = nowProvider()
            )
            updated
        }

        if (approved) {
            orderService.confirmPayment(
                orderId = updated.orderId,
                method = "TERMINAL",
                amount = updated.amount,
                reference = terminalTransactionId,
                actorId = null,
                maskedCardNumber = updated.maskedCardNumber
            )
        }
        return WebhookResult.Success(updated)
    }

    /**
     * Idempotent by [refundId]: retrying the same refund replays the original result instead of
     * refunding twice. Every attempt - success or gateway failure - is recorded in [refundAttempts]
     * so a failed one is never silently lost; see [listUnresolvedRefunds].
     */
    suspend fun refund(paymentId: String, refundId: String, amount: Double): RefundPaymentResult {
        val payment = payments[paymentId] ?: return RefundPaymentResult.PaymentNotFound
        if (payment.status == GatewayPaymentStatus.REFUNDED && payment.refundId == refundId) {
            return RefundPaymentResult.Success(payment)
        }
        if (payment.status != GatewayPaymentStatus.APPROVED) return RefundPaymentResult.NotApproved

        return try {
            retryPolicy.withBackoff { gateway.refund(payment.terminalTransactionId, amount, refundId) }

            var updated: GatewayPayment = payment
            payments.compute(paymentId) { _, current ->
                val target = current ?: payment
                updated = target.copy(
                    status = GatewayPaymentStatus.REFUNDED,
                    refundId = refundId,
                    refundedAmount = amount,
                    updatedAt = nowProvider()
                )
                updated
            }
            recordRefundAttempt(refundId, paymentId, updated.orderId, amount, succeeded = true, error = null)

            val order = orderService.getOrder(updated.orderId)
            if (order?.status == OrderStatus.PAID) {
                orderService.refund(updated.orderId, refundId, "Gateway refund $refundId", actorId = null)
            }
            RefundPaymentResult.Success(updated)
        } catch (e: GatewayException) {
            recordRefundAttempt(refundId, paymentId, payment.orderId, amount, succeeded = false, error = e.message ?: "Gateway error")
            RefundPaymentResult.GatewayError(e.message ?: "Gateway error")
        }
    }

    private fun recordRefundAttempt(
        refundId: String,
        paymentId: String,
        orderId: String,
        amount: Double,
        succeeded: Boolean,
        error: String?
    ) {
        val now = nowProvider()
        refundAttempts.compute(refundId) { _, existing ->
            RefundAttempt(
                refundId = refundId,
                paymentId = paymentId,
                orderId = orderId,
                amount = amount,
                status = if (succeeded) RefundAttemptStatus.SUCCEEDED else RefundAttemptStatus.FAILED,
                attempts = (existing?.attempts ?: 0) + 1,
                lastError = error,
                requestedAt = existing?.requestedAt ?: now,
                resolvedAt = if (succeeded) now else null
            )
        }
    }

    /** Refund attempts that failed at the gateway and have not since succeeded on a retry with the same refundId. */
    fun listUnresolvedRefunds(): List<RefundAttempt> =
        refundAttempts.values.filter { it.status == RefundAttemptStatus.FAILED }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
