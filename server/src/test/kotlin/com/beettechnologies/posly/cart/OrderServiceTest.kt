package com.beettechnologies.posly.cart

import com.beettechnologies.posly.products.TaxCategory
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderServiceTest {

    private fun seedCart(storeId: String = "store-1", quantity: Int = 1): Cart {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        return Cart(
            id = "cart-1",
            storeId = storeId,
            createdBy = "cashier-1",
            items = listOf(
                CartItem(
                    productId = "product-1",
                    productName = "Widget",
                    quantity = quantity,
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

    /** A two-unit cart (subtotal 20) with a flat 10% tax, so tax proration math is non-trivial to verify. */
    private fun seedTaxedTotals() = CartTotals(
        subtotal = 20.0, itemDiscountTotal = 0.0, cartDiscountAmount = 0.0,
        taxableAmount = 20.0, taxBreakdown = emptyList(), totalTax = 2.0, total = 22.0
    )

    @Test
    fun `a newly created order starts pending with no payment or refund`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        assertEquals(OrderStatus.PENDING, order.status)
        assertTrue(order.payments.isEmpty())
        assertTrue(order.refunds.isEmpty())
        assertEquals(0.0, order.amountRefunded)
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
        assertEquals("CARD", success.order.payments.single().method)
        assertEquals(10.0, success.order.payments.single().amount)
        assertEquals("auth-123", success.order.payments.single().reference)
        assertEquals("cashier-1", success.order.payments.single().confirmedBy)

        val events = service.listEvents(order.id)
        assertEquals(listOf(OrderEventType.CREATED, OrderEventType.PAYMENT_CONFIRMED), events.map { it.type })
    }

    @Test
    fun `a partial payment leaves the order pending with a remaining balance`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        val result = service.confirmPayment(order.id, "CASH", 4.0, null, "cashier-1")

        val success = assertIs<ConfirmPaymentResult.Success>(result)
        assertEquals(OrderStatus.PENDING, success.order.status)
        assertEquals(4.0, success.order.amountPaid)
        assertEquals(6.0, success.order.remainingBalance)
        assertEquals(listOf("CASH"), success.order.payments.map { it.method })
    }

    @Test
    fun `two partial tenders summing to the total mark the order paid with both payment records`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        val first = assertIs<ConfirmPaymentResult.Success>(service.confirmPayment(order.id, "CASH", 4.0, null, "cashier-1"))
        assertEquals(OrderStatus.PENDING, first.order.status)

        val second = assertIs<ConfirmPaymentResult.Success>(
            service.confirmPayment(order.id, "GIFT_CARD", 6.0, "gift-ref-1", "cashier-1")
        )

        assertEquals(OrderStatus.PAID, second.order.status)
        assertEquals(0.0, second.order.remainingBalance)
        assertEquals(listOf("CASH", "GIFT_CARD"), second.order.payments.map { it.method })
        assertEquals(listOf(4.0, 6.0), second.order.payments.map { it.amount })
    }

    @Test
    fun `confirming an amount greater than the remaining balance is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CASH", 4.0, null, "cashier-1")

        val result = service.confirmPayment(order.id, "CARD", 7.0, null, "cashier-1")

        assertIs<ConfirmPaymentResult.InvalidAmount>(result)
        // The rejected attempt must not have been recorded as a tender.
        assertEquals(listOf("CASH"), service.getOrder(order.id)?.payments?.map { it.method })
    }

    @Test
    fun `confirming a card payment stores its masked card number on the payment record`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")

        val result = service.confirmPayment(
            order.id, "TERMINAL", 10.0, "term_abc123", "cashier-1", maskedCardNumber = "•••• •••• •••• 4242"
        )

        val success = assertIs<ConfirmPaymentResult.Success>(result)
        assertEquals("•••• •••• •••• 4242", success.order.payments.single().maskedCardNumber)
    }

    @Test
    fun `confirming payment after the order is fully paid via split tenders is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CASH", 4.0, null, "cashier-1")
        service.confirmPayment(order.id, "GIFT_CARD", 6.0, null, "cashier-1")

        val result = service.confirmPayment(order.id, "CARD", 1.0, null, "cashier-1")

        assertEquals(ConfirmPaymentResult.NotPending, result)
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
    fun `refunding a paid order in full transitions to refunded and records the refund`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")
        val itemId = order.items.single().id

        val result = service.refund(
            order.id, "refund-1", "MANUAL",
            listOf(RefundLineItemInput(itemId, quantity = 1)),
            reason = "Customer changed their mind", actorId = "manager-1"
        )

        val success = assertIs<RefundResult.Success>(result)
        assertEquals(false, success.replayed)
        assertEquals(OrderStatus.REFUNDED, success.order.status)
        assertEquals(10.0, success.order.amountRefunded)
        assertEquals(0.0, success.order.remainingRefundable)
        val refund = success.order.refunds.single()
        assertEquals("refund-1", refund.refundId)
        assertEquals("MANUAL", refund.method)
        assertEquals(10.0, refund.amount)
        assertEquals("Customer changed their mind", refund.reason)
        assertEquals("manager-1", refund.refundedBy)
        assertEquals(listOf(itemId), refund.lineItems.map { it.cartItemId })

        val events = service.listEvents(order.id)
        assertEquals(
            listOf(OrderEventType.CREATED, OrderEventType.PAYMENT_CONFIRMED, OrderEventType.REFUNDED),
            events.map { it.type }
        )
    }

    @Test
    fun `partially refunding one unit of a two-unit line leaves the order partially refunded with prorated tax`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(quantity = 2), seedTaxedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 22.0, null, "cashier-1")
        val itemId = order.items.single().id

        val preview = service.previewRefund(order.id, listOf(RefundLineItemInput(itemId, quantity = 1)))
        val previewSuccess = assertIs<RefundPreviewResult.Success>(preview)
        assertEquals(11.0, previewSuccess.amount)
        // previewRefund must not mutate the order.
        assertEquals(OrderStatus.PAID, service.getOrder(order.id)?.status)

        val result = service.refund(
            order.id, "refund-1", "CARD", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1"
        )

        val success = assertIs<RefundResult.Success>(result)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, success.order.status)
        assertEquals(11.0, success.order.amountRefunded)
        assertEquals(11.0, success.order.remainingRefundable)
        assertEquals(11.0, success.order.refunds.single().amount)

        // Refunding the remaining unit completes the refund.
        val second = assertIs<RefundResult.Success>(
            service.refund(order.id, "refund-2", "CARD", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")
        )
        assertEquals(OrderStatus.REFUNDED, second.order.status)
        assertEquals(22.0, second.order.amountRefunded)
        assertEquals(0.0, second.order.remainingRefundable)
    }

    @Test
    fun `refunding more units than remain on a line is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(quantity = 2), seedTaxedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 22.0, null, "cashier-1")
        val itemId = order.items.single().id
        service.refund(order.id, "refund-1", "CARD", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")

        val result = service.refund(order.id, "refund-2", "CARD", listOf(RefundLineItemInput(itemId, quantity = 2)), null, "manager-1")

        assertIs<RefundResult.InvalidLineItem>(result)
    }

    @Test
    fun `refunding an unknown line item is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")

        val result = service.refund(order.id, "refund-1", "MANUAL", listOf(RefundLineItemInput("no-such-item", quantity = 1)), null, "manager-1")

        assertIs<RefundResult.InvalidLineItem>(result)
    }

    @Test
    fun `refunding a pending (unpaid) order is rejected`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        val itemId = order.items.single().id

        val result = service.refund(order.id, "refund-1", "MANUAL", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")

        assertEquals(RefundResult.NotRefundable, result)
    }

    @Test
    fun `refunding after the refund window has expired is rejected`() {
        val now = Instant.parse("2026-04-01T00:00:00Z")
        val service = OrderService(nowProvider = { now })
        val order = service.createOrder(seedCart(), seedTotals(), "key-1", checkedOutAt = now.minus(91, ChronoUnit.DAYS))
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")
        val itemId = order.items.single().id

        val result = service.refund(order.id, "refund-1", "MANUAL", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")

        assertEquals(RefundResult.RefundWindowExpired, result)
    }

    @Test
    fun `refunding within the refund window succeeds`() {
        val now = Instant.parse("2026-04-01T00:00:00Z")
        val service = OrderService(nowProvider = { now })
        val order = service.createOrder(seedCart(), seedTotals(), "key-1", checkedOutAt = now.minus(89, ChronoUnit.DAYS))
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")
        val itemId = order.items.single().id

        val result = service.refund(order.id, "refund-1", "MANUAL", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")

        assertIs<RefundResult.Success>(result)
    }

    @Test
    fun `refunding twice with the same refundId replays the original result instead of refunding twice`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")
        val itemId = order.items.single().id
        val lineItems = listOf(RefundLineItemInput(itemId, quantity = 1))

        val first = assertIs<RefundResult.Success>(service.refund(order.id, "refund-1", "MANUAL", lineItems, null, "manager-1"))
        val second = assertIs<RefundResult.Success>(service.refund(order.id, "refund-1", "MANUAL", lineItems, null, "manager-1"))

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
    fun `refunding an already fully-refunded order with a different refundId is rejected as not refundable`() {
        val service = OrderService()
        val order = service.createOrder(seedCart(), seedTotals(), "key-1")
        service.confirmPayment(order.id, "CARD", 10.0, null, "cashier-1")
        val itemId = order.items.single().id
        service.refund(order.id, "refund-1", "MANUAL", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")

        val result = service.refund(order.id, "refund-2", "MANUAL", listOf(RefundLineItemInput(itemId, quantity = 1)), null, "manager-1")

        assertEquals(RefundResult.NotRefundable, result)
    }

    @Test
    fun `refunding an unknown order is rejected`() {
        val service = OrderService()
        val result = service.refund("does-not-exist", "refund-1", "MANUAL", listOf(RefundLineItemInput("item-1", quantity = 1)), null, null)
        assertEquals(RefundResult.OrderNotFound, result)
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
