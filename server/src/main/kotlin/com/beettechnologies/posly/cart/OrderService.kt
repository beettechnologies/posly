package com.beettechnologies.posly.cart

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed class ConfirmPaymentResult {
    data class Success(val order: Order) : ConfirmPaymentResult()
    data object OrderNotFound : ConfirmPaymentResult()
    data object NotPending : ConfirmPaymentResult()
    data class InvalidAmount(val message: String) : ConfirmPaymentResult()
}

sealed class RefundResult {
    data class Success(val order: Order) : RefundResult()
    data object OrderNotFound : RefundResult()
    data object NotPaid : RefundResult()
}

/**
 * Owns the Order aggregate and its PENDING -> PAID -> REFUNDED state machine, plus an
 * append-only audit trail of every transition. Orders are created by CartService.checkout
 * (always starting PENDING); everything after that - confirming payment, refunding - happens
 * here via atomic per-order transitions.
 */
class OrderService(private val nowProvider: () -> Instant = { Instant.now() }) {

    private val orders = ConcurrentHashMap<String, Order>()
    private val events = mutableListOf<OrderEvent>()

    fun createOrder(cart: Cart, totals: CartTotals, idempotencyKey: String): Order {
        val order = Order(
            cartId = cart.id,
            storeId = cart.storeId,
            createdBy = cart.createdBy,
            items = cart.items,
            discount = cart.discount,
            totals = totals,
            idempotencyKey = idempotencyKey,
            checkedOutAt = nowProvider(),
            status = OrderStatus.PENDING
        )
        orders[order.id] = order
        recordEvent(order.id, OrderEventType.CREATED, cart.createdBy, "status=PENDING")
        return order
    }

    /** Reverts a just-created order that never made it into a consistently committed cart. */
    fun deleteOrder(id: String) {
        orders.remove(id)
        synchronized(events) { events.removeAll { it.orderId == id } }
    }

    fun getOrder(id: String): Order? = orders[id]

    fun count(): Int = orders.size

    fun confirmPayment(orderId: String, method: String, amount: Double, reference: String?, actorId: String?): ConfirmPaymentResult {
        if (amount <= 0) return ConfirmPaymentResult.InvalidAmount("amount must be positive")

        var outcome: ConfirmPaymentResult = ConfirmPaymentResult.OrderNotFound
        orders.compute(orderId) { _, existing ->
            when {
                existing == null -> {
                    outcome = ConfirmPaymentResult.OrderNotFound
                    null
                }
                existing.status != OrderStatus.PENDING -> {
                    outcome = ConfirmPaymentResult.NotPending
                    existing
                }
                else -> {
                    val payment = PaymentRecord(
                        method = method,
                        amount = amount,
                        reference = reference,
                        confirmedBy = actorId,
                        confirmedAt = nowProvider()
                    )
                    val updated = existing.copy(status = OrderStatus.PAID, payment = payment)
                    outcome = ConfirmPaymentResult.Success(updated)
                    updated
                }
            }
        }
        if (outcome is ConfirmPaymentResult.Success) {
            recordEvent(orderId, OrderEventType.PAYMENT_CONFIRMED, actorId, "method=$method amount=$amount")
        }
        return outcome
    }

    fun refund(orderId: String, reason: String?, actorId: String?): RefundResult {
        var outcome: RefundResult = RefundResult.OrderNotFound
        orders.compute(orderId) { _, existing ->
            when {
                existing == null -> {
                    outcome = RefundResult.OrderNotFound
                    null
                }
                existing.status != OrderStatus.PAID -> {
                    outcome = RefundResult.NotPaid
                    existing
                }
                else -> {
                    val refund = RefundRecord(
                        amount = existing.totals.total,
                        reason = reason,
                        refundedBy = actorId,
                        refundedAt = nowProvider()
                    )
                    val updated = existing.copy(status = OrderStatus.REFUNDED, refund = refund)
                    outcome = RefundResult.Success(updated)
                    updated
                }
            }
        }
        if (outcome is RefundResult.Success) {
            val amount = (outcome as RefundResult.Success).order.refund?.amount
            recordEvent(orderId, OrderEventType.REFUNDED, actorId, "amount=$amount")
        }
        return outcome
    }

    fun listEvents(orderId: String): List<OrderEvent> = synchronized(events) { events.filter { it.orderId == orderId } }

    private fun recordEvent(orderId: String, type: OrderEventType, actorId: String?, detail: String?) {
        synchronized(events) {
            events += OrderEvent(nowProvider(), orderId, type, actorId, detail)
        }
    }
}
