package com.beettechnologies.posly.sync

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartService
import com.beettechnologies.posly.cart.CartStatus
import com.beettechnologies.posly.cart.ConfirmPaymentResult
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.SelectedModifier
import com.beettechnologies.posly.cart.roundCents
import com.beettechnologies.posly.devices.DeviceAuthResult
import com.beettechnologies.posly.devices.DeviceRecord
import com.beettechnologies.posly.devices.DeviceRegistryService
import com.beettechnologies.posly.products.Product
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.products.TaxCategory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class IngestBatchResult {
    data class Success(val results: List<OfflineSaleOutcomeResult>) : IngestBatchResult()
    data object InvalidCredentials : IngestBatchResult()
    data object DeviceDeprovisioned : IngestBatchResult()
}

data class OfflineSaleOutcomeResult(val record: OfflineSaleRecord, val replayed: Boolean)

private data class ResolvedItem(val item: OfflineSaleItemInput, val product: Product?)

/**
 * Ingests batches of sales rung up while a device was offline. Each sale is keyed by a
 * client-generated [OfflineSaleInput.idempotencyKey]: a resubmission of the same key (whether a
 * genuine retry after a dropped response, or the same batch replayed on the next reconnect)
 * replays the original outcome rather than reprocessing or double-creating an order - the whole
 * point of an idempotency token.
 *
 * A sale's items are checked against the CURRENT product catalog by SKU. If nothing has changed,
 * the sale is persisted as-is (CREATED). If something has (price, tax category, or the SKU no
 * longer resolving to any product at all), the batch's [ConflictPolicy] decides what happens:
 *  - REJECT: nothing is persisted; the conflict is recorded for admin review only.
 *  - MAP: the sale is persisted, re-priced using today's catalog values.
 *  - CONVERT: the sale is persisted using exactly what the customer was actually charged offline.
 * A SKU that no longer resolves to any product can't be MAP'd or CONVERT'd (there is nothing to
 * price from), so that specific conflict is always rejected regardless of policy - as is a sale
 * whose captured tenders don't cover its recomputed total.
 */
