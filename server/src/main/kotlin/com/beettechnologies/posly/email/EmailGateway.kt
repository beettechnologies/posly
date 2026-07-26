package com.beettechnologies.posly.email

import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.GatewayTransientException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abstracts email provider differences (SMTP, SES, ...) behind one contract, the same way
 * PaymentGateway abstracts payment terminal vendors. A real adapter is a drop-in implementation
 * of this same interface, built once real provider credentials are available.
 */
interface EmailGateway {
    /** Sends an email with [pdfBytes] attached to [recipient]; returns the provider's message id. */
    suspend fun sendReceipt(recipient: String, subject: String, pdfBytes: ByteArray): String

    /** Sends a plain-text/HTML email (no attachment) to [recipient] - e.g. an account invite. Returns the provider's message id. */
    suspend fun sendPlainText(recipient: String, subject: String, body: String): String
}

/**
 * A working in-memory stand-in for a real email provider. [transientFailuresBeforeSuccess] lets
 * tests exercise retry/backoff by failing the first N calls before succeeding - mirrors
 * SimulatorPaymentGateway exactly. Any recipient address containing "+bounce" always simulates a
 * permanent send failure (a hard bounce) - the same "amount ending in .13" demoable-failure
 * convention already used for the payment terminal simulator, applied to email addresses.
 */
class SimulatorEmailGateway(
    private val transientFailuresBeforeSuccess: Int = 0
) : EmailGateway {

    private val attempts = AtomicInteger(0)

    override suspend fun sendReceipt(recipient: String, subject: String, pdfBytes: ByteArray): String =
        simulateSend(recipient)

    override suspend fun sendPlainText(recipient: String, subject: String, body: String): String =
        simulateSend(recipient)

    private fun simulateSend(recipient: String): String {
        if (recipient.contains("+bounce")) {
            throw GatewayException("Simulated permanent bounce for $recipient")
        }
        if (attempts.getAndIncrement() < transientFailuresBeforeSuccess) {
            throw GatewayTransientException("Simulated transient email provider failure")
        }
        return "email_${UUID.randomUUID().toString().take(12).uppercase()}"
    }
}
