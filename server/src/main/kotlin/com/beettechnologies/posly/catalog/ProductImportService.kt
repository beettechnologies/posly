package com.beettechnologies.posly.catalog

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.products.CreateProductRequest
import com.beettechnologies.posly.products.ProductResult
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.products.UpdateProductRequest
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.apache.commons.csv.CSVFormat

sealed class UploadCsvResult {
    data class Success(val file: ProductImportFile) : UploadCsvResult()
    data class InvalidCsv(val message: String) : UploadCsvResult()
}

sealed class StartImportResult {
    data class Success(val job: ProductImportJob) : StartImportResult()
    data object FileNotFound : StartImportResult()
    data class InvalidMapping(val errors: List<String>) : StartImportResult()
}

sealed class DryRunResult {
    data class Success(val outcomes: List<ImportRowOutcome>) : DryRunResult()
    data object FileNotFound : DryRunResult()
    data class InvalidMapping(val errors: List<String>) : DryRunResult()
}

sealed class RollbackResult {
    data class Success(val job: ProductImportJob) : RollbackResult()
    data object JobNotFound : RollbackResult()
    data object JobNotCompleted : RollbackResult()
    data object AlreadyRolledBack : RollbackResult()
    data object NotMostRecentImport : RollbackResult()
}

/** One row resolved against a column [mapping] - either ready to persist or already known to be invalid. */
private sealed class ResolvedRow {
    data class Valid(
        val sku: String,
        val name: String,
        val price: Double,
        val description: String?,
        val taxCategory: String,
        val barcode: String?,
        val category: String?,
        val inStock: Boolean
    ) : ResolvedRow()
    data class Invalid(val errors: List<String>) : ResolvedRow()
}

/**
 * Bulk product catalog import: upload a CSV, map its columns onto [ProductImportField]s, dry-run
 * the mapping to see which rows would fail (with line numbers), then run the import as an async
 * job with per-row created/updated/errored reporting and one-shot rollback of the most recent
 * completed import. [importScope] mirrors WebhookService's `deliveryScope` pattern: null (most
 * unit tests) records the job but leaves it PENDING for [runImportNow] to drive synchronously; a
 * real [CoroutineScope] (the live application) runs the import in the background so the request
 * that started it returns immediately.
 */
