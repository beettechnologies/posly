package com.beettechnologies.posly.cart

import com.beettechnologies.posly.db.OrderEventsTable
import com.beettechnologies.posly.db.OrdersTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit

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

/**
 * Notified of order lifecycle milestones ([OrderEventType.CREATED] and, once an order becomes
 * fully paid, [OrderEventType.PAYMENT_CONFIRMED]) - the single reliable hook for integrations
 * (e.g. outbound webhooks) regardless of which caller triggered the change (checkout, offline
 * sync, a terminal webhook, or a manual tender). Deliberately generic rather than
 * webhook-specific, so this file stays unaware that webhooks exist.
 */
fun interface OrderEventListener {
    fun onEvent(order: Order, type: OrderEventType)
}

/**
 * Dispatches every event to a mutable, appendable list of listeners. Lets a listener that itself
 * depends on [OrderService] (e.g. a reporting pipeline needing to query orders) register *after*
 * [OrderService] is constructed, instead of forcing a circular construction order - [register] is
 * called post-construction, well before any real event can fire.
 */
class CompositeOrderEventListener : OrderEventListener {
    private val listeners = mutableListOf<OrderEventListener>()

    fun register(listener: OrderEventListener) {
        listeners += listener
    }

    override fun onEvent(order: Order, type: OrderEventType) {
        listeners.forEach { it.onEvent(order, type) }
    }
}

private sealed class RefundValidation {
    data class Valid(val lineItems: List<RefundLineItem>, val amount: Double) : RefundValidation()
    data object OrderNotFound : RefundValidation()
    data object NotRefundable : RefundValidation()
    data object RefundWindowExpired : RefundValidation()
    data class InvalidLineItem(val message: String) : RefundValidation()
}

private fun rowToOrder(row: ResultRow) = Order(
    id = row[OrdersTable.id],
    cartId = row[OrdersTable.cartId],
    storeId = row[OrdersTable.storeId],
    createdBy = row[OrdersTable.createdBy],
    items = row[OrdersTable.items],
    discount = row[OrdersTable.discount],
    totals = row[OrdersTable.totals],
    idempotencyKey = row[OrdersTable.idempotencyKey],
    checkedOutAt = row[OrdersTable.checkedOutAt],
    currency = row[OrdersTable.currency],
    status = OrderStatus.valueOf(row[OrdersTable.status]),
    payments = row[OrdersTable.payments],
    refunds = row[OrdersTable.refunds]
)

private fun rowToOrderEvent(row: ResultRow) = OrderEvent(
    timestamp = row[OrderEventsTable.timestamp],
    orderId = row[OrderEventsTable.orderId],
    type = OrderEventType.valueOf(row[OrderEventsTable.type]),
    actorId = row[OrderEventsTable.actorId],
    detail = row[OrderEventsTable.detail]
)

/**
 * Owns the Order aggregate and its PENDING -> PAID -> (PARTIALLY_REFUNDED ->) REFUNDED state
 * machine, plus an append-only audit trail of every transition. Orders are created by
 * CartService.checkout (always starting PENDING); everything after that - confirming payment,
 * refunding - happens here via atomic per-order transitions.
 */
