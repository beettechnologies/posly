package com.beettechnologies.posly.inventory

import com.beettechnologies.posly.TestDatabase

import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventoryServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
    }

    private fun newHarness(): Triple<ProductService, StoreService, InventoryService> {
        val productService = ProductService()
        val storeService = StoreService(TaxProfileService())
        return Triple(productService, storeService, InventoryService(productService, storeService))
    }

    private fun seedProduct(productService: ProductService, sku: String = "SKU-1"): String {
        val result = productService.createProduct(
            CreateProductRequest(sku = sku, name = "Widget", price = 9.99, taxCategory = "STANDARD")
        )
        return (result as ProductResult.Created).product.id
    }

    private fun seedStore(storeService: StoreService, name: String = "Downtown"): String {
        val result = storeService.createStore(
            name = name,
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = null
        )
        return (result as CreateStoreResult.Created).store.id
    }

    @Test
    fun `adjusting stock creates a snapshot and records a transaction`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)

        val result = inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = "admin-1")

        val snapshot = assertIs<AdjustStockResult.Success>(result).snapshot
        assertEquals(10, snapshot.onHand)
        assertEquals(0, snapshot.reserved)
        assertEquals(10, snapshot.available)

        val transactions = inventory.listTransactions(storeId, productId)
        assertEquals(1, transactions.size)
        assertEquals(InventoryTransactionType.ADJUSTMENT, transactions[0].type)
        assertEquals(10, transactions[0].quantity)
        assertEquals("Initial stock", transactions[0].reason)
    }

    @Test
    fun `adjustment for unknown product returns ProductNotFound`() {
        val (products, stores, inventory) = newHarness()
        val storeId = seedStore(stores)

        val result = inventory.adjustStock("does-not-exist", storeId, delta = 5, reason = "x", actorId = null)

        assertEquals(AdjustStockResult.ProductNotFound, result)
    }

    @Test
    fun `adding item to reservation decrements available stock and records the reservation`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        val result = inventory.reserve(productId, storeId, quantity = 3, referenceId = "cart-1")

        val success = assertIs<ReserveResult.Success>(result)
        assertEquals(3, success.snapshot.reserved)
        assertEquals(7, success.snapshot.available)
        assertEquals("cart-1", success.reservation.referenceId)
        assertEquals(ReservationStatus.ACTIVE, success.reservation.status)
    }

    @Test
    fun `reserving more than available stock is rejected`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 2, reason = "Initial stock", actorId = null)

        val result = inventory.reserve(productId, storeId, quantity = 3, referenceId = "cart-1")

        assertEquals(ReserveResult.InsufficientStock, result)
        val snapshot = inventory.getSnapshot(productId, storeId)!!
        assertEquals(0, snapshot.reserved)
        assertEquals(2, snapshot.available)
    }

    @Test
    fun `completing an order converts reserved quantity to sold and reduces inventory`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)
        val reservation = (inventory.reserve(productId, storeId, quantity = 4, referenceId = "cart-1") as ReserveResult.Success).reservation

        val result = inventory.commit(reservation.id)

        val success = assertIs<CommitReservationResult.Success>(result)
        assertEquals(ReservationStatus.COMMITTED, success.reservation.status)
        assertEquals(6, success.snapshot.onHand)
        assertEquals(0, success.snapshot.reserved)
        assertEquals(6, success.snapshot.available)

        val transactions = inventory.listTransactions(storeId, productId)
        assertTrue(transactions.any { it.type == InventoryTransactionType.COMMIT && it.quantity == 4 })
    }

    @Test
    fun `releasing a reservation restores available stock`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)
        val reservation = (inventory.reserve(productId, storeId, quantity = 4, referenceId = "cart-1") as ReserveResult.Success).reservation

        val result = inventory.release(reservation.id)

        val success = assertIs<ReleaseResult.Success>(result)
        assertEquals(ReservationStatus.RELEASED, success.reservation.status)
        assertEquals(10, success.snapshot.onHand)
        assertEquals(0, success.snapshot.reserved)
        assertEquals(10, success.snapshot.available)
    }

    @Test
    fun `a reservation cannot be released or committed twice`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)
        val reservation = (inventory.reserve(productId, storeId, quantity = 4, referenceId = "cart-1") as ReserveResult.Success).reservation

        assertIs<ReleaseResult.Success>(inventory.release(reservation.id))
        assertEquals(ReleaseResult.NotActive, inventory.release(reservation.id))
        assertEquals(CommitReservationResult.NotActive, inventory.commit(reservation.id))

        // Only one release's worth of stock should have been restored.
        val snapshot = inventory.getSnapshot(productId, storeId)!!
        assertEquals(10, snapshot.onHand)
        assertEquals(0, snapshot.reserved)
    }

    @Test
    fun `admin stock adjustment corrects counts and is recorded`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        val result = inventory.adjustStock(productId, storeId, delta = -3, reason = "Damaged in transit", actorId = "admin-1")

        val snapshot = assertIs<AdjustStockResult.Success>(result).snapshot
        assertEquals(7, snapshot.onHand)

        val transactions = inventory.listTransactions(storeId, productId)
        val adjustment = transactions.first { it.reason == "Damaged in transit" }
        assertEquals(-3, adjustment.quantity)
        assertEquals("admin-1", adjustment.actorId)
    }

    @Test
    fun `adjustStock returns the id of the transaction it recorded and accepts an optional referenceId`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)

        val result = inventory.adjustStock(
            productId, storeId, delta = 10, reason = "Stock count reconciliation (count-1)", actorId = "manager-1", referenceId = "count-1"
        )

        val success = assertIs<AdjustStockResult.Success>(result)
        val transactions = inventory.listTransactions(storeId, productId)
        assertEquals(1, transactions.size)
        assertEquals(success.transactionId, transactions.single().id)
        assertEquals("count-1", transactions.single().referenceId)
    }

    @Test
    fun `concurrent reservations never oversell available stock`() {
        val (products, stores, inventory) = newHarness()
        val productId = seedProduct(products)
        val storeId = seedStore(stores)
        inventory.adjustStock(productId, storeId, delta = 5, reason = "Initial stock", actorId = null)

        val threadCount = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ReserveResult>())

        val futures = (1..threadCount).map { i ->
            executor.submit {
                startLatch.await()
                results.add(inventory.reserve(productId, storeId, quantity = 1, referenceId = "cart-$i"))
            }
        }
        startLatch.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        val successes = results.count { it is ReserveResult.Success }
        val rejections = results.count { it is ReserveResult.InsufficientStock }
        assertEquals(5, successes, "exactly onHand reservations should succeed, no more")
        assertEquals(threadCount - 5, rejections)

        val snapshot = inventory.getSnapshot(productId, storeId)!!
        assertEquals(5, snapshot.reserved)
        assertEquals(0, snapshot.available)
        assertEquals(5, inventory.listTransactions(storeId, productId).count { it.type == InventoryTransactionType.RESERVE })
    }
}
