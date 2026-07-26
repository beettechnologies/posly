package com.beettechnologies.posly.catalog

import com.beettechnologies.posly.products.Product
import java.time.Instant
import java.util.UUID

/** The product fields a CSV column can be mapped onto. [SKU], [NAME], and [PRICE] are required. */
enum class ProductImportField {
    SKU, NAME, PRICE, DESCRIPTION, TAX_CATEGORY, BARCODE, CATEGORY, IN_STOCK
}

enum class ImportJobStatus { PENDING, RUNNING, COMPLETED, FAILED }

enum class ImportRowAction { CREATED, UPDATED, ERROR }

/** A CSV file staged for import - held in memory only long enough to dry-run and/or execute against. */
data class ProductImportFile(
    val id: String = UUID.randomUUID().toString(),
    val originalFileName: String,
    val headers: List<String>,
    /** Raw string cells in file order; index 0 is the first row after the header. */
    val rows: List<List<String>>,
    val uploadedAt: Instant = Instant.now()
)

data class ImportRowOutcome(
    /** 1-based, counting data rows only (the header is not row 1). */
    val rowNumber: Int,
    val action: ImportRowAction,
    val sku: String? = null,
    val errors: List<String> = emptyList()
)

/**
 * Captures enough to undo one row's effect on the catalog. [previousSnapshot] null means the row
 * created [productId] (rollback deletes it); non-null is the pre-import product state (rollback
 * restores it verbatim via [com.beettechnologies.posly.products.ProductService.updateProduct]).
 */
data class RollbackEntry(
    val sku: String,
    val productId: String,
    val previousSnapshot: Product? = null
)

data class ProductImportJob(
    val id: String = UUID.randomUUID().toString(),
    val fileId: String,
    val mapping: Map<ProductImportField, String>,
    val status: ImportJobStatus = ImportJobStatus.PENDING,
    val totalRows: Int,
    val processedRows: Int = 0,
    val createdCount: Int = 0,
    val updatedCount: Int = 0,
    val erroredCount: Int = 0,
    val rowOutcomes: List<ImportRowOutcome> = emptyList(),
    val rollbackEntries: List<RollbackEntry> = emptyList(),
    val rolledBack: Boolean = false,
    val startedBy: String? = null,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val error: String? = null
)
