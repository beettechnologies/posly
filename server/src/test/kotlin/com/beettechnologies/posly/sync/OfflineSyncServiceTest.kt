package com.beettechnologies.posly.sync

import com.beettechnologies.posly.TestDatabase

import com.beettechnologies.posly.cart.CartService
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.OrderStatus
import com.beettechnologies.posly.devices.DeviceRecord
import com.beettechnologies.posly.devices.DeviceRegistryService
import com.beettechnologies.posly.devices.EnrollDeviceResult
import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import com.beettechnologies.posly.stores.TaxRate
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Harness {
    val products = ProductService()
    val taxProfiles = TaxProfileService()
    val stores = StoreService(taxProfiles)
    val orders = OrderService()
    val carts = CartService(products, stores, taxProfiles, orders)
    val devices = DeviceRegistryService()
    val sync = OfflineSyncService(devices, products, carts, orders)

    val taxProfileId = taxProfiles.createProfile(name = "Sales Tax", rates = listOf(TaxRate("Sales Tax", 0.0))).id
    val storeId = (
        stores.createStore(
            name = "Downtown",
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = taxProfileId
        ) as CreateStoreResult.Created
        ).store.id

    val device: DeviceRecord = run {
        val code = devices.createPairCode(storeId, createdBy = "admin-1")
        (devices.enrollDevice(code.code, requestedStoreId = null, name = "Terminal 1") as EnrollDeviceResult.Success).device
    }

    fun seedProduct(sku: String, price: Double, taxCategory: String = "STANDARD"): String =
        (products.createProduct(CreateProductRequest(sku = sku, name = "Widget", price = price, taxCategory = taxCategory)) as ProductResult.Created)
            .product.id

    fun item(
        sku: String,
        unitPriceAtSale: Double,
        taxCategoryAtSale: String = "STANDARD",
        quantity: Int = 1,
        productName: String = "Widget"
    ) = OfflineSaleItemInput(
        sku = sku,
        productName = productName,
        quantity = quantity,
        unitPriceAtSale = unitPriceAtSale,
        taxCategoryAtSale = taxCategoryAtSale
    )

    fun sale(
        idempotencyKey: String,
        items: List<OfflineSaleItemInput>,
        paymentAmount: Double? = null,
        soldAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        soldBy: String? = "cashier-1"
    ): OfflineSaleInput {
        val amount = paymentAmount ?: items.sumOf { it.unitPriceAtSale * it.quantity }
        return OfflineSaleInput(
            idempotencyKey = idempotencyKey,
            items = items,
            payments = listOf(OfflineSalePaymentInput(method = "CASH", amount = amount)),
            soldAt = soldAt,
            soldBy = soldBy
        )
    }

    fun ingestOne(sale: OfflineSaleInput, policy: ConflictPolicy = ConflictPolicy.REJECT): OfflineSaleOutcomeResult =
        (sync.ingestBatch(device.clientId, device.clientSecret, policy, listOf(sale)) as IngestBatchResult.Success)
            .results.single()
}

class OfflineSyncServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
    }

    @Test
    fun `a sale whose item matches the current catalog is created and paid`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))))

        assertEquals(OfflineSaleOutcome.CREATED, result.record.outcome)
        assertTrue(result.record.conflicts.isEmpty())
        assertEquals(false, result.replayed)
        val order = h.orders.getOrder(result.record.orderId!!)!!
        assertEquals(OrderStatus.PAID, order.status)
        assertEquals(10.0, order.totals.total)
    }

    @Test
    fun `resubmitting the same idempotency key replays the original result without creating a second order`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)
        val sale = h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0)))

        val first = h.ingestOne(sale)
        val second = h.ingestOne(sale)

        assertEquals(false, first.replayed)
        assertEquals(true, second.replayed)
        assertEquals(first.record.orderId, second.record.orderId)
        assertEquals(1, h.orders.count())
    }

    @Test
    fun `a duplicate batch resubmission replays every sale in it`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)
        h.seedProduct("SKU-2", price = 5.0)
        val batch = listOf(
            h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))),
            h.sale("key-2", listOf(h.item("SKU-2", unitPriceAtSale = 5.0)))
        )

        val first = (h.sync.ingestBatch(h.device.clientId, h.device.clientSecret, ConflictPolicy.REJECT, batch) as IngestBatchResult.Success).results
        val second = (h.sync.ingestBatch(h.device.clientId, h.device.clientSecret, ConflictPolicy.REJECT, batch) as IngestBatchResult.Success).results

        assertTrue(first.none { it.replayed })
        assertTrue(second.all { it.replayed })
        assertEquals(2, h.orders.count())
    }

    @Test
    fun `a price mismatch under REJECT policy is not persisted`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 12.0)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))), ConflictPolicy.REJECT)

        assertEquals(OfflineSaleOutcome.CONFLICT_REJECTED, result.record.outcome)
        assertNull(result.record.orderId)
        val conflict = result.record.conflicts.single()
        assertEquals(ConflictReason.PRICE_CHANGED, conflict.reason)
        assertEquals("10.0", conflict.capturedValue)
        assertEquals("12.0", conflict.currentValue)
        assertEquals(0, h.orders.count())
    }

    @Test
    fun `a price mismatch under MAP policy is persisted using today's catalog price`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 12.0)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))), ConflictPolicy.MAP)

        assertEquals(OfflineSaleOutcome.CONFLICT_RESOLVED_MAP, result.record.outcome)
        val order = h.orders.getOrder(result.record.orderId!!)!!
        assertEquals(12.0, order.items.single().unitPrice)
        assertEquals(12.0, order.totals.total)
    }

    @Test
    fun `a price mismatch under CONVERT policy is persisted using what the customer actually paid offline`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 12.0)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))), ConflictPolicy.CONVERT)

        assertEquals(OfflineSaleOutcome.CONFLICT_RESOLVED_CONVERT, result.record.outcome)
        val order = h.orders.getOrder(result.record.orderId!!)!!
        assertEquals(10.0, order.items.single().unitPrice)
        assertEquals(10.0, order.totals.total)
    }

    @Test
    fun `a tax category mismatch is reported and resolved the same way as a price mismatch`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0, taxCategory = "EXEMPT")

        val result = h.ingestOne(
            h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0, taxCategoryAtSale = "STANDARD"))),
            ConflictPolicy.MAP
        )

        assertEquals(OfflineSaleOutcome.CONFLICT_RESOLVED_MAP, result.record.outcome)
        val conflict = result.record.conflicts.single { it.reason == ConflictReason.TAX_CATEGORY_CHANGED }
        assertEquals("STANDARD", conflict.capturedValue)
        assertEquals("EXEMPT", conflict.currentValue)
    }

    @Test
    fun `a sku that no longer resolves to any product is always rejected regardless of policy`() {
        for (policy in ConflictPolicy.entries) {
            val h = Harness()
            val result = h.ingestOne(h.sale("key-1", listOf(h.item("does-not-exist", unitPriceAtSale = 10.0))), policy)

            assertEquals(OfflineSaleOutcome.CONFLICT_REJECTED, result.record.outcome, "policy=$policy")
            assertEquals(ConflictReason.PRODUCT_NOT_FOUND, result.record.conflicts.single().reason, "policy=$policy")
            assertNull(result.record.orderId, "policy=$policy")
        }
    }

    @Test
    fun `a deleted product's sku is rejected even under MAP or CONVERT`() {
        val h = Harness()
        val productId = h.seedProduct("SKU-1", price = 10.0)
        h.products.deleteProduct(productId)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))), ConflictPolicy.CONVERT)

        assertEquals(OfflineSaleOutcome.CONFLICT_REJECTED, result.record.outcome)
        assertEquals(ConflictReason.PRODUCT_NOT_FOUND, result.record.conflicts.single().reason)
    }

    @Test
    fun `tenders that exceed the recomputed total are rejected and no order is left behind`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0)), paymentAmount = 15.0))

        assertEquals(OfflineSaleOutcome.CONFLICT_REJECTED, result.record.outcome)
        assertEquals(ConflictReason.PAYMENT_MISMATCH, result.record.conflicts.single().reason)
        assertEquals(0, h.orders.count())
    }

    @Test
    fun `a partial tender leaves the order pending rather than rejecting the sale`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)

        val result = h.ingestOne(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0)), paymentAmount = 6.0))

        assertEquals(OfflineSaleOutcome.CREATED, result.record.outcome)
        val order = h.orders.getOrder(result.record.orderId!!)!!
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(6.0, order.amountPaid)
    }

    @Test
    fun `a sale with no items is rejected as structurally invalid`() {
        val h = Harness()

        val result = h.ingestOne(h.sale("key-1", emptyList(), paymentAmount = 0.0))

        assertEquals(OfflineSaleOutcome.CONFLICT_REJECTED, result.record.outcome)
        assertEquals(ConflictReason.INVALID_SALE, result.record.conflicts.single().reason)
    }

    @Test
    fun `an unknown device is rejected without touching any sale`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)

        val result = h.sync.ingestBatch(
            "unknown-client", "wrong-secret", ConflictPolicy.REJECT,
            listOf(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))))
        )

        assertEquals(IngestBatchResult.InvalidCredentials, result)
        assertEquals(0, h.orders.count())
    }

    @Test
    fun `a deprovisioned device cannot sync`() {
        val h = Harness()
        h.devices.deprovisionDevice(h.device.id, actorId = "admin-1")

        val result = h.sync.ingestBatch(
            h.device.clientId, h.device.clientSecret, ConflictPolicy.REJECT,
            listOf(h.sale("key-1", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))))
        )

        assertEquals(IngestBatchResult.DeviceDeprovisioned, result)
    }

    @Test
    fun `listConflicts surfaces rejected and auto-resolved sales but not clean ones`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 10.0)
        h.seedProduct("SKU-2", price = 20.0)

        h.ingestOne(h.sale("clean", listOf(h.item("SKU-1", unitPriceAtSale = 10.0))), ConflictPolicy.REJECT)
        h.ingestOne(h.sale("rejected", listOf(h.item("SKU-2", unitPriceAtSale = 15.0))), ConflictPolicy.REJECT)
        h.ingestOne(h.sale("mapped", listOf(h.item("SKU-2", unitPriceAtSale = 15.0))), ConflictPolicy.MAP)

        val conflicts = h.sync.listConflicts()
        assertEquals(setOf("rejected", "mapped"), conflicts.map { it.idempotencyKey }.toSet())
    }
}
