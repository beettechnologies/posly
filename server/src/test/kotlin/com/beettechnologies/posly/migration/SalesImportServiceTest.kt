package com.beettechnologies.posly.migration

import com.beettechnologies.posly.TestDatabase
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.OrderStatus
import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.Address
import com.beettechnologies.posly.stores.CreateStoreResult
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.TaxProfileService
import com.beettechnologies.posly.stores.TaxRate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val STANDARD_MAPPING = mapOf(
    SalesImportField.ORDER_REFERENCE to "orderRef",
    SalesImportField.STORE_ID to "storeId",
    SalesImportField.SKU to "sku",
    SalesImportField.QUANTITY to "qty",
    SalesImportField.UNIT_PRICE to "unitPrice",
    SalesImportField.SOLD_AT to "soldAt",
    SalesImportField.PAYMENT_METHOD to "paymentMethod",
    SalesImportField.TOTAL_AMOUNT to "total",
    SalesImportField.SUBTOTAL to "subtotal",
    SalesImportField.TAX_AMOUNT to "tax",
    SalesImportField.PAYMENT_REFERENCE to "paymentRef",
    SalesImportField.SOLD_BY to "soldBy"
)
private const val HEADER = "orderRef,storeId,sku,qty,unitPrice,soldAt,paymentMethod,total,subtotal,tax,paymentRef,soldBy"

private class Harness {
    val products = ProductService()
    val taxProfiles = TaxProfileService()
    val stores = StoreService(taxProfiles)
    val orders = OrderService()
    val importService = SalesImportService(products, stores, orders)

    val storeId: String = run {
        val taxProfileId = taxProfiles.createProfile(name = "Sales Tax", rates = listOf(TaxRate("Sales Tax", 0.0))).id
        (
            stores.createStore(
                name = "Downtown",
                address = Address(line1 = "1 Main St", city = "New York", postalCode = "10001", country = "US"),
                timezone = "America/New_York",
                currency = "USD",
                taxProfileId = taxProfileId
            ) as CreateStoreResult.Created
            ).store.id
    }

    fun seedProduct(sku: String, price: Double = 10.0): String =
        (products.createProduct(CreateProductRequest(sku = sku, name = "Widget", price = price)) as ProductResult.Created).product.id

    fun csv(vararg dataLines: String) = (listOf(HEADER) + dataLines).joinToString("\n").toByteArray()

    fun uploadedFile(vararg dataLines: String) =
        (importService.uploadCsv("sales.csv", csv(*dataLines)) as UploadSalesCsvResult.Success).file
}

class SalesImportServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @Test
    fun `uploading a CSV returns headers and rows`() {
        val h = Harness()
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        assertEquals(12, file.headers.size)
        assertEquals(1, file.rows.size)
    }

    @Test
    fun `uploading an empty file is rejected`() {
        val h = Harness()
        val result = assertIs<UploadSalesCsvResult.InvalidCsv>(h.importService.uploadCsv("empty.csv", ByteArray(0)))
        assertTrue(result.message.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Dry run
    // -------------------------------------------------------------------------

    @Test
    fun `dry-run matches a well formed single-item order`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,2,10.00,2024-01-01T00:00:00Z,CASH,20.00,20.00,0.00,,cashier-1")

        val report = (h.importService.dryRun(file.id, STANDARD_MAPPING) as SalesDryRunResult.Success).report
        assertEquals(1, report.rowOutcomes.size)
        assertEquals(SalesRowResult.MATCHED, report.rowOutcomes[0].result)
        assertEquals(1, report.groups.size)
        assertTrue(report.groups[0].importable)
        assertEquals(20.00, report.groups[0].total)
    }

    @Test
    fun `dry-run flags an unknown SKU as unmatched`() {
        val h = Harness()
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-MISSING,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")

        val report = (h.importService.dryRun(file.id, STANDARD_MAPPING) as SalesDryRunResult.Success).report
        assertEquals(SalesRowResult.UNMATCHED, report.rowOutcomes[0].result)
        assertTrue(report.rowOutcomes[0].errors.any { it.contains("SKU-MISSING") })
        assertTrue(!report.groups[0].importable)
    }

    @Test
    fun `dry-run flags an unknown store as unmatched`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile("ORD-1,no-such-store,SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")

        val report = (h.importService.dryRun(file.id, STANDARD_MAPPING) as SalesDryRunResult.Success).report
        assertEquals(SalesRowResult.UNMATCHED, report.rowOutcomes[0].result)
        assertTrue(report.groups[0].errors.any { it.contains("storeId") })
    }

    @Test
    fun `dry-run groups multiple line-item rows sharing an order reference into one group`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        h.seedProduct("SKU-2")
        val file = h.uploadedFile(
            "ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,25.00,25.00,0.00,,cashier-1",
            "ORD-1,,SKU-2,1,15.00,,,,,,,"
        )

        val report = (h.importService.dryRun(file.id, STANDARD_MAPPING) as SalesDryRunResult.Success).report
        assertEquals(1, report.groups.size)
        val group = report.groups[0]
        assertEquals(2, group.itemCount)
        assertEquals(listOf(1, 2), group.rowNumbers)
        assertTrue(group.importable)
        assertEquals(2, report.rowOutcomes.size)
        assertTrue(report.rowOutcomes.all { it.result == SalesRowResult.MATCHED })
    }

    @Test
    fun `dry-run rejects a mapping missing a required field`() {
        val h = Harness()
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val incomplete = STANDARD_MAPPING - SalesImportField.TOTAL_AMOUNT

        val result = assertIs<SalesDryRunResult.InvalidMapping>(h.importService.dryRun(file.id, incomplete))
        assertTrue(result.errors.any { it.contains("TOTAL_AMOUNT") })
    }

    @Test
    fun `dry-run against an unknown file id returns FileNotFound`() {
        val h = Harness()
        assertIs<SalesDryRunResult.FileNotFound>(h.importService.dryRun("does-not-exist", STANDARD_MAPPING))
    }

    // -------------------------------------------------------------------------
    // Full import - DB state and reconciliation
    // -------------------------------------------------------------------------

    @Test
    fun `running an import creates a paid order from grouped rows using the legacy-supplied totals verbatim`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 999.0) // catalog price deliberately differs from the historical sale price
        h.seedProduct("SKU-2", price = 999.0)
        val file = h.uploadedFile(
            "ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,26.75,25.00,1.75,ref-1,cashier-1",
            "ORD-1,,SKU-2,1,15.00,,,,,,,"
        )
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = "admin-1") as StartSalesImportResult.Success).job
        h.importService.runImportNow(job.id)

        val completed = h.importService.getJob(job.id)!!
        assertEquals(SalesImportJobStatus.COMPLETED, completed.status)
        assertEquals(1, completed.importedCount)
        assertEquals(0, completed.skippedUnmatchedCount)
        assertEquals(1, completed.importedOrders.size)

        val order = h.orders.getOrder(completed.importedOrders[0].orderId)!!
        assertEquals(OrderStatus.PAID, order.status)
        assertEquals(2, order.items.size)
        assertEquals(26.75, order.totals.total)
        assertEquals(1.75, order.totals.totalTax)
        assertEquals(1, order.payments.size)
        assertEquals("CASH", order.payments[0].method)
        assertEquals("ref-1", order.payments[0].reference)
        assertEquals(java.time.Instant.parse("2024-01-01T00:00:00Z"), order.checkedOutAt)
    }

    @Test
    fun `running an import skips a group with any unmatched row and counts it separately`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile(
            "ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1",
            "ORD-BAD,${h.storeId},SKU-MISSING,1,5.00,2024-01-01T00:00:00Z,CASH,5.00,5.00,0.00,,cashier-1"
        )
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job.id)

        val completed = h.importService.getJob(job.id)!!
        assertEquals(1, completed.importedCount)
        assertEquals(1, completed.skippedUnmatchedCount)
    }

    @Test
    fun `a zero-total historical order is imported without a payment confirmation`() {
        val h = Harness()
        h.seedProduct("SKU-1", price = 0.0)
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,0.00,2024-01-01T00:00:00Z,COMP,0.00,0.00,0.00,,cashier-1")
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job.id)

        val completed = h.importService.getJob(job.id)!!
        assertEquals(1, completed.importedCount)
        val order = h.orders.getOrder(completed.importedOrders[0].orderId)!!
        assertTrue(order.payments.isEmpty())
        assertEquals(OrderStatus.PENDING, order.status)
    }

    @Test
    fun `re-running the same import file does not create duplicate orders`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")

        val job1 = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job1.id)
        val firstOrderId = h.importService.getJob(job1.id)!!.importedOrders[0].orderId

        val job2 = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job2.id)
        val secondJob = h.importService.getJob(job2.id)!!

        assertEquals(0, secondJob.importedCount)
        assertEquals(1, secondJob.skippedAlreadyImportedCount)
        assertEquals(firstOrderId, secondJob.importedOrders[0].orderId)
        assertEquals(1, h.orders.count())
    }

    @Test
    fun `starting an import against an invalid mapping does not create a job`() {
        val h = Harness()
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val result = h.importService.startImport(file.id, STANDARD_MAPPING - SalesImportField.SOLD_AT, startedBy = null)
        assertIs<StartSalesImportResult.InvalidMapping>(result)
    }

    // -------------------------------------------------------------------------
    // Reconciliation report
    // -------------------------------------------------------------------------

    @Test
    fun `reconciliation report lists counts and sample record mappings after completion`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job.id)

        val report = (h.importService.getReconciliationReport(job.id) as SalesReconciliationResult.Success).report
        assertEquals(1, report.importedCount)
        assertEquals(1, report.sampleMappings.size)
        assertEquals("ORD-1", report.sampleMappings[0].orderReference)
    }

    @Test
    fun `reconciliation report for a job that has not completed yet is rejected`() {
        val h = Harness()
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job

        assertIs<SalesReconciliationResult.JobNotCompleted>(h.importService.getReconciliationReport(job.id))
    }

    @Test
    fun `reconciliation report for an unknown job id returns JobNotFound`() {
        val h = Harness()
        assertIs<SalesReconciliationResult.JobNotFound>(h.importService.getReconciliationReport("does-not-exist"))
    }

    // -------------------------------------------------------------------------
    // Rollback
    // -------------------------------------------------------------------------

    @Test
    fun `rollback deletes the orders this import created`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job.id)
        val orderId = h.importService.getJob(job.id)!!.importedOrders[0].orderId

        val rollback = assertIs<SalesRollbackResult.Success>(h.importService.rollback(job.id, "admin-1"))
        assertTrue(rollback.job.rolledBack)
        assertNull(h.orders.getOrder(orderId))
    }

    @Test
    fun `rollback cannot be applied twice`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job.id)

        assertIs<SalesRollbackResult.Success>(h.importService.rollback(job.id, null))
        assertIs<SalesRollbackResult.AlreadyRolledBack>(h.importService.rollback(job.id, null))
    }

    @Test
    fun `only the most recently completed import can be rolled back`() {
        val h = Harness()
        h.seedProduct("SKU-1")
        val file1 = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job1 = (h.importService.startImport(file1.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job1.id)

        val file2 = h.uploadedFile("ORD-2,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job2 = (h.importService.startImport(file2.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job
        h.importService.runImportNow(job2.id)

        assertIs<SalesRollbackResult.NotMostRecentImport>(h.importService.rollback(job1.id, null))
        assertIs<SalesRollbackResult.Success>(h.importService.rollback(job2.id, null))
    }

    @Test
    fun `rollback of a job that has not completed yet is rejected`() {
        val h = Harness()
        val file = h.uploadedFile("ORD-1,${h.storeId},SKU-1,1,10.00,2024-01-01T00:00:00Z,CASH,10.00,10.00,0.00,,cashier-1")
        val job = (h.importService.startImport(file.id, STANDARD_MAPPING, startedBy = null) as StartSalesImportResult.Success).job

        assertIs<SalesRollbackResult.JobNotCompleted>(h.importService.rollback(job.id, null))
    }

    @Test
    fun `rollback of an unknown job id returns JobNotFound`() {
        val h = Harness()
        assertIs<SalesRollbackResult.JobNotFound>(h.importService.rollback("does-not-exist", null))
    }
}
