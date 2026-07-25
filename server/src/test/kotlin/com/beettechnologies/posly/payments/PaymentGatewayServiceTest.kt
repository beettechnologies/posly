package com.beettechnologies.posly.payments

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.OrderStatus
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.products.TaxCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WEBHOOK_SECRET = "test-webhook-secret"
private val FAST_RETRY = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1, backoffFactor = 1.0)

/** Always succeeds at creating a payment but permanently (non-transiently) fails to refund it. */
private class AlwaysFailingRefundGateway : PaymentGateway {
    override suspend fun createPayment(orderId: String, amount: Double, currency: String): String = "term_test"
    override suspend fun refund(terminalTransactionId: String, amount: Double, refundId: String): String =
        throw GatewayException("Simulated persistent refund failure")
}

/** Fails to refund until [shouldFail] is flipped off, so a test can simulate "the gateway recovered on retry". */
private class FlakyRefundGateway : PaymentGateway {
    var shouldFail = true
    override suspend fun createPayment(orderId: String, amount: Double, currency: String): String = "term_test"
    override suspend fun refund(terminalTransactionId: String, amount: Double, refundId: String): String {
        if (shouldFail) throw GatewayException("Simulated persistent refund failure")
        return "refund_test"
    }
}

private fun hmac(secret: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

class PaymentGatewayServiceTest {

    private fun seedOrder(orderService: OrderService, amount: Double = 10.0): Order {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val cart = Cart(
            id = "cart-1",
            storeId = "store-1",
            createdBy = "cashier-1",
            items = listOf(
                CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = amount, taxCategory = TaxCategory.STANDARD)
            ),
            createdAt = now,
            updatedAt = now
        )
        val totals = CartTotals(amount, 0.0, 0.0, amount, emptyList(), 0.0, amount)
        return orderService.createOrder(cart, totals, "checkout-key-1")
    }

    private fun newService(gateway: PaymentGateway = SimulatorPaymentGateway()): Pair<PaymentGatewayService, OrderService> {
        val orderService = OrderService()
        val service = PaymentGatewayService(gateway, orderService, WEBHOOK_SECRET, retryPolicy = FAST_RETRY)
        return service to orderService
    }

    private fun newAutoResolvingService(): Pair<PaymentGatewayService, OrderService> {
        val orderService = OrderService()
        val service = PaymentGatewayService(
            SimulatorPaymentGateway(),
            orderService,
            WEBHOOK_SECRET,
            retryPolicy = FAST_RETRY,
            autoResolveScope = CoroutineScope(Dispatchers.Default),
            autoResolveDelayMillis = 5
        )
        return service to orderService
    }

    @Test
    fun `creating a payment for an existing order returns initiated status`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)

        val result = service.createPayment(order.id, 10.0, "USD")

        val success = assertIs<CreatePaymentResult.Success>(result)
        assertEquals(GatewayPaymentStatus.INITIATED, success.payment.status)
        assertTrue(success.payment.terminalTransactionId.isNotBlank())
    }

    @Test
    fun `creating a payment for an unknown order is rejected`() = runBlocking {
        val (service, _) = newService()
        val result = service.createPayment("does-not-exist", 10.0, "USD")
        assertEquals(CreatePaymentResult.OrderNotFound, result)
    }

    @Test
    fun `an approved webhook transitions the payment and confirms the order`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment

        val result = service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        val success = assertIs<WebhookResult.Success>(result)
        assertEquals(GatewayPaymentStatus.APPROVED, success.payment.status)
        assertEquals(OrderStatus.PAID, orderService.getOrder(order.id)?.status)
        assertEquals(payment.terminalTransactionId, orderService.getOrder(order.id)?.payments?.single()?.reference)
    }

    @Test
    fun `an approved card payment's masked card number ends up on the order's payment record`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        assertTrue(payment.maskedCardNumber.matches(Regex("""•{4} •{4} •{4} \d{4}""")))

        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        assertEquals(payment.maskedCardNumber, orderService.getOrder(order.id)?.payments?.single()?.maskedCardNumber)
    }

    @Test
    fun `a declined webhook transitions the payment without confirming the order`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment

        val result = service.handleWebhook("evt-1", payment.terminalTransactionId, approved = false, declineReason = "Insufficient funds")

        val success = assertIs<WebhookResult.Success>(result)
        assertEquals(GatewayPaymentStatus.DECLINED, success.payment.status)
        assertEquals("Insufficient funds", success.payment.declineReason)
        assertEquals(OrderStatus.PENDING, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `a redelivered webhook with the same event id is processed only once`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment

        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)
        val secondDelivery = service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        assertEquals(WebhookResult.AlreadyProcessed, secondDelivery)
    }

    @Test
    fun `a webhook for an unknown terminal transaction id is rejected`() = runBlocking {
        val (service, _) = newService()
        val result = service.handleWebhook("evt-1", "does-not-exist", approved = true, declineReason = null)
        assertEquals(WebhookResult.PaymentNotFound, result)
    }

    @Test
    fun `signature verification accepts a correctly signed body and rejects a tampered one`() {
        val (service, _) = newService()
        val body = """{"eventId":"evt-1"}"""
        val validSignature = hmac(WEBHOOK_SECRET, body)

        assertTrue(service.verifySignature(body, validSignature))
        assertFalse(service.verifySignature(body, hmac("wrong-secret", body)))
        assertFalse(service.verifySignature(body + "tampered", validSignature))
        assertFalse(service.verifySignature(body, null))
    }

    @Test
    fun `refunding an approved payment transitions it and refunds the order`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        val result = service.refund(payment.id, "refund-1", 10.0)

        val success = assertIs<RefundPaymentResult.Success>(result)
        assertEquals(GatewayPaymentStatus.REFUNDED, success.payment.status)
        assertEquals("refund-1", success.payment.refundId)
        assertEquals(OrderStatus.REFUNDED, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `refunding a payment that was never approved is rejected`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment

        val result = service.refund(payment.id, "refund-1", 10.0)

        assertEquals(RefundPaymentResult.NotApproved, result)
    }

    @Test
    fun `refunding twice with the same refund id is idempotent`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        val first = assertIs<RefundPaymentResult.Success>(service.refund(payment.id, "refund-1", 10.0))
        val second = assertIs<RefundPaymentResult.Success>(service.refund(payment.id, "refund-1", 10.0))

        assertEquals(first.payment, second.payment)
    }

    @Test
    fun `a transient gateway failure is retried and eventually succeeds`() = runBlocking {
        val (service, orderService) = newService(gateway = SimulatorPaymentGateway(transientFailuresBeforeSuccess = 2))
        val order = seedOrder(orderService)

        val result = service.createPayment(order.id, 10.0, "USD")

        val success = assertIs<CreatePaymentResult.Success>(result)
        assertTrue(success.payment.terminalTransactionId.isNotBlank())
    }

    @Test
    fun `a persistent gateway failure exhausts retries and surfaces as a gateway error`() = runBlocking {
        val (service, orderService) = newService(gateway = SimulatorPaymentGateway(transientFailuresBeforeSuccess = 10))
        val order = seedOrder(orderService)

        val result = service.createPayment(order.id, 10.0, "USD")

        val error = assertIs<CreatePaymentResult.GatewayError>(result)
        assertTrue(error.message.isNotBlank())
    }

    @Test
    fun `auto-resolve approves a normal-amount payment after the delay and confirms the order`() = runBlocking {
        val (service, orderService) = newAutoResolvingService()
        val order = seedOrder(orderService, amount = 10.0)

        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        delay(100)

        assertEquals(GatewayPaymentStatus.APPROVED, service.getPayment(payment.id)?.status)
        assertEquals(OrderStatus.PAID, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `auto-resolve declines a payment for a total ending in dot-13`() = runBlocking {
        val (service, orderService) = newAutoResolvingService()
        val order = seedOrder(orderService, amount = 10.13)

        val payment = (service.createPayment(order.id, 10.13, "USD") as CreatePaymentResult.Success).payment
        delay(100)

        val resolved = service.getPayment(payment.id)
        assertEquals(GatewayPaymentStatus.DECLINED, resolved?.status)
        assertEquals("Card declined (simulated)", resolved?.declineReason)
        assertEquals(OrderStatus.PENDING, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `auto-resolve leaves a payment for a total ending in dot-99 stuck at initiated`() = runBlocking {
        val (service, orderService) = newAutoResolvingService()
        val order = seedOrder(orderService, amount = 10.99)

        val payment = (service.createPayment(order.id, 10.99, "USD") as CreatePaymentResult.Success).payment
        delay(100)

        assertEquals(GatewayPaymentStatus.INITIATED, service.getPayment(payment.id)?.status)
        assertEquals(OrderStatus.PENDING, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `without an auto-resolve scope a payment stays initiated indefinitely`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)

        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        delay(50)

        assertEquals(GatewayPaymentStatus.INITIATED, service.getPayment(payment.id)?.status)
    }

    @Test
    fun `a persistently failing gateway refund is recorded as an unresolved attempt`() = runBlocking {
        val (service, orderService) = newService(gateway = AlwaysFailingRefundGateway())
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        val result = service.refund(payment.id, "refund-1", 10.0)

        assertIs<RefundPaymentResult.GatewayError>(result)
        val unresolved = service.listUnresolvedRefunds()
        assertEquals(1, unresolved.size)
        val attempt = unresolved.single()
        assertEquals("refund-1", attempt.refundId)
        assertEquals(payment.id, attempt.paymentId)
        assertEquals(order.id, attempt.orderId)
        assertEquals(RefundAttemptStatus.FAILED, attempt.status)
        assertEquals(1, attempt.attempts)
        assertTrue(attempt.lastError!!.isNotBlank())
        assertNull(attempt.resolvedAt)

        // The payment itself is untouched by the failed attempt - still approved, not refunded.
        assertEquals(GatewayPaymentStatus.APPROVED, service.getPayment(payment.id)?.status)
        assertEquals(OrderStatus.PAID, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `retrying a failed refund with the same refundId resolves it once the gateway succeeds`() = runBlocking {
        val gateway = FlakyRefundGateway()
        val (service, orderService) = newService(gateway = gateway)
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        assertIs<RefundPaymentResult.GatewayError>(service.refund(payment.id, "refund-1", 10.0))
        assertEquals(1, service.listUnresolvedRefunds().size)

        gateway.shouldFail = false
        val retryResult = assertIs<RefundPaymentResult.Success>(service.refund(payment.id, "refund-1", 10.0))

        assertTrue(service.listUnresolvedRefunds().isEmpty(), "a since-succeeded retry must no longer be unresolved")
        assertEquals(GatewayPaymentStatus.REFUNDED, retryResult.payment.status)
        assertEquals(OrderStatus.REFUNDED, orderService.getOrder(order.id)?.status)
    }

    @Test
    fun `a successful refund is not recorded as unresolved`() = runBlocking {
        val (service, orderService) = newService()
        val order = seedOrder(orderService)
        val payment = (service.createPayment(order.id, 10.0, "USD") as CreatePaymentResult.Success).payment
        service.handleWebhook("evt-1", payment.terminalTransactionId, approved = true, declineReason = null)

        service.refund(payment.id, "refund-1", 10.0)

        assertTrue(service.listUnresolvedRefunds().isEmpty())
    }
}