class ProductImportService(
    private val productService: ProductService,
    private val importScope: CoroutineScope? = null,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val files = ConcurrentHashMap<String, ProductImportFile>()
    private val jobs = ConcurrentHashMap<String, ProductImportJob>()

    @Volatile
    private var lastCompletedJobId: String? = null

    fun uploadCsv(originalFileName: String, bytes: ByteArray): UploadCsvResult {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return UploadCsvResult.InvalidCsv("The uploaded file is empty")

        val records = try {
            CSVFormat.DEFAULT.parse(StringReader(text)).use { it.records }
        } catch (e: Exception) {
            return UploadCsvResult.InvalidCsv("Could not parse CSV: ${e.message}")
        }
        if (records.isEmpty()) return UploadCsvResult.InvalidCsv("The uploaded file has no rows")

        val headers = records.first().toList()
        val rows = records.drop(1).map { it.toList() }
        val file = ProductImportFile(originalFileName = originalFileName, headers = headers, rows = rows, uploadedAt = nowProvider())
        files[file.id] = file
        return UploadCsvResult.Success(file)
    }

    fun getFile(fileId: String): ProductImportFile? = files[fileId]

    fun dryRun(fileId: String, mapping: Map<ProductImportField, String>): DryRunResult {
        val file = files[fileId] ?: return DryRunResult.FileNotFound
        val mappingErrors = validateMapping(file, mapping)
        if (mappingErrors.isNotEmpty()) return DryRunResult.InvalidMapping(mappingErrors)

        val outcomes = resolveRows(file, mapping).map { (rowNumber, resolved) ->
            when (resolved) {
                is ResolvedRow.Invalid -> ImportRowOutcome(rowNumber, ImportRowAction.ERROR, errors = resolved.errors)
                is ResolvedRow.Valid -> {
                    val action = if (productService.getProductBySku(resolved.sku) == null) ImportRowAction.CREATED else ImportRowAction.UPDATED
                    ImportRowOutcome(rowNumber, action, sku = resolved.sku)
                }
            }
        }
        return DryRunResult.Success(outcomes)
    }

    fun startImport(fileId: String, mapping: Map<ProductImportField, String>, startedBy: String?): StartImportResult {
        val file = files[fileId] ?: return StartImportResult.FileNotFound
        val mappingErrors = validateMapping(file, mapping)
        if (mappingErrors.isNotEmpty()) return StartImportResult.InvalidMapping(mappingErrors)

        val job = ProductImportJob(
            fileId = fileId,
            mapping = mapping,
            totalRows = file.rows.size,
            startedBy = startedBy,
            createdAt = nowProvider()
        )
        jobs[job.id] = job
        AuditService.record(AuditEvent.PRODUCT_IMPORT_STARTED, userId = startedBy, detail = "fileId=$fileId totalRows=${file.rows.size}")
        importScope?.launch { runImportNow(job.id) }
        return StartImportResult.Success(job)
    }

    fun getJob(jobId: String): ProductImportJob? = jobs[jobId]

    /** Synchronously drives a PENDING job to completion - production runs this via [importScope], tests call it directly. */
    fun runImportNow(jobId: String) {
        val job = jobs[jobId] ?: return
        if (job.status != ImportJobStatus.PENDING) return
        val file = files[job.fileId] ?: run {
            jobs[jobId] = job.copy(status = ImportJobStatus.FAILED, error = "Import file no longer available", completedAt = nowProvider())
            return
        }

        jobs[jobId] = job.copy(status = ImportJobStatus.RUNNING)

        val outcomes = mutableListOf<ImportRowOutcome>()
        val rollbackEntries = mutableListOf<RollbackEntry>()
        var created = 0
        var updated = 0
        var errored = 0

        for ((rowNumber, resolved) in resolveRows(file, job.mapping)) {
            when (resolved) {
                is ResolvedRow.Invalid -> {
                    errored++
                    outcomes += ImportRowOutcome(rowNumber, ImportRowAction.ERROR, errors = resolved.errors)
                }
                is ResolvedRow.Valid -> {
                    val existing = productService.getProductBySku(resolved.sku)
                    if (existing == null) {
                        val result = productService.createProduct(resolved.toCreateRequest())
                        if (result is ProductResult.Created) {
                            created++
                            outcomes += ImportRowOutcome(rowNumber, ImportRowAction.CREATED, sku = resolved.sku)
                            rollbackEntries += RollbackEntry(sku = resolved.sku, productId = result.product.id, previousSnapshot = null)
                        } else {
                            errored++
                            outcomes += ImportRowOutcome(rowNumber, ImportRowAction.ERROR, sku = resolved.sku, errors = listOf("Could not create product"))
                        }
                    } else {
                        val result = productService.updateProduct(existing.id, resolved.toUpdateRequest())
                        if (result is ProductResult.Updated) {
                            updated++
                            outcomes += ImportRowOutcome(rowNumber, ImportRowAction.UPDATED, sku = resolved.sku)
                            rollbackEntries += RollbackEntry(sku = resolved.sku, productId = existing.id, previousSnapshot = existing)
                        } else {
                            errored++
                            outcomes += ImportRowOutcome(rowNumber, ImportRowAction.ERROR, sku = resolved.sku, errors = listOf("Could not update product"))
                        }
                    }
                }
            }
            jobs.computeIfPresent(jobId) { _, j -> j.copy(processedRows = outcomes.size) }
        }

        val completed = jobs.computeIfPresent(jobId) { _, j ->
            j.copy(
                status = ImportJobStatus.COMPLETED,
                processedRows = outcomes.size,
                createdCount = created,
                updatedCount = updated,
                erroredCount = errored,
                rowOutcomes = outcomes,
                rollbackEntries = rollbackEntries,
                completedAt = nowProvider()
            )
        }
        if (completed != null) {
            lastCompletedJobId = jobId
            AuditService.record(
                AuditEvent.PRODUCT_IMPORT_COMPLETED, userId = job.startedBy,
                detail = "jobId=$jobId created=$created updated=$updated errored=$errored"
            )
        }
    }

    fun rollback(jobId: String, actorUserId: String?): RollbackResult {
        val job = jobs[jobId] ?: return RollbackResult.JobNotFound
        if (job.status != ImportJobStatus.COMPLETED) return RollbackResult.JobNotCompleted
        if (job.rolledBack) return RollbackResult.AlreadyRolledBack
        if (jobId != lastCompletedJobId) return RollbackResult.NotMostRecentImport

        for (entry in job.rollbackEntries) {
            val snapshot = entry.previousSnapshot
            if (snapshot == null) {
                productService.deleteProduct(entry.productId)
            } else {
                productService.restoreProduct(snapshot)
            }
        }

        val rolledBackJob = jobs.computeIfPresent(jobId) { _, j -> j.copy(rolledBack = true) }!!
        AuditService.record(AuditEvent.PRODUCT_IMPORT_ROLLED_BACK, userId = actorUserId, detail = "jobId=$jobId rows=${job.rollbackEntries.size}")
        return RollbackResult.Success(rolledBackJob)
    }

    private fun validateMapping(file: ProductImportFile, mapping: Map<ProductImportField, String>): List<String> {
        val errors = mutableListOf<String>()
        for (required in listOf(ProductImportField.SKU, ProductImportField.NAME, ProductImportField.PRICE)) {
            val header = mapping[required]
            if (header == null) {
                errors += "${required.name} must be mapped to a column"
            } else if (header !in file.headers) {
                errors += "Mapped column '$header' for ${required.name} is not a column in this file"
            }
        }
        for ((field, header) in mapping) {
            if (field !in listOf(ProductImportField.SKU, ProductImportField.NAME, ProductImportField.PRICE) && header !in file.headers) {
                errors += "Mapped column '$header' for ${field.name} is not a column in this file"
            }
        }
        return errors
    }

    private fun resolveRows(file: ProductImportFile, mapping: Map<ProductImportField, String>): List<Pair<Int, ResolvedRow>> {
        val headerIndex = file.headers.withIndex().associate { (i, h) -> h to i }
        val seenSkuAtRow = mutableMapOf<String, Int>()

        fun valueOf(row: List<String>, field: ProductImportField): String? {
            val header = mapping[field] ?: return null
            val index = headerIndex[header] ?: return null
            return row.getOrNull(index)?.trim()
        }

        return file.rows.mapIndexed { index, row ->
            val rowNumber = index + 1
            val errors = mutableListOf<String>()

            val sku = valueOf(row, ProductImportField.SKU).orEmpty()
            if (sku.isBlank()) errors += "sku is required"

            val name = valueOf(row, ProductImportField.NAME).orEmpty()
            if (name.isBlank()) errors += "name is required"

            val priceRaw = valueOf(row, ProductImportField.PRICE).orEmpty()
            val price = priceRaw.toDoubleOrNull()
            if (price == null) {
                errors += "price '$priceRaw' is not a valid number"
            } else if (price < 0) {
                errors += "price must be non-negative"
            }

            val taxCategoryRaw = valueOf(row, ProductImportField.TAX_CATEGORY)?.takeIf { it.isNotBlank() }
            val taxCategory = taxCategoryRaw?.uppercase() ?: TaxCategory.STANDARD.name
            if (runCatching { TaxCategory.valueOf(taxCategory) }.isFailure) {
                errors += "taxCategory '$taxCategoryRaw' must be one of: ${TaxCategory.entries.joinToString()}"
            }

            val inStockRaw = valueOf(row, ProductImportField.IN_STOCK)?.takeIf { it.isNotBlank() }
            val inStock = when (inStockRaw?.lowercase()) {
                null -> true
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            if (inStockRaw != null && inStock == null) {
                errors += "inStock '$inStockRaw' must be true/false"
            }

            if (sku.isNotBlank()) {
                val firstRow = seenSkuAtRow[sku]
                if (firstRow != null) {
                    errors += "Duplicate SKU '$sku' also appears at row $firstRow in this file"
                } else {
                    seenSkuAtRow[sku] = rowNumber
                }
            }

            val resolved = if (errors.isNotEmpty()) {
                ResolvedRow.Invalid(errors)
            } else {
                ResolvedRow.Valid(
                    sku = sku,
                    name = name,
                    price = price!!,
                    description = valueOf(row, ProductImportField.DESCRIPTION)?.takeIf { it.isNotBlank() },
                    taxCategory = taxCategory,
                    barcode = valueOf(row, ProductImportField.BARCODE)?.takeIf { it.isNotBlank() },
                    category = valueOf(row, ProductImportField.CATEGORY)?.takeIf { it.isNotBlank() },
                    inStock = inStock ?: true
                )
            }
            rowNumber to resolved
        }
    }

    private fun ResolvedRow.Valid.toCreateRequest() = CreateProductRequest(
        sku = sku, name = name, description = description, price = price,
        taxCategory = taxCategory, barcode = barcode, category = category, inStock = inStock
    )

    private fun ResolvedRow.Valid.toUpdateRequest() = UpdateProductRequest(
        name = name, description = description, price = price,
        taxCategory = taxCategory, barcode = barcode, category = category, inStock = inStock
    )
}