class OfflineSyncService(
    private val deviceRegistryService: DeviceRegistryService,
    private val productService: ProductService,
    private val cartService: CartService,
    private val orderService: OrderService,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val ledger = ConcurrentHashMap<String, OfflineSaleRecord>()
    private val ingestLock = Any()

    fun ingestBatch(
        clientId: String,
        clientSecret: String,
        conflictPolicy: ConflictPolicy,
        sales: List<OfflineSaleInput>,
        correlationId: String? = null
    ): IngestBatchResult {
        val device = when (val auth = deviceRegistryService.authenticateDevice(clientId, clientSecret)) {
            is DeviceAuthResult.Success -> auth.device
            DeviceAuthResult.InvalidCredentials -> return IngestBatchResult.InvalidCredentials
            DeviceAuthResult.Deprovisioned -> return IngestBatchResult.DeviceDeprovisioned
        }
        return IngestBatchResult.Success(sales.map { sale -> ingestSale(device, conflictPolicy, sale, correlationId) })
    }

    fun listConflicts(): List<OfflineSaleRecord> =
        ledger.values.filter { it.outcome != OfflineSaleOutcome.CREATED }.sortedBy { it.processedAt }

    private fun ingestSale(device: DeviceRecord, conflictPolicy: ConflictPolicy, sale: OfflineSaleInput, correlationId: String?): OfflineSaleOutcomeResult {
        synchronized(ingestLock) {
            ledger[sale.idempotencyKey]?.let { return OfflineSaleOutcomeResult(it, replayed = true) }
            val record = processNewSale(device, conflictPolicy, sale, correlationId)
            ledger[sale.idempotencyKey] = record
            return OfflineSaleOutcomeResult(record, replayed = false)
        }
    }

    private fun processNewSale(device: DeviceRecord, conflictPolicy: ConflictPolicy, sale: OfflineSaleInput, correlationId: String?): OfflineSaleRecord {
        val now = nowProvider()

        fun rejected(conflicts: List<ItemConflict>): OfflineSaleRecord {
            if (conflicts.isNotEmpty()) auditConflict(sale.idempotencyKey, device.id, conflicts)
            return OfflineSaleRecord(
                idempotencyKey = sale.idempotencyKey,
                deviceId = device.id,
                storeId = device.storeId,
                outcome = OfflineSaleOutcome.CONFLICT_REJECTED,
                orderId = null,
                conflicts = conflicts,
                processedAt = now
            )
        }

        val structurallyInvalid = sale.idempotencyKey.isBlank() ||
            sale.items.isEmpty() ||
            sale.items.any { it.quantity <= 0 || it.sku.isBlank() } ||
            sale.items.any { runCatching { TaxCategory.valueOf(it.taxCategoryAtSale) }.isFailure }
        if (structurallyInvalid) {
            return rejected(listOf(ItemConflict(sku = null, reason = ConflictReason.INVALID_SALE, capturedValue = null, currentValue = null)))
        }

        val conflicts = mutableListOf<ItemConflict>()
        val resolved = sale.items.map { item ->
            val product = productService.getProductBySku(item.sku)
            if (product == null) {
                conflicts += ItemConflict(item.sku, ConflictReason.PRODUCT_NOT_FOUND, item.unitPriceAtSale.toString(), null)
            } else {
                if (roundCents(product.price) != roundCents(item.unitPriceAtSale)) {
                    conflicts += ItemConflict(item.sku, ConflictReason.PRICE_CHANGED, item.unitPriceAtSale.toString(), product.price.toString())
                }
                if (product.taxCategory.name != item.taxCategoryAtSale) {
                    conflicts += ItemConflict(item.sku, ConflictReason.TAX_CATEGORY_CHANGED, item.taxCategoryAtSale, product.taxCategory.name)
                }
            }
            ResolvedItem(item, product)
        }

        if (conflicts.any { it.reason == ConflictReason.PRODUCT_NOT_FOUND }) return rejected(conflicts)

        val hasResolvableConflict = conflicts.isNotEmpty()
        if (hasResolvableConflict && conflictPolicy == ConflictPolicy.REJECT) return rejected(conflicts)

        val useCurrentCatalogValues = !hasResolvableConflict || conflictPolicy == ConflictPolicy.MAP
        val cartItems = resolved.map { (item, product) ->
            val p = requireNotNull(product) { "product null after PRODUCT_NOT_FOUND was excluded above" }
            CartItem(
                productId = p.id,
                productName = if (useCurrentCatalogValues) p.name else item.productName,
                quantity = item.quantity,
                unitPrice = if (useCurrentCatalogValues) p.price else item.unitPriceAtSale,
                taxCategory = if (useCurrentCatalogValues) p.taxCategory else TaxCategory.valueOf(item.taxCategoryAtSale),
                selectedModifiers = item.selectedModifiers.map { SelectedModifier(it.modifierId, it.option, it.additionalCost) },
                discount = item.discount
            )
        }

        val cart = Cart(
            id = UUID.randomUUID().toString(),
            storeId = device.storeId,
            createdBy = sale.soldBy,
            items = cartItems,
            discount = sale.discount,
            status = CartStatus.CHECKED_OUT,
            checkoutIdempotencyKey = sale.idempotencyKey,
            createdAt = sale.soldAt,
            updatedAt = sale.soldAt
        )
        val totals = cartService.getTotals(cart)

        val paymentsTotal = roundCents(sale.payments.sumOf { it.amount })
        if (paymentsTotal > totals.total) {
            conflicts += ItemConflict(
                sku = null,
                reason = ConflictReason.PAYMENT_MISMATCH,
                capturedValue = paymentsTotal.toString(),
                currentValue = totals.total.toString()
            )
            return rejected(conflicts)
        }

        val order = orderService.createOrder(cart, totals, sale.idempotencyKey, checkedOutAt = sale.soldAt)
        for (payment in sale.payments) {
            val result = orderService.confirmPayment(
                orderId = order.id,
                method = payment.method,
                amount = payment.amount,
                reference = payment.reference,
                actorId = sale.soldBy
            )
            if (result !is ConfirmPaymentResult.Success) {
                orderService.deleteOrder(order.id)
                conflicts += ItemConflict(
                    sku = null,
                    reason = ConflictReason.PAYMENT_MISMATCH,
                    capturedValue = payment.amount.toString(),
                    currentValue = null
                )
                return rejected(conflicts)
            }
        }

        val outcome = when {
            !hasResolvableConflict -> OfflineSaleOutcome.CREATED
            conflictPolicy == ConflictPolicy.MAP -> OfflineSaleOutcome.CONFLICT_RESOLVED_MAP
            else -> OfflineSaleOutcome.CONFLICT_RESOLVED_CONVERT
        }
        if (conflicts.isNotEmpty()) auditConflict(sale.idempotencyKey, device.id, conflicts)
        AuditService.record(
            AuditEvent.ORDER_CREATED,
            deviceId = device.id,
            correlationId = correlationId,
            detail = "orderId=${order.id} source=offline_sync outcome=$outcome"
        )
        return OfflineSaleRecord(
            idempotencyKey = sale.idempotencyKey,
            deviceId = device.id,
            storeId = device.storeId,
            outcome = outcome,
            orderId = order.id,
            conflicts = conflicts.toList(),
            processedAt = now
        )
    }

    private fun auditConflict(idempotencyKey: String, deviceId: String, conflicts: List<ItemConflict>) {
        AuditService.record(
            AuditEvent.OFFLINE_SALE_CONFLICT,
            detail = "idempotencyKey=$idempotencyKey deviceId=$deviceId reasons=${conflicts.joinToString(",") { it.reason.name }}"
        )
    }
}
