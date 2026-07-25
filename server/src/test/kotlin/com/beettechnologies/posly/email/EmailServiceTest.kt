package com.beettechnologies.posly.email

import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.products.TaxCategory
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val FAST_RETRY = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1, backoffFactor = 1.0)

private fun seedCart(): Cart {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    return Cart(
        id = "cart-1",
        storeId = "store-1",
        createdBy = "cashier-1",
        items = listOf(
            CartItem(productId = "product-1", productName = "Widget", quantity = 1, unitPrice = 10.0, taxCategory = TaxCategory.STANDARD)
        ),
        createdAt = now,
        updatedAt = now
    )
}

private fun seedTotals() = CartTotals(
    subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
    taxableAmount = 10.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 10.0
)

class EmailServiceTest {

    @Test
    fun `emailing a receipt to a valid address succeeds`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val service = EmailService(orderService, SimulatorEmailGateway())

        val result = service.sendReceipt(order.id, "customer@example.com")

        val success = assertIs<SendReceiptEmailResult.Success>(result)
        assertEquals(EmailStatus.SENT, success.email.status)
        assertEquals("customer@example.com", success.email.recipient)
    }

    @Test
    fun `a recipient address containing plus-bounce always fails permanently`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val service = EmailService(orderService, SimulatorEmailGateway())

        val result = service.sendReceipt(order.id, "customer+bounce@example.com")

        val failed = assertIs<SendReceiptEmailResult.Failed>(result)
        assertEquals(EmailStatus.FAILED, failed.email.status)
    }

    @Test
    fun `a transient email provider failure is retried and eventually succeeds`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val service = EmailService(orderService, SimulatorEmailGateway(transientFailuresBeforeSuccess = 2), retryPolicy = FAST_RETRY)

        val result = service.sendReceipt(order.id, "customer@example.com")

        assertIs<SendReceiptEmailResult.Success>(result)
        Unit
    }

    @Test
    fun `a transient failure that exhausts retries is reported as failed`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val service = EmailService(orderService, SimulatorEmailGateway(transientFailuresBeforeSuccess = 10), retryPolicy = FAST_RETRY)

        val result = service.sendReceipt(order.id, "customer@example.com")

        assertIs<SendReceiptEmailResult.Failed>(result)
        Unit
    }

    @Test
    fun `a malformed email address is rejected before ever reaching the gateway`() = runBlocking {
        val orderService = OrderService()
        val order = orderService.createOrder(seedCart(), seedTotals(), "key-1")
        val service = EmailService(orderService, SimulatorEmailGateway())

        val result = service.sendReceipt(order.id, "not-an-email")

        assertIs<SendReceiptEmailResult.InvalidEmail>(result)
        assertEquals(emptyList(), service.listEmails(order.id))
    }

    @Test
    fun `emailing a receipt for an unknown order is rejected`() = runBlocking {
        val orderService = OrderService()
        val service = EmailService(orderService, SimulatorEmailGateway())

        assertEquals(SendReceiptEmailResult.OrderNotFound, service.sendReceipt("does-not-exist", "customer@example.com"))
    }
}
