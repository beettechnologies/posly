package com.beettechnologies.posly.payments

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** A gateway-side failure. Non-transient by default - retrying would not help. */
open class GatewayException(message: String) : Exception(message)

/** A transient gateway failure (e.g. a network blip) - safe and expected to retry. */
class GatewayTransientException(message: String) : GatewayException(message)

/**
 * Abstracts terminal/gateway differences behind one contract so the rest of the payment
 * microservice never depends on a specific vendor's API shape. A real adapter (e.g. for a
 * specific payment terminal vendor) is a drop-in implementation of this same interface, built
 * once real sandbox credentials and API documentation are available.
 */
interface PaymentGateway {
    /** Asks the gateway to start a payment; returns its terminal-assigned transaction id. */
    suspend fun createPayment(orderId: String, amount: Double, currency: String): String

    /** Asks the gateway to refund a previously-approved payment; returns its refund confirmation id. */
    suspend fun refund(terminalTransactionId: String, amount: Double, refundId: String): String
}

/**
 * A working in-memory stand-in for a real terminal/gateway: synchronously "creates" a payment
 * (a real terminal would confirm asynchronously via webhook, which callers simulate by posting
 * to the webhook endpoint), and "refunds" one. [transientFailuresBeforeSuccess] lets tests
 * exercise retry/backoff by failing the first N calls (across create and refund combined) before
 * succeeding.
 */
class SimulatorPaymentGateway(
    private val transientFailuresBeforeSuccess: Int = 0
) : PaymentGateway {

    private val attempts = AtomicInteger(0)

    override suspend fun createPayment(orderId: String, amount: Double, currency: String): String {
        maybeFailTransiently()
        return "term_${randomToken()}"
    }

    override suspend fun refund(terminalTransactionId: String, amount: Double, refundId: String): String {
        maybeFailTransiently()
        return "refund_${randomToken()}"
    }

    private fun maybeFailTransiently() {
        if (attempts.getAndIncrement() < transientFailuresBeforeSuccess) {
            throw GatewayTransientException("Simulated transient gateway failure")
        }
    }

    private fun randomToken(): String = UUID.randomUUID().toString().take(12).uppercase()
}
