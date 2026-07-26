package com.beettechnologies.posly.webhooks

import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.OrderEventType
import com.beettechnologies.posly.cart.toResponse
import com.beettechnologies.posly.gateway.RetryPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val FAST_RETRY = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1, backoffFactor = 1.0)

private fun hmacSha256Hex(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun testOrder(id: String = "order-1") = Order(
    id = id,
    cartId = "cart-1",
    storeId = "store-1",
    createdBy = "cashier-1",
    items = emptyList(),
    discount = null,
    totals = CartTotals(10.0, 0.0, 0.0, 10.0, emptyList(), 0.0, 10.0),
    idempotencyKey = "key-1",
    checkedOutAt = Instant.parse("2026-01-01T00:00:00Z")
)

class WebhookServiceTest {

    private fun newService(engine: MockEngine, retryPolicy: RetryPolicy = FAST_RETRY): WebhookService =
        WebhookService(HttpClient(engine), retryPolicy = retryPolicy, deliveryScope = null)

    @Test
    fun `registering a subscription with an invalid url is rejected`() {
        val service = newService(MockEngine { respond("") })

        val result = service.register("not-a-url", "secret", setOf(WebhookEventType.ORDER_CREATED))

        assertIs<RegisterWebhookResult.InvalidRequest>(result)
    }

    @Test
    fun `registering a subscription with a blank secret is rejected`() {
        val service = newService(MockEngine { respond("") })

        val result = service.register("https://example.com/hook", "", setOf(WebhookEventType.ORDER_CREATED))

        assertIs<RegisterWebhookResult.InvalidRequest>(result)
    }

    @Test
    fun `registering a subscription with no event types is rejected`() {
        val service = newService(MockEngine { respond("") })

        val result = service.register("https://example.com/hook", "secret", emptySet())

        assertIs<RegisterWebhookResult.InvalidRequest>(result)
    }

    @Test
    fun `a valid registration is listed and retrievable by id`() {
        val service = newService(MockEngine { respond("") })

        val subscription = (service.register("https://example.com/hook", "secret", setOf(WebhookEventType.ORDER_CREATED)) as RegisterWebhookResult.Success).subscription

        assertEquals(listOf(subscription), service.listSubscriptions())
        assertEquals(subscription, service.getSubscription(subscription.id))
    }

    @Test
    fun `a successful delivery is signed correctly and marked DELIVERED`() = runBlocking {
        var capturedRequest: HttpRequestData? = null
        val service = newService(MockEngine { request ->
            capturedRequest = request
            respond("", HttpStatusCode.OK)
        })
        val subscription = (service.register("https://example.com/hook", "shh", setOf(WebhookEventType.ORDER_CREATED)) as RegisterWebhookResult.Success).subscription

        val deliveries = service.publish(WebhookEventType.ORDER_CREATED, "order-1", testOrder().toResponse())
        val delivery = deliveries.single()
        service.deliverNow(delivery.id)

        val expectedSignature = hmacSha256Hex("shh", delivery.payload)
        assertEquals(expectedSignature, capturedRequest!!.headers[WEBHOOK_SIGNATURE_HEADER])

        val updated = service.listDeliveries(subscription.id).single()
        assertEquals(WebhookDeliveryStatus.DELIVERED, updated.status)
        assertEquals(1, updated.attempts)
        assertEquals(200, updated.lastStatusCode)
    }

    @Test
    fun `a persistent 500 response retries with backoff then dead-letters after the threshold`() = runBlocking {
        var callCount = 0
        val service = newService(MockEngine {
            callCount++
            respond("", HttpStatusCode.InternalServerError)
        }, retryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1, backoffFactor = 1.0))
        service.register("https://example.com/hook", "secret", setOf(WebhookEventType.ORDER_CREATED))

        val delivery = service.publish(WebhookEventType.ORDER_CREATED, "order-1", testOrder().toResponse()).single()
        service.deliverNow(delivery.id)

        assertEquals(3, callCount, "the retry policy's maxAttempts must bound the number of HTTP calls")
        val updated = service.listDeliveries().single()
        assertEquals(WebhookDeliveryStatus.DEAD_LETTERED, updated.status)
        assertEquals(3, updated.attempts)
        assertEquals(500, updated.lastStatusCode)
        assertTrue(updated.lastError!!.contains("500"))
        assertEquals(listOf(updated), service.listDeadLettered())
    }

    @Test
    fun `a 400 response is not retried and dead-letters after a single attempt`() = runBlocking {
        var callCount = 0
        val service = newService(MockEngine {
            callCount++
            respond("", HttpStatusCode.BadRequest)
        })
        service.register("https://example.com/hook", "secret", setOf(WebhookEventType.ORDER_CREATED))

        val delivery = service.publish(WebhookEventType.ORDER_CREATED, "order-1", testOrder().toResponse()).single()
        service.deliverNow(delivery.id)

        assertEquals(1, callCount, "a non-transient 4xx failure must not be retried")
        val updated = service.listDeliveries().single()
        assertEquals(WebhookDeliveryStatus.DEAD_LETTERED, updated.status)
        assertEquals(1, updated.attempts)
    }

    @Test
    fun `a delivery that fails twice then succeeds on retry ends up DELIVERED`() = runBlocking {
        var callCount = 0
        val service = newService(MockEngine {
            callCount++
            if (callCount < 3) respond("", HttpStatusCode.InternalServerError) else respond("", HttpStatusCode.OK)
        }, retryPolicy = RetryPolicy(maxAttempts = 5, initialDelayMillis = 1, backoffFactor = 1.0))
        service.register("https://example.com/hook", "secret", setOf(WebhookEventType.ORDER_CREATED))

        val delivery = service.publish(WebhookEventType.ORDER_CREATED, "order-1", testOrder().toResponse()).single()
        service.deliverNow(delivery.id)

        assertEquals(3, callCount)
        val updated = service.listDeliveries().single()
        assertEquals(WebhookDeliveryStatus.DELIVERED, updated.status)
        assertEquals(3, updated.attempts)
    }

    @Test
    fun `publish only enqueues deliveries for subscriptions subscribed to that event type`() {
        val service = newService(MockEngine { respond("", HttpStatusCode.OK) })
        service.register("https://example.com/orders", "secret", setOf(WebhookEventType.ORDER_CREATED))
        service.register("https://example.com/payments", "secret", setOf(WebhookEventType.PAYMENT_SUCCEEDED))
        service.register("https://example.com/both", "secret", setOf(WebhookEventType.ORDER_CREATED, WebhookEventType.PAYMENT_SUCCEEDED))

        val deliveries = service.publish(WebhookEventType.ORDER_CREATED, "order-1", testOrder().toResponse())

        assertEquals(2, deliveries.size)
    }

    @Test
    fun `onEvent maps CREATED to ORDER_CREATED and fully-paid PAYMENT_CONFIRMED to PAYMENT_SUCCEEDED`() {
        val service = newService(MockEngine { respond("", HttpStatusCode.OK) })
        service.register("https://example.com/hook", "secret", setOf(WebhookEventType.ORDER_CREATED, WebhookEventType.PAYMENT_SUCCEEDED))
        val order = testOrder()

        service.onEvent(order, OrderEventType.CREATED)
        assertEquals(1, service.listDeliveries().size)
        assertEquals(WebhookEventType.ORDER_CREATED, service.listDeliveries().single().eventType)

        service.onEvent(order, OrderEventType.PAYMENT_CONFIRMED)
        assertEquals(2, service.listDeliveries().size)
        assertTrue(service.listDeliveries().any { it.eventType == WebhookEventType.PAYMENT_SUCCEEDED })
    }

    @Test
    fun `onEvent ignores REFUNDED - there is no webhook event type for it`() {
        val service = newService(MockEngine { respond("", HttpStatusCode.OK) })
        service.register("https://example.com/hook", "secret", WebhookEventType.entries.toSet())

        service.onEvent(testOrder(), OrderEventType.REFUNDED)

        assertTrue(service.listDeliveries().isEmpty())
    }

    @Test
    fun `listDeliveries filters by subscription and status`() = runBlocking {
        val service = newService(MockEngine { respond("", HttpStatusCode.OK) })
        val subA = (service.register("https://example.com/a", "secret", setOf(WebhookEventType.ORDER_CREATED)) as RegisterWebhookResult.Success).subscription
        val subB = (service.register("https://example.com/b", "secret", setOf(WebhookEventType.ORDER_CREATED)) as RegisterWebhookResult.Success).subscription
        val deliveries = service.publish(WebhookEventType.ORDER_CREATED, "order-1", testOrder().toResponse())
        deliveries.forEach { service.deliverNow(it.id) }

        assertEquals(1, service.listDeliveries(subscriptionId = subA.id).size)
        assertEquals(1, service.listDeliveries(subscriptionId = subB.id).size)
        assertEquals(2, service.listDeliveries(status = WebhookDeliveryStatus.DELIVERED).size)
        assertEquals(0, service.listDeliveries(status = WebhookDeliveryStatus.DEAD_LETTERED).size)
    }
}
