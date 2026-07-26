package com.beettechnologies.posly.cart

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

sealed class ConfirmPaymentResult {
    data class Success(val order: Order) : ConfirmPaymentResult()
    data object OrderNotFound : ConfirmPaymentResult()
    data object NotPending : ConfirmPaymentResult()
    data class InvalidAmount(val message: String) : ConfirmPaymentResult()
}

/** One requested refund line - [restock] decides whether these units go back into inventory. */
data class RefundLineItemInput(val cartItemId: String, val quantity: Int, val restock: Boolean = false)

sealed class RefundPreviewResult {
    data class Success(val amount: Double, val lineItems: List<RefundLineItem>) : RefundPreviewResult()
    data object OrderNotFound : RefundPreviewResult()
    data object NotRefundable : RefundPreviewResult()
    data object RefundWindowExpired : RefundPreviewResult()
    data class InvalidLineItem(val message: String) : RefundPreviewResult()
}

sealed class RefundResult {
    data class Success(val order: Order, val replayed: Boolean) : RefundResult()
    data object OrderNotFound : RefundResult()
    /** Order was never paid, or is already refunded in full - nothing left to refund. */
    data object NotRefundable : RefundResult()
    data object RefundWindowExpired : RefundResult()
    data class InvalidLineItem(val message: String) : RefundResult()
}

private sealed class RefundValidation {
    data class Valid(val lineItems: List<RefundLineItem>, val amount: Double) : RefundValidation()
    data object OrderNotFound : RefundValidation()
    data object NotRefundable : RefundValidation()
    data object RefundWindowExpired : RefundValidation()
    data class InvalidLineItem(val message: String) : RefundValidation()
}

/**
 * Owns the Order aggregate and its PENDING -> PAID -> (PARTIALLY_REFUNDED ->) REFUNDED state
 * machine, plus an append-only audit trail of every transition. Orders are created by
 * CartService.checkout (always starting PENDING); everything after that - confirming payment,
 * refunding - happens here via atomic per-order transitions.
 */
