package com.beettechnologies.posly.inventory

import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockCountServiceTest {

    private data class Harness(
        val products: ProductService,
        val stores: StoreService,
        val inventory: InventoryService,
        val stockCounts: StockCountService
    )

    private fun newHarness(): Harness {
        val products = ProductService()
        val stores = StoreService(TaxProfileService())
        val inventory = InventoryService(products, stores)
        return Harness(products, stores, inventory, StockCountService(inventory, products, stores))
    }

    private fun seedProduct(products: ProductService, sku: String = "SKU-1"): String {
        val result = products.createProduct(CreateProductRequest(sku = sku, name = "Widget", price = 9.99, taxCategory = "STANDARD"))
        return (result as ProductResult.Created).product.id
    }

    private fun seedStore(stores: StoreService, name: String = "Downtown"): String {
        val result = stores.createStore(
            name = name,
            address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
            timezone = "America/New_York",
            currency = "USD",
            taxProfileId = null
        )
        return (result as CreateStoreResult.Created).store.id
    }

    @Test
    fun `a count matching expected on-hand produces a variance-free report with no adjustment`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)
        h.inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        val result = h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, 10)), countedBy = "manager-1")

        val stockCount = assertIs<SubmitStockCountResult.Success>(result).stockCount
        assertEquals(false, stockCount.hasVariance)
        assertEquals(0, stockCount.totalVarianceUnits)
        val variance = stockCount.variances.single()
        assertEquals(10, variance.expectedQuantity)
        assertEquals(10, variance.countedQuantity)
        assertEquals(0, variance.delta)
        assertEquals(VarianceCause.NONE, variance.cause)
        assertNull(variance.adjustmentTransactionId)
        assertEquals(10, h.inventory.getSnapshot(productId, storeId)?.onHand)
    }

    @Test
    fun `a shortage posts a negative adjustment and is classified as SHORTAGE`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)
        h.inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        val result = h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, 6)), countedBy = "manager-1")

        val stockCount = assertIs<SubmitStockCountResult.Success>(result).stockCount
        assertTrue(stockCount.hasVariance)
        assertEquals(4, stockCount.totalVarianceUnits)
        val variance = stockCount.variances.single()
        assertEquals(-4, variance.delta)
        assertEquals(VarianceCause.SHORTAGE, variance.cause)
        assertEquals(-4, variance.suggestedAdjustment)
        assertEquals(6, h.inventory.getSnapshot(productId, storeId)?.onHand, "the shortage must be reconciled into on-hand stock")

        val adjustment = h.inventory.listTransactions(storeId, productId).first { it.id == variance.adjustmentTransactionId }
        assertEquals(-4, adjustment.quantity)
        assertEquals(stockCount.id, adjustment.referenceId, "the adjustment must be traceable back to the stock count")
        assertEquals("manager-1", adjustment.actorId)
    }

    @Test
    fun `an overage posts a positive adjustment and is classified as OVERAGE`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)
        h.inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        val result = h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, 15)), countedBy = "manager-1")

        val stockCount = assertIs<SubmitStockCountResult.Success>(result).stockCount
        val variance = stockCount.variances.single()
        assertEquals(5, variance.delta)
        assertEquals(VarianceCause.OVERAGE, variance.cause)
        assertEquals(15, h.inventory.getSnapshot(productId, storeId)?.onHand)
    }

    @Test
    fun `a count with no prior snapshot activity treats expected on-hand as zero`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)

        val result = h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, 3)), countedBy = null)

        val stockCount = assertIs<SubmitStockCountResult.Success>(result).stockCount
        val variance = stockCount.variances.single()
        assertEquals(0, variance.expectedQuantity)
        assertEquals(3, variance.delta)
        assertEquals(3, h.inventory.getSnapshot(productId, storeId)?.onHand)
    }

    @Test
    fun `a multi-line count aggregates every product's variance in one report`() {
        val h = newHarness()
        val productA = seedProduct(h.products, sku = "SKU-A")
        val productB = seedProduct(h.products, sku = "SKU-B")
        val storeId = seedStore(h.stores)
        h.inventory.adjustStock(productA, storeId, delta = 10, reason = "Initial stock", actorId = null)
        h.inventory.adjustStock(productB, storeId, delta = 5, reason = "Initial stock", actorId = null)

        val result = h.stockCounts.submitStockCount(
            storeId,
            listOf(StockCountLineInput(productA, 8), StockCountLineInput(productB, 5)),
            countedBy = "manager-1"
        )

        val stockCount = assertIs<SubmitStockCountResult.Success>(result).stockCount
        assertEquals(2, stockCount.variances.size)
        assertEquals(2, stockCount.totalVarianceUnits, "only product A's 2-unit shortfall should count toward the total")
        val varianceA = stockCount.variances.single { it.productId == productA }
        val varianceB = stockCount.variances.single { it.productId == productB }
        assertEquals(-2, varianceA.delta)
        assertEquals(0, varianceB.delta)
        assertNull(varianceB.adjustmentTransactionId, "a matching count must not post an adjustment")
    }

    @Test
    fun `submitting for an unknown store is rejected`() {
        val h = newHarness()
        val productId = seedProduct(h.products)

        val result = h.stockCounts.submitStockCount("does-not-exist", listOf(StockCountLineInput(productId, 1)), countedBy = null)

        assertEquals(SubmitStockCountResult.StoreNotFound, result)
    }

    @Test
    fun `submitting an empty count is rejected`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        val result = h.stockCounts.submitStockCount(storeId, emptyList(), countedBy = null)

        assertIs<SubmitStockCountResult.InvalidLine>(result)
    }

    @Test
    fun `submitting a count for an unknown product is rejected`() {
        val h = newHarness()
        val storeId = seedStore(h.stores)

        val result = h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput("does-not-exist", 1)), countedBy = null)

        assertIs<SubmitStockCountResult.InvalidLine>(result)
    }

    @Test
    fun `a negative counted quantity is rejected`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)

        val result = h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, -1)), countedBy = null)

        assertIs<SubmitStockCountResult.InvalidLine>(result)
    }

    @Test
    fun `a duplicate product line in the same count is rejected`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)

        val result = h.stockCounts.submitStockCount(
            storeId, listOf(StockCountLineInput(productId, 1), StockCountLineInput(productId, 2)), countedBy = null
        )

        assertIs<SubmitStockCountResult.InvalidLine>(result)
    }

    @Test
    fun `counting below the currently reserved quantity is rejected without posting a partial adjustment`() {
        val h = newHarness()
        val productA = seedProduct(h.products, sku = "SKU-A")
        val productB = seedProduct(h.products, sku = "SKU-B")
        val storeId = seedStore(h.stores)
        h.inventory.adjustStock(productA, storeId, delta = 10, reason = "Initial stock", actorId = null)
        h.inventory.adjustStock(productB, storeId, delta = 10, reason = "Initial stock", actorId = null)
        h.inventory.reserve(productA, storeId, quantity = 4, referenceId = "cart-1")

        // Product A's count (2) is below its 4 reserved units; product B's count is otherwise a
        // legitimate 3-unit shortage that must NOT be applied given the whole submission fails.
        val result = h.stockCounts.submitStockCount(
            storeId,
            listOf(StockCountLineInput(productA, 2), StockCountLineInput(productB, 7)),
            countedBy = "manager-1"
        )

        assertIs<SubmitStockCountResult.InvalidLine>(result)
        assertEquals(10, h.inventory.getSnapshot(productB, storeId)?.onHand, "no line may be reconciled when the submission as a whole is rejected")
        assertTrue(h.stockCounts.listStockCounts(storeId).isEmpty())
    }

    @Test
    fun `getStockCount and listStockCounts retrieve what was submitted`() {
        val h = newHarness()
        val productId = seedProduct(h.products)
        val storeId = seedStore(h.stores)
        h.inventory.adjustStock(productId, storeId, delta = 10, reason = "Initial stock", actorId = null)

        val stockCount = (h.stockCounts.submitStockCount(storeId, listOf(StockCountLineInput(productId, 8)), countedBy = "manager-1")
            as SubmitStockCountResult.Success).stockCount

        assertEquals(stockCount, h.stockCounts.getStockCount(stockCount.id))
        assertEquals(listOf(stockCount), h.stockCounts.listStockCounts(storeId))
        assertEquals(listOf(stockCount), h.stockCounts.listStockCounts())
        assertNull(h.stockCounts.getStockCount("does-not-exist"))
    }
}
