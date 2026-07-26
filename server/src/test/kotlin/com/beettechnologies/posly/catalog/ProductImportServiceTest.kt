package com.beettechnologies.posly.catalog

import com.beettechnologies.posly.products.ProductService
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val STANDARD_MAPPING = mapOf(
    ProductImportField.SKU to "sku",
    ProductImportField.NAME to "name",
    ProductImportField.PRICE to "price",
    ProductImportField.DESCRIPTION to "description",
    ProductImportField.TAX_CATEGORY to "taxCategory",
    ProductImportField.BARCODE to "barcode",
    ProductImportField.CATEGORY to "category",
    ProductImportField.IN_STOCK to "inStock"
)

class ProductImportServiceTest {

    private lateinit var productService: ProductService
    private lateinit var importService: ProductImportService

    @BeforeTest
    fun setUp() {
        productService = ProductService()
        importService = ProductImportService(productService)
    }

    private fun csv(vararg lines: String) = lines.joinToString("\n").toByteArray()

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @Test
    fun `uploading a CSV returns headers and rows`() {
        val bytes = csv(
            "sku,name,price,description,taxCategory,barcode,category,inStock",
            "SKU-1,Widget,9.99,A widget,STANDARD,111,Widgets,true"
        )
        val result = assertIs<UploadCsvResult.Success>(importService.uploadCsv("products.csv", bytes))
        assertEquals(listOf("sku", "name", "price", "description", "taxCategory", "barcode", "category", "inStock"), result.file.headers)
        assertEquals(1, result.file.rows.size)
    }

    @Test
    fun `uploading an empty file is rejected`() {
        val result = assertIs<UploadCsvResult.InvalidCsv>(importService.uploadCsv("empty.csv", ByteArray(0)))
        assertTrue(result.message.isNotBlank())
    }

    // -------------------------------------------------------------------------
    // Dry run - validation and line numbers
    // -------------------------------------------------------------------------

    @Test
    fun `dry-run flags a row with a missing required field by line number`() {
        val bytes = csv(
            "sku,name,price",
            "SKU-1,Widget,9.99",
            ",Missing Sku,5.00"
        )
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")

        val result = assertIs<DryRunResult.Success>(importService.dryRun(file.id, mapping))

        assertEquals(2, result.outcomes.size)
        assertEquals(ImportRowAction.CREATED, result.outcomes[0].action)
        val badRow = result.outcomes[1]
        assertEquals(2, badRow.rowNumber)
        assertEquals(ImportRowAction.ERROR, badRow.action)
        assertTrue(badRow.errors.any { it.contains("sku") })
    }

    @Test
    fun `dry-run flags an unparseable price and an invalid taxCategory`() {
        val bytes = csv(
            "sku,name,price,taxCategory",
            "SKU-1,Widget,not-a-number,STANDARD",
            "SKU-2,Gadget,4.99,NOT_REAL"
        )
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price", ProductImportField.TAX_CATEGORY to "taxCategory")

        val result = assertIs<DryRunResult.Success>(importService.dryRun(file.id, mapping))

        assertEquals(ImportRowAction.ERROR, result.outcomes[0].action)
        assertTrue(result.outcomes[0].errors.any { it.contains("price") })
        assertEquals(ImportRowAction.ERROR, result.outcomes[1].action)
        assertTrue(result.outcomes[1].errors.any { it.contains("taxCategory") })
    }

    @Test
    fun `dry-run flags duplicate SKUs within the same file, referencing the first occurrence`() {
        val bytes = csv(
            "sku,name,price",
            "SKU-1,Widget,9.99",
            "SKU-2,Gadget,4.99",
            "SKU-1,Widget Reissue,10.99"
        )
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")

        val result = assertIs<DryRunResult.Success>(importService.dryRun(file.id, mapping))

        assertEquals(ImportRowAction.CREATED, result.outcomes[0].action)
        assertEquals(ImportRowAction.CREATED, result.outcomes[1].action)
        val duplicateRow = result.outcomes[2]
        assertEquals(3, duplicateRow.rowNumber)
        assertEquals(ImportRowAction.ERROR, duplicateRow.action)
        assertTrue(duplicateRow.errors.any { it.contains("Duplicate SKU") && it.contains("row 1") })
    }

    @Test
    fun `dry-run reports UPDATED for a SKU that already exists in the catalog`() {
        productService.createProduct(
            com.beettechnologies.posly.products.CreateProductRequest(sku = "SKU-1", name = "Old Name", price = 1.0)
        )
        val bytes = csv("sku,name,price", "SKU-1,New Name,2.0")
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")

        val result = assertIs<DryRunResult.Success>(importService.dryRun(file.id, mapping))
        assertEquals(ImportRowAction.UPDATED, result.outcomes[0].action)
    }

    @Test
    fun `dry-run rejects a mapping missing a required field`() {
        val bytes = csv("sku,name,price", "SKU-1,Widget,9.99")
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name")

        val result = assertIs<DryRunResult.InvalidMapping>(importService.dryRun(file.id, mapping))
        assertTrue(result.errors.any { it.contains("PRICE") })
    }

    @Test
    fun `dry-run against an unknown file id returns FileNotFound`() {
        assertIs<DryRunResult.FileNotFound>(importService.dryRun("does-not-exist", STANDARD_MAPPING))
    }

