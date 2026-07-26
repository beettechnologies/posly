package com.beettechnologies.posly.payments

import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.RefundLineItemInput
import com.beettechnologies.posly.cart.RefundPreviewResult
import com.beettechnologies.posly.cart.RefundResult
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.secrets.SecretName
import com.beettechnologies.posly.secrets.SecretsManager
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

/** Outcome of the unified, order-centric CARD refund entry point - see [PaymentGatewayService.refundOrder]. */
sealed class RefundOrderResult {
    data class Success(val payment: GatewayPayment, val order: Order) : RefundOrderResult()
    data object OrderNotFound : RefundOrderResult()
    data object NoApprovedCardPayment : RefundOrderResult()
    data object NotRefundable : RefundOrderResult()
    data object RefundWindowExpired : RefundOrderResult()
    data class InvalidLineItem(val message: String) : RefundOrderResult()
    /** The order was never touched - a gateway failure here is exactly where a manual fallback is offered. */
    data class GatewayError(val message: String) : RefundOrderResult()
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
    private val secretsManager: SecretsManager,
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

    /**
     * Verifies an HMAC-SHA256 signature over the exact raw webhook body, using a constant-time
     * comparison. Accepts a signature made with *any* currently-valid webhook secret version -
     * the current one, or a previous one still within its post-rotation grace period - so an
     * in-flight webhook signed just before a rotation isn't rejected.
     */
    fun verifySignature(rawBody: String, signatureHeader: String?): Boolean {
        if (signatureHeader.isNullOrBlank()) return false
        return secretsManager.validVersions(SecretName.PAYMENT_WEBHOOK_SECRET).any { version ->
            val expected = hmacSha256Hex(version.value, rawBody)
            MessageDigest.isEqual(expected.toByteArray(), signatureHeader.toByteArray())
        }
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
     * so a failed one is never silently lost; see [listUnresolvedRefunds]. This is purely a
     * payment/gateway-level operation - it does not touch the order; see [refundOrder] for the
     * unified, order-centric entry point that also finalizes the order once the gateway confirms.
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
            RefundPaymentResult.Success(updated)
        } catch (e: GatewayException) {
            recordRefundAttempt(refundId, paymentId, payment.orderId, amount, succeeded = false, error = e.message ?: "Gateway error")
            RefundPaymentResult.GatewayError(e.message ?: "Gateway error")
        }
    }

    /**
     * The unified, order-centric refund entry point for a CARD refund: finds the order's most
     * recent approved card payment, previews [lineItems] against the order via
     * [OrderService.previewRefund] (the single source of truth for refund eligibility/amount,
     * shared with the MANUAL path), attempts the gateway refund for that amount, and only once the
     * gateway confirms it finalizes the order via [OrderService.refund]. A gateway failure leaves
     * the order completely untouched - nothing needs rolling back - so the caller can cleanly
     * offer a manual fallback.
     */
    suspend fun refundOrder(
        orderId: String,
        refundId: String,
        lineItems: List<RefundLineItemInput>,
        reason: String?,
        actorId: String?
    ): RefundOrderResult {
        val existingAttempt = refundAttempts[refundId]
        if (existingAttempt?.status == RefundAttemptStatus.SUCCEEDED) {
            val replayedPayment = payments[existingAttempt.paymentId]
            val replayedOrder = orderService.getOrder(orderId)
            if (replayedPayment != null && replayedOrder != null) {
                return RefundOrderResult.Success(replayedPayment, replayedOrder)
            }
        }

        val payment = payments.values
            .filter { it.orderId == orderId && it.status == GatewayPaymentStatus.APPROVED }
            .maxByOrNull { it.createdAt }
            ?: return RefundOrderResult.NoApprovedCardPayment

        val amount = when (val preview = orderService.previewRefund(orderId, lineItems)) {
            is RefundPreviewResult.Success -> preview.amount
            RefundPreviewResult.OrderNotFound -> return RefundOrderResult.OrderNotFound
            RefundPreviewResult.NotRefundable -> return RefundOrderResult.NotRefundable
            RefundPreviewResult.RefundWindowExpired -> return RefundOrderResult.RefundWindowExpired
            is RefundPreviewResult.InvalidLineItem -> return RefundOrderResult.InvalidLineItem(preview.message)
        }

        return when (val gatewayResult = refund(payment.id, refundId, amount)) {
            is RefundPaymentResult.Success -> {
                when (val orderResult = orderService.refund(orderId, refundId, "CARD", lineItems, reason, actorId)) {
                    is RefundResult.Success -> RefundOrderResult.Success(gatewayResult.payment, orderResult.order)
                    // Unreachable in practice - previewRefund just validated the same order+lineItems -
                    // mapped defensively rather than assuming so.
                    RefundResult.OrderNotFound -> RefundOrderResult.OrderNotFound
                    RefundResult.NotRefundable -> RefundOrderResult.NotRefundable
                    RefundResult.RefundWindowExpired -> RefundOrderResult.RefundWindowExpired
                    is RefundResult.InvalidLineItem -> RefundOrderResult.InvalidLineItem(orderResult.message)
                }
            }
            RefundPaymentResult.PaymentNotFound -> RefundOrderResult.NoApprovedCardPayment
            RefundPaymentResult.NotApproved -> RefundOrderResult.NoApprovedCardPayment
            is RefundPaymentResult.GatewayError -> RefundOrderResult.GatewayError(gatewayResult.message)
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