class OrderService(
    private val nowProvider: () -> Instant = { Instant.now() },
    private val refundWindowDays: Long = 90
) {

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

    /** Orders checked out in [from, to) for [storeId] - e.g. for a shift's cash reconciliation window. */
    fun listOrders(storeId: String, from: Instant, to: Instant): List<Order> =
        orders.values.filter { it.storeId == storeId && !it.checkedOutAt.isBefore(from) && it.checkedOutAt.isBefore(to) }

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
     * Validates [lineItems] against [orderId] and computes what refunding them would cost -
     * including each refunded line's fair share of order tax, allocated proportionally to its
     * share of the taxable base, the same way a cart-level discount is already prorated in
     * [computeTotals] - without mutating anything. Used both to show a cashier a total before they
     * submit, and internally by the payment gateway to know the amount before ever calling out to
     * it.
     */
    fun previewRefund(orderId: String, lineItems: List<RefundLineItemInput>): RefundPreviewResult =
        when (val validation = validateRefund(orders[orderId], lineItems)) {
            is RefundValidation.Valid -> RefundPreviewResult.Success(validation.amount, validation.lineItems)
            RefundValidation.OrderNotFound -> RefundPreviewResult.OrderNotFound
            RefundValidation.NotRefundable -> RefundPreviewResult.NotRefundable
            RefundValidation.RefundWindowExpired -> RefundPreviewResult.RefundWindowExpired
            is RefundValidation.InvalidLineItem -> RefundPreviewResult.InvalidLineItem(validation.message)
        }

    /**
     * Idempotent by [refundId]: a retry with the SAME key replays the original result instead of
     * re-validating or double-refunding. [method] is "CARD" or "MANUAL" purely for the record - by
     * the time this is called for a card refund, the gateway call has already succeeded; a manual
     * refund is a cashier/manager asserting they handled it outside the system. Restocking (if any
     * [RefundLineItemInput.restock] is set) is the caller's responsibility once this succeeds -
     * kept out of here to keep this service free of an inventory dependency.
     */
    fun refund(
        orderId: String,
        refundId: String,
        method: String,
        lineItems: List<RefundLineItemInput>,
        reason: String?,
        actorId: String?
    ): RefundResult {
        var outcome: RefundResult = RefundResult.OrderNotFound
        orders.compute(orderId) { _, existing ->
            when {
                existing == null -> {
                    outcome = RefundResult.OrderNotFound
                    null
                }
                existing.refunds.any { it.refundId == refundId } -> {
                    outcome = RefundResult.Success(existing, replayed = true)
                    existing
                }
                else -> when (val validation = validateRefund(existing, lineItems)) {
                    is RefundValidation.Valid -> {
                        val refund = RefundRecord(
                            refundId = refundId,
                            method = method,
                            lineItems = validation.lineItems,
                            amount = validation.amount,
                            reason = reason,
                            refundedBy = actorId,
                            refundedAt = nowProvider()
                        )
                        val withRefund = existing.copy(refunds = existing.refunds + refund)
                        val updated = if (withRefund.remainingRefundable <= 0.0) {
                            withRefund.copy(status = OrderStatus.REFUNDED)
                        } else {
                            withRefund.copy(status = OrderStatus.PARTIALLY_REFUNDED)
                        }
                        outcome = RefundResult.Success(updated, replayed = false)
                        updated
                    }
                    RefundValidation.OrderNotFound -> {
                        outcome = RefundResult.OrderNotFound
                        existing
                    }
                    RefundValidation.NotRefundable -> {
                        outcome = RefundResult.NotRefundable
                        existing
                    }
                    RefundValidation.RefundWindowExpired -> {
                        outcome = RefundResult.RefundWindowExpired
                        existing
                    }
                    is RefundValidation.InvalidLineItem -> {
                        outcome = RefundResult.InvalidLineItem(validation.message)
                        existing
                    }
                }
            }
        }
        val success = outcome as? RefundResult.Success
        if (success != null && !success.replayed) {
            val justRefunded = success.order.refunds.last()
            recordEvent(orderId, OrderEventType.REFUNDED, actorId, "method=$method amount=${justRefunded.amount}")
        }
        return outcome
    }

    fun listEvents(orderId: String): List<OrderEvent> = synchronized(events) { events.filter { it.orderId == orderId } }

    private fun validateRefund(order: Order?, lineItems: List<RefundLineItemInput>): RefundValidation {
        if (order == null) return RefundValidation.OrderNotFound
        if (order.status != OrderStatus.PAID && order.status != OrderStatus.PARTIALLY_REFUNDED) {
            return RefundValidation.NotRefundable
        }
        if (order.remainingRefundable <= 0.0) return RefundValidation.NotRefundable

        val windowEnd = order.checkedOutAt.plus(refundWindowDays, ChronoUnit.DAYS)
        if (nowProvider().isAfter(windowEnd)) return RefundValidation.RefundWindowExpired

        if (lineItems.isEmpty()) return RefundValidation.InvalidLineItem("at least one line item is required")

        val resolvedLines = mutableListOf<RefundLineItem>()
        var total = 0.0
        for (input in lineItems) {
            if (input.quantity <= 0) {
                return RefundValidation.InvalidLineItem("quantity must be positive for ${input.cartItemId}")
            }
            val item = order.items.find { it.id == input.cartItemId }
                ?: return RefundValidation.InvalidLineItem("unknown cart item ${input.cartItemId}")
            val remainingQty = item.quantity - order.refundedQuantityFor(item.id)
            if (input.quantity > remainingQty) {
                return RefundValidation.InvalidLineItem(
                    "cannot refund ${input.quantity} of '${item.productName}'; only $remainingQty remaining"
                )
            }

            val perUnit = if (item.quantity > 0) item.lineTotal / item.quantity else 0.0
            val preTaxAmount = roundCents(perUnit * input.quantity)
            val isTaxable = item.taxCategory in TAXABLE_CATEGORIES
            val taxShare = if (isTaxable && order.totals.taxableAmount > 0) {
                roundCents(preTaxAmount / order.totals.taxableAmount * order.totals.totalTax)
            } else {
                0.0
            }
            val lineAmount = roundCents(preTaxAmount + taxShare)
            total = roundCents(total + lineAmount)
            resolvedLines += RefundLineItem(cartItemId = item.id, quantity = input.quantity, amount = lineAmount, restock = input.restock)
        }

        if (total > order.remainingRefundable) {
            return RefundValidation.InvalidLineItem(
                "requested refund of $total exceeds the remaining refundable balance of ${order.remainingRefundable}"
            )
        }
        return RefundValidation.Valid(resolvedLines, total)
    }

    private fun recordEvent(orderId: String, type: OrderEventType, actorId: String?, detail: String?) {
        synchronized(events) {
            events += OrderEvent(nowProvider(), orderId, type, actorId, detail)
        }
    }
}
