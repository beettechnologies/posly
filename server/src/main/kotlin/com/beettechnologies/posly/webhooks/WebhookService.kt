package com.beettechnologies.posly.webhooks

import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.OrderEventListener
import com.beettechnologies.posly.cart.OrderEventType
import com.beettechnologies.posly.cart.OrderResponse
import com.beettechnologies.posly.cart.toResponse
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.GatewayTransientException
import com.beettechnologies.posly.gateway.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val WEBHOOK_SIGNATURE_HEADER = "X-Posly-Signature"

sealed class RegisterWebhookResult {
    data class Success(val subscription: WebhookSubscription) : RegisterWebhookResult()
    data class InvalidRequest(val message: String) : RegisterWebhookResult()
}

/**
 * Lets external systems subscribe to order lifecycle events ([WebhookEventType]) and delivers them
 * as signed HTTP POSTs, retrying transient failures with exponential backoff
 * ([com.beettechnologies.posly.gateway.RetryPolicy]) before giving up and dead-lettering the
 * delivery for manual inspection. Implements [OrderEventListener] directly so
 * [com.beettechnologies.posly.cart.OrderService] can notify it without knowing webhooks exist.
 *
 * [deliveryScope] mirrors `PaymentGatewayService`'s `autoResolveScope` pattern: when set (the live
 * application, via its own `CoroutineScope`), delivery runs in the background so the request that
 * triggered the event (checkout, payment confirmation) returns immediately. When null (e.g. most
 * unit tests), [publish] only records the pending delivery - call [deliverNow] to drive it
 * synchronously.
 */
class WebhookService(
    private val httpClient: HttpClient,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 4, initialDelayMillis = 500, backoffFactor = 2.0),
    private val deliveryScope: CoroutineScope? = null
) : OrderEventListener {

    private val subscriptions = ConcurrentHashMap<String, WebhookSubscription>()
    private val deliveries = ConcurrentHashMap<String, WebhookDelivery>()
    private val json = Json { encodeDefaults = true }

    fun register(url: String, secret: String, eventTypes: Set<WebhookEventType>): RegisterWebhookResult {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return RegisterWebhookResult.InvalidRequest("url must be a valid http(s) URL")
        }
        if (secret.isBlank()) return RegisterWebhookResult.InvalidRequest("secret is required")
        if (eventTypes.isEmpty()) return RegisterWebhookResult.InvalidRequest("at least one eventType is required")

        val subscription = WebhookSubscription(url = url, secret = secret, eventTypes = eventTypes, createdAt = nowProvider())
        subscriptions[subscription.id] = subscription
        return RegisterWebhookResult.Success(subscription)
    }

    fun listSubscriptions(): List<WebhookSubscription> = subscriptions.values.sortedByDescending { it.createdAt }

    fun getSubscription(id: String): WebhookSubscription? = subscriptions[id]

    fun listDeliveries(subscriptionId: String? = null, status: WebhookDeliveryStatus? = null): List<WebhookDelivery> =
        deliveries.values
            .filter { (subscriptionId == null || it.subscriptionId == subscriptionId) && (status == null || it.status == status) }
            .sortedByDescending { it.createdAt }

    fun listDeadLettered(): List<WebhookDelivery> = listDeliveries(status = WebhookDeliveryStatus.DEAD_LETTERED)

    override fun onEvent(order: Order, type: OrderEventType) {
        val eventType = when (type) {
            OrderEventType.CREATED -> WebhookEventType.ORDER_CREATED
            OrderEventType.PAYMENT_CONFIRMED -> WebhookEventType.PAYMENT_SUCCEEDED
            OrderEventType.REFUNDED -> return
        }
        publish(eventType, eventId = order.id, data = order.toResponse())
    }

    /** Enqueues a delivery to every subscription registered for [eventType]; returns the created (still-pending) deliveries. */
    fun publish(eventType: WebhookEventType, eventId: String, data: OrderResponse): List<WebhookDelivery> =
        subscriptions.values.filter { eventType in it.eventTypes }.map { subscription ->
            val envelope = WebhookEventEnvelope(
                id = UUID.randomUUID().toString(),
                type = eventType.name,
                occurredAt = nowProvider().toString(),
                data = json.encodeToJsonElement(OrderResponse.serializer(), data)
            )
            val delivery = WebhookDelivery(
                subscriptionId = subscription.id,
                eventType = eventType,
                eventId = eventId,
                payload = json.encodeToString(envelope),
                status = WebhookDeliveryStatus.PENDING,
                attempts = 0,
                createdAt = nowProvider()
            )
            deliveries[delivery.id] = delivery
            deliveryScope?.launch { attemptDelivery(delivery.id) }
            delivery
        }

    /** Synchronously drives one pending delivery to completion (delivered or dead-lettered). */
    suspend fun deliverNow(deliveryId: String) = attemptDelivery(deliveryId)

    private suspend fun attemptDelivery(deliveryId: String) {
        val delivery = deliveries[deliveryId] ?: return
        val subscription = subscriptions[delivery.subscriptionId] ?: return
        var attemptCount = 0

        try {
            retryPolicy.withBackoff {
                attemptCount++
                deliveries.computeIfPresent(deliveryId) { _, d -> d.copy(attempts = attemptCount) }

                val statusCode = try {
                    httpClient.post(subscription.url) {
                        contentType(ContentType.Application.Json)
                        header(WEBHOOK_SIGNATURE_HEADER, hmacSha256Hex(subscription.secret, delivery.payload))
                        setBody(delivery.payload)
                    }.status.value
                } catch (e: Exception) {
                    throw GatewayTransientException("Webhook request failed: ${e.message}")
                }

                deliveries.computeIfPresent(deliveryId) { _, d -> d.copy(lastStatusCode = statusCode) }
                when (statusCode) {
                    in 200..299 -> Unit
                    in 500..599 -> throw GatewayTransientException("Webhook endpoint responded with $statusCode")
                    else -> throw GatewayException("Webhook endpoint responded with $statusCode")
                }
            }
            deliveries.computeIfPresent(deliveryId) { _, d ->
                d.copy(status = WebhookDeliveryStatus.DELIVERED, deliveredAt = nowProvider())
            }
        } catch (e: Exception) {
            deliveries.computeIfPresent(deliveryId) { _, d ->
                d.copy(status = WebhookDeliveryStatus.DEAD_LETTERED, lastError = e.message, deadLetteredAt = nowProvider())
            }
        }
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
