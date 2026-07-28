package com.beettechnologies.posly.email

import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.receipts.ReceiptRenderer
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.Store
import com.beettechnologies.posly.stores.StoreService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Falls back to sensible defaults when [storeService] is unavailable or an order's storeId no longer resolves to a real store, so receipt rendering degrades gracefully rather than throwing. */
private fun resolveStore(storeService: StoreService?, storeId: String): Store =
    storeService?.getStore(storeId) ?: Store(
        id = storeId,
        name = "Store",
        address = Address(line1 = "", city = "", postalCode = "", country = ""),
        timezone = "UTC",
        currency = "USD"
    )

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

enum class EmailStatus { SENT, FAILED }

data class EmailRecord(
    val id: String = UUID.randomUUID().toString(),
    val orderId: String,
    val recipient: String,
    val status: EmailStatus,
    val message: String? = null,
    val sentAt: Instant
)

sealed class SendReceiptEmailResult {
    data class Success(val email: EmailRecord) : SendReceiptEmailResult()
    data class Failed(val email: EmailRecord) : SendReceiptEmailResult()
    data object OrderNotFound : SendReceiptEmailResult()
    data class InvalidEmail(val message: String) : SendReceiptEmailResult()
}

/**
 * Emails a PDF receipt for an order. A malformed address is rejected up front (never reaches the
 * gateway); a gateway failure (permanent, or transient retries exhausted) is recorded as a FAILED
 * [EmailRecord] and reported back rather than thrown, so callers can offer the cashier a clear
 * "email failed" outcome instead of a generic error.
 */
class EmailService(
    private val orderService: OrderService,
    private val gateway: EmailGateway,
    private val storeService: StoreService? = null,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val emails = ConcurrentHashMap<String, EmailRecord>()

    suspend fun sendReceipt(orderId: String, recipient: String): SendReceiptEmailResult {
        if (!EMAIL_PATTERN.matches(recipient)) {
            return SendReceiptEmailResult.InvalidEmail("'$recipient' is not a valid email address")
        }
        val order = orderService.getOrder(orderId) ?: return SendReceiptEmailResult.OrderNotFound
        val now = nowProvider()

        return try {
            val store = resolveStore(storeService, order.storeId)
            val logoBytes = storeService?.getLogo(order.storeId)?.bytes
            val pdfBytes = ReceiptRenderer.renderPdf(order, store, logoBytes)
            retryPolicy.withBackoff { gateway.sendReceipt(recipient, subject = "Your receipt", pdfBytes = pdfBytes) }
            val record = EmailRecord(orderId = orderId, recipient = recipient, status = EmailStatus.SENT, sentAt = now)
            emails[record.id] = record
            SendReceiptEmailResult.Success(record)
        } catch (e: GatewayException) {
            val record = EmailRecord(
                orderId = orderId, recipient = recipient, status = EmailStatus.FAILED,
                message = e.message ?: "Failed to send email", sentAt = now
            )
            emails[record.id] = record
            SendReceiptEmailResult.Failed(record)
        }
    }

    fun listEmails(orderId: String): List<EmailRecord> = emails.values.filter { it.orderId == orderId }
}
