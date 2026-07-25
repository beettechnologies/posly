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
    data class Success(val order: Order, val replayed: Boolean) : RefundResult()
    data object OrderNotFound : RefundResult()
    data object NotPaid : RefundResult()
    /** The order was already refunded under a DIFFERENT refundId - not a retry, a genuinely conflicting second refund. */
    data object AlreadyRefunded : RefundResult()
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

    fun createOrder(cart: Cart, totals: CartTotals, idempotencyKey: String, checkedOutAt: Instant = nowProvider()): Order {
        val order = Order(
            cartId = cart.id,
            storeId = cart.storeId,
            createdBy = cart.createdBy,
            items = cart.items,
            discount = cart.discount,
            totals = totals,
            idempotencyKey = idempotencyKey,
            checkedOutAt = checkedOutAt,
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

    /**
     * Accepts one tender toward the order's total. A single call for the full remaining balance
     * behaves exactly as before (PENDING -> PAID). A lesser amount is a partial/split tender: the
     * order stays PENDING with the payment appended, and further calls are accepted until the
     * accumulated total covers the order - at which point it flips to PAID. An amount greater than
     * what's still owed is rejected outright rather than silently overpaying.
     */
    fun confirmPayment(
        orderId: String,
        method: String,
        amount: Double,
        reference: String?,
        actorId: String?,
        maskedCardNumber: String? = null
    ): ConfirmPaymentResult {
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
                roundCents(amount) > existing.remainingBalance -> {
                    outcome = ConfirmPaymentResult.InvalidAmount(
                        "amount exceeds remaining balance of ${existing.remainingBalance}"
                    )
                    existing
                }
                else -> {
                    val payment = PaymentRecord(
                        method = method,
                        amount = amount,
                        reference = reference,
                        confirmedBy = actorId,
                        confirmedAt = nowProvider(),
                        maskedCardNumber = maskedCardNumber
                    )
                    val withPayment = existing.copy(payments = existing.payments + payment)
                    val updated = if (withPayment.remainingBalance <= 0.0) {
                        withPayment.copy(status = OrderStatus.PAID)
                    } else {
                        withPayment
                    }
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

    /**
     * Idempotent by [refundId]: a retry with the SAME key against an already-refunded order
     * replays the original result instead of erroring. A different key against an already-refunded
     * order is rejected - that's not a retry, it's a second, conflicting refund attempt.
     */
    fun refund(orderId: String, refundId: String, reason: String?, actorId: String?): RefundResult {
        var outcome: RefundResult = RefundResult.OrderNotFound
        orders.compute(orderId) { _, existing ->
            when {
                existing == null -> {
                    outcome = RefundResult.OrderNotFound
                    null
                }
                existing.status == OrderStatus.REFUNDED -> {
                    outcome = if (existing.refund?.refundId == refundId) {
                        RefundResult.Success(existing, replayed = true)
                    } else {
                        RefundResult.AlreadyRefunded
                    }
                    existing
                }
                existing.status != OrderStatus.PAID -> {
                    outcome = RefundResult.NotPaid
                    existing
                }
                else -> {
                    val refund = RefundRecord(
                        refundId = refundId,
                        amount = existing.totals.total,
                        reason = reason,
                        refundedBy = actorId,
                        refundedAt = nowProvider()
                    )
                    val updated = existing.copy(status = OrderStatus.REFUNDED, refund = refund)
                    outcome = RefundResult.Success(updated, replayed = false)
                    updated
                }
            }
        }
        val success = outcome as? RefundResult.Success
        if (success != null && !success.replayed) {
            recordEvent(orderId, OrderEventType.REFUNDED, actorId, "amount=${success.order.refund?.amount}")
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
