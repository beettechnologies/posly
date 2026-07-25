package com.beettechnologies.posly.cart

import com.beettechnologies.posly.products.TaxCategory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderServiceTest {

    private fun seedCart(storeId: String = "store-1"): Cart {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        return Cart(
            id = "cart-1",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(
                CartItem(
                    productId = "product-1",
                    productName = "Widget",
                    quantity = 1,
                    unitPrice = 10.0,
                    taxCategory = TaxCategory.STANDARD
                )
            ),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun seedTotals() = CartTotals(
        subtotal = 10.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 10.0, taxBreakdown = emptyList(), totalTax = 0.0, total = 10.0
    )

    @Test
    fun `a newly created order starts pending with no payment or refund`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        assertEquals(OrderStatus.PENDING, order.status)
        assertNull(order.payment)
        assertNull(order.refund)
        assertEquals(1, service.listEvents(order.id).size)
        assertEquals(OrderEventType.CREATED, service.listEvents(order.id).single().type)
    }

    @Test
    fun `confirming payment transitions pending to paid and records the payment`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        val result = service.confirmPayment(order.id, method = "CARD", amount = 10.0, reference = "auth-123", actorId = "cashier-1")

        val success = assertIs<ConfirmPaymentResult.Success>(result)
        assertEquals(OrderStatus.PAID, success.order.status)
        assertEquals("CARD", success.order.payment?.method)
        assertEquals(10.0, success.order.payment?.amount)
        assertEquals("auth-123", success.order.payment?.reference)
        assertEquals("cashier-1", success.order.payment?.confirmedBy)

        val events = service.listEvents(order.id)
        assertEquals(listOf(OrderEventType.CREATED, OrderEventType.PAYMENT_CONFIRMED), events.map { it.type })
    }

    @Test
    fun `confirming payment on a non-pending order is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")

        val result = service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")

        assertEquals(ConfirmPaymentResult.NotPending, result)
    }

    @Test
    fun `confirming payment with a non-positive amount is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        val result = service.confirmPayment(order.id, "CARD", 0.0, null, "cashier-1")

        assertIs<ConfirmPaymentResult.InvalidAmount>(result)
    }

    @Test
    fun `confirming payment on an unknown order is rejected`() {
        val service = OrderService()
        assertEquals(ConfirmPaymentResult.OrderNotFound, service.confirmPayment("does-not-exist", "CARD", 10.0, null, null))
    }

    @Test
    fun `refunding a paid order transitions to refunded and records the refund`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")

        val result = service.refund(order.id, "refund-1", reason = "Customer changed their mind", actorId = "manager-1")

        val success = assertIs<RefundResult.Success>(result)
        assertEquals(false, success.replayed)
        assertEquals(OrderStatus.REFUNDED, success.order.status)
        assertEquals("refund-1", success.order.refund?.refundId)
        assertEquals(10.0, success.order.refund?.amount)
        assertEquals("Customer changed their mind", success.order.refund?.reason)
        assertEquals("manager-1", success.order.refund?.refundedBy)

        val events = service.listEvents(order.id)
        assertEquals(
            listOf(OrderEventType.CREATED, OrderEventType.PAYMENT_CONFIRMED, OrderEventType.REFUNDED),
            events.map { it.type }
        )
    }

    @Test
    fun `refunding a pending (unpaid) order is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        val result = service.refund(order.id, "refund-1", null, "manager-1")

        assertEquals(RefundResult.NotPaid, result)
    }

    @Test
    fun `refunding twice with the same refundId replays the original result instead of refunding twice`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")

        val first = assertIs<RefundResult.Success>(service.refund(order.id, "refund-1", null, "manager-1"))
        val second = assertIs<RefundResult.Success>(service.refund(order.id, "refund-1", null, "manager-1"))

        assertEquals(false, first.replayed)
        assertEquals(true, second.replayed)
        assertEquals(first.order, second.order)
        assertEquals(
            listOf(OrderEventType.CREATED, OrderEventType.PAYMENT_CONFIRMED, OrderEventType.REFUNDED),
            service.listEvents(order.id).map { it.type },
            "a replayed refund must not record a second REFUNDED event"
        )
    }

    @Test
    fun `refunding an already-refunded order with a different refundId is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")
        service.refund(order.id, "refund-1", null, "manager-1")

        val result = service.refund(order.id, "refund-2", null, "manager-1")

        assertEquals(RefundResult.AlreadyRefunded, result)
    }

    @Test
    fun `refunding an unknown order is rejected`() {
        val service = OrderService()
        assertEquals(RefundResult.OrderNotFound, service.refund("does-not-exist", "refund-1", null, null))
    }

    @Test
    fun `deleting an order also removes its events`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        assertTrue(service.listEvents(order.id).isNotEmpty())

        service.deleteOrder(order.id)

        assertNull(service.getOrder(order.id))
        assertTrue(service.listEvents(order.id).isEmpty())
    }
}