class OrderService(
    private val nowProvider: () -> Instant = { Instant.now() },
    private val refundWindowDays: Long = 90,
    private val eventListener: OrderEventListener? = null
) {

    fun createOrder(
        cart: Cart,
        totals: CartTotals,
        idempotencyKey: String,
        checkedOutAt: Instant = nowProvider(),
        currency: String = "USD"
    ): Order {
        val order = Order(
            cartId = cart.id,
            storeId = cart.storeId,
            createdBy = cart.createdBy,
            items = cart.items,
            discount = cart.discount,
            totals = totals,
            idempotencyKey = idempotencyKey,
            checkedOutAt = checkedOutAt,
            currency = currency,
            status = OrderStatus.PENDING
        )
        transaction {
            persistNewOrder(order)
            recordEvent(order.id, OrderEventType.CREATED, cart.createdBy, "status=PENDING")
        }
        eventListener?.onEvent(order, OrderEventType.CREATED)
        return order
    }

    /** Reverts a just-created order that never made it into a consistently committed cart. */
    fun deleteOrder(id: String) {
        transaction {
            OrderEventsTable.deleteWhere { OrderEventsTable.orderId eq id }
            OrdersTable.deleteWhere { OrdersTable.id eq id }
        }
    }

    fun getOrder(id: String): Order? = transaction {
        OrdersTable.selectAll().where { OrdersTable.id eq id }.singleOrNull()?.let { rowToOrder(it) }
    }

    /** Looks an order up by its checkout idempotency key - lets a caller (e.g. a migration import) detect a re-run without keeping its own ledger. */
    fun getOrderByIdempotencyKey(key: String): Order? = transaction {
        OrdersTable.selectAll().where { OrdersTable.idempotencyKey eq key }.singleOrNull()?.let { rowToOrder(it) }
    }

    fun count(): Int = transaction {
        OrdersTable.selectAll().count().toInt()
    }

    /** Orders checked out in [from, to) for [storeId] - e.g. for a shift's cash reconciliation window. */
    fun listOrders(storeId: String, from: Instant, to: Instant): List<Order> = transaction {
        OrdersTable.selectAll()
            .where { (OrdersTable.storeId eq storeId) and (OrdersTable.checkedOutAt greaterEq from) and (OrdersTable.checkedOutAt less to) }
            .map { rowToOrder(it) }
    }

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

        val outcome = transaction {
            // Locks the order row for the duration of this transaction - the same atomicity
            // ConcurrentHashMap.compute() used to give, now enforced by the database instead.
            val existing = OrdersTable.selectAll().where { OrdersTable.id eq orderId }
                .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()?.let { rowToOrder(it) }

            val result: ConfirmPaymentResult = when {
                existing == null -> ConfirmPaymentResult.OrderNotFound
                existing.status != OrderStatus.PENDING -> ConfirmPaymentResult.NotPending
                roundCents(amount) > existing.remainingBalance -> ConfirmPaymentResult.InvalidAmount(
                    "amount exceeds remaining balance of ${existing.remainingBalance}"
                )
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
                    persistOrderUpdate(updated)
                    ConfirmPaymentResult.Success(updated)
                }
            }
            if (result is ConfirmPaymentResult.Success) {
                recordEvent(orderId, OrderEventType.PAYMENT_CONFIRMED, actorId, "method=$method amount=$amount")
            }
            result
        }

        val success = outcome as? ConfirmPaymentResult.Success
        // Only a fully-settled order counts as "payment succeeded" for integrations - a partial/
        // split tender is still an in-progress sale, not a completed one.
        if (success != null && success.order.status == OrderStatus.PAID) {
            eventListener?.onEvent(success.order, OrderEventType.PAYMENT_CONFIRMED)
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
        when (val validation = validateRefund(getOrder(orderId), lineItems)) {
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
        val outcome = transaction {
            val existing = OrdersTable.selectAll().where { OrdersTable.id eq orderId }
                .forUpdate(ForUpdateOption.ForUpdate).singleOrNull()?.let { rowToOrder(it) }

            val result: RefundResult = when {
                existing == null -> RefundResult.OrderNotFound
                existing.refunds.any { it.refundId == refundId } -> RefundResult.Success(existing, replayed = true)
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
                        persistOrderUpdate(updated)
                        RefundResult.Success(updated, replayed = false)
                    }
                    RefundValidation.OrderNotFound -> RefundResult.OrderNotFound
                    RefundValidation.NotRefundable -> RefundResult.NotRefundable
                    RefundValidation.RefundWindowExpired -> RefundResult.RefundWindowExpired
                    is RefundValidation.InvalidLineItem -> RefundResult.InvalidLineItem(validation.message)
                }
            }
            val success = result as? RefundResult.Success
            if (success != null && !success.replayed) {
                val justRefunded = success.order.refunds.last()
                recordEvent(orderId, OrderEventType.REFUNDED, actorId, "method=$method amount=${justRefunded.amount}")
            }
            result
        }
        return outcome
    }

    fun listEvents(orderId: String): List<OrderEvent> = transaction {
        OrderEventsTable.selectAll().where { OrderEventsTable.orderId eq orderId }
            .orderBy(OrderEventsTable.id to SortOrder.ASC)
            .map { rowToOrderEvent(it) }
    }

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

    private fun persistNewOrder(order: Order) {
        OrdersTable.insert {
            it[id] = order.id
            it[cartId] = order.cartId
            it[storeId] = order.storeId
            it[createdBy] = order.createdBy
            it[items] = order.items
            it[discount] = order.discount
            it[totals] = order.totals
            it[idempotencyKey] = order.idempotencyKey
            it[checkedOutAt] = order.checkedOutAt
            it[currency] = order.currency
            it[status] = order.status.name
            it[payments] = order.payments
            it[refunds] = order.refunds
        }
    }

    private fun persistOrderUpdate(order: Order) {
        OrdersTable.update({ OrdersTable.id eq order.id }) {
            it[status] = order.status.name
            it[payments] = order.payments
            it[refunds] = order.refunds
        }
    }

    private fun recordEvent(orderId: String, type: OrderEventType, actorId: String?, detail: String?) {
        OrderEventsTable.insert {
            it[OrderEventsTable.orderId] = orderId
            it[timestamp] = nowProvider()
            it[OrderEventsTable.type] = type.name
            it[OrderEventsTable.actorId] = actorId
            it[OrderEventsTable.detail] = detail
        }
    }
}