    // -------------------------------------------------------------------------
    // Full import - DB state
    // -------------------------------------------------------------------------

    @Test
    fun `running an import creates new products and updates existing ones, skipping errored rows`() {
        productService.createProduct(
            com.beettechnologies.posly.products.CreateProductRequest(sku = "SKU-EXISTING", name = "Old Name", price = 1.0)
        )
        val bytes = csv(
            "sku,name,price",
            "SKU-NEW,Brand New,3.50",
            "SKU-EXISTING,Renamed,4.25",
            ",Bad Row,1.00"
        )
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")

        val job = (importService.startImport(file.id, mapping, startedBy = "admin-1") as StartImportResult.Success).job
        importService.runImportNow(job.id)

        val completed = importService.getJob(job.id)!!
        assertEquals(ImportJobStatus.COMPLETED, completed.status)
        assertEquals(1, completed.createdCount)
        assertEquals(1, completed.updatedCount)
        assertEquals(1, completed.erroredCount)

        val created = productService.getProductBySku("SKU-NEW")
        assertNotNull(created)
        assertEquals("Brand New", created.name)
        assertEquals(3.50, created.price)

        val updated = productService.getProductBySku("SKU-EXISTING")!!
        assertEquals("Renamed", updated.name)
        assertEquals(4.25, updated.price)
    }

    @Test
    fun `starting an import against an invalid mapping does not create a job`() {
        val bytes = csv("sku,name,price", "SKU-1,Widget,9.99")
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val result = importService.startImport(file.id, mapOf(ProductImportField.SKU to "sku"), startedBy = null)
        assertIs<StartImportResult.InvalidMapping>(result)
    }

    // -------------------------------------------------------------------------
    // Rollback
    // -------------------------------------------------------------------------

    @Test
    fun `rollback deletes created products and restores updated ones to their previous state`() {
        productService.createProduct(
            com.beettechnologies.posly.products.CreateProductRequest(
                sku = "SKU-EXISTING", name = "Old Name", price = 1.0, description = "original description"
            )
        )
        val bytes = csv(
            "sku,name,price,description",
            "SKU-NEW,Brand New,3.50,",
            "SKU-EXISTING,Renamed,4.25,"
        )
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(
            ProductImportField.SKU to "sku", ProductImportField.NAME to "name",
            ProductImportField.PRICE to "price", ProductImportField.DESCRIPTION to "description"
        )
        val job = (importService.startImport(file.id, mapping, startedBy = "admin-1") as StartImportResult.Success).job
        importService.runImportNow(job.id)

        assertNotNull(productService.getProductBySku("SKU-NEW"))
        assertEquals("Renamed", productService.getProductBySku("SKU-EXISTING")!!.name)

        val rollback = assertIs<RollbackResult.Success>(importService.rollback(job.id, actorUserId = "admin-1"))
        assertTrue(rollback.job.rolledBack)

        assertNull(productService.getProductBySku("SKU-NEW"))
        val restored = productService.getProductBySku("SKU-EXISTING")!!
        assertEquals("Old Name", restored.name)
        assertEquals(1.0, restored.price)
        assertEquals("original description", restored.description)
    }

    @Test
    fun `rollback cannot be applied twice`() {
        val bytes = csv("sku,name,price", "SKU-1,Widget,9.99")
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")
        val job = (importService.startImport(file.id, mapping, startedBy = null) as StartImportResult.Success).job
        importService.runImportNow(job.id)

        assertIs<RollbackResult.Success>(importService.rollback(job.id, null))
        assertIs<RollbackResult.AlreadyRolledBack>(importService.rollback(job.id, null))
    }

    @Test
    fun `only the most recently completed import can be rolled back`() {
        val bytes1 = csv("sku,name,price", "SKU-1,Widget,9.99")
        val file1 = (importService.uploadCsv("f1.csv", bytes1) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")
        val job1 = (importService.startImport(file1.id, mapping, startedBy = null) as StartImportResult.Success).job
        importService.runImportNow(job1.id)

        val bytes2 = csv("sku,name,price", "SKU-2,Gadget,4.99")
        val file2 = (importService.uploadCsv("f2.csv", bytes2) as UploadCsvResult.Success).file
        val job2 = (importService.startImport(file2.id, mapping, startedBy = null) as StartImportResult.Success).job
        importService.runImportNow(job2.id)

        assertIs<RollbackResult.NotMostRecentImport>(importService.rollback(job1.id, null))
        assertIs<RollbackResult.Success>(importService.rollback(job2.id, null))
    }

    @Test
    fun `rollback of a job that has not completed yet is rejected`() {
        val bytes = csv("sku,name,price", "SKU-1,Widget,9.99")
        val file = (importService.uploadCsv("f.csv", bytes) as UploadCsvResult.Success).file
        val mapping = mapOf(ProductImportField.SKU to "sku", ProductImportField.NAME to "name", ProductImportField.PRICE to "price")
        val job = (importService.startImport(file.id, mapping, startedBy = null) as StartImportResult.Success).job
        // importScope is null in this test, so the job stays PENDING until runImportNow is called explicitly.

        assertIs<RollbackResult.JobNotCompleted>(importService.rollback(job.id, null))
    }

    @Test
    fun `rollback of an unknown job id returns JobNotFound`() {
        assertIs<RollbackResult.JobNotFound>(importService.rollback("does-not-exist", null))
    }
}
