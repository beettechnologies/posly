package com.beettechnologies.posly.migration

import java.time.Instant
import java.util.UUID

/**
 * The order fields a CSV column can be mapped onto. Rows sharing the same [ORDER_REFERENCE] are
 * grouped into a single historical order (one row per line item, matching a typical legacy POS
 * export) - [STORE_ID], [SOLD_AT], [PAYMENT_METHOD], and the totals fields are read from each
 * group's first row and assumed consistent across the group. [SUBTOTAL] and [TAX_AMOUNT] are
 * optional (default to 0 / derived from [TOTAL_AMOUNT]); everything else is required.
 */
enum class SalesImportField {
    ORDER_REFERENCE, STORE_ID, SKU, QUANTITY, UNIT_PRICE, SOLD_AT, PAYMENT_METHOD, TOTAL_AMOUNT,
    SUBTOTAL, TAX_AMOUNT, PAYMENT_REFERENCE, SOLD_BY
}

enum class SalesImportJobStatus { PENDING, RUNNING, COMPLETED, FAILED }

/** Per-row classification during dry-run, deliberately named to match this ticket's acceptance criteria wording. */
enum class SalesRowResult { MATCHED, UNMATCHED }

/** What actually happened to an order group once the (async) import ran. */
enum class SalesGroupOutcome { IMPORTED, SKIPPED_UNMATCHED, SKIPPED_ALREADY_IMPORTED }

/** A CSV file staged for import - held in memory only long enough to dry-run and/or execute against. */
data class SalesImportFile(
    val id: String = UUID.randomUUID().toString(),
    val originalFileName: String,
    val headers: List<String>,
    /** Raw string cells in file order; index 0 is the first row after the header. */
    val rows: List<List<String>>,
    val uploadedAt: Instant = Instant.now()
)

data class SalesRowOutcome(
    /** 1-based, counting data rows only (the header is not row 1). */
    val rowNumber: Int,
    val orderReference: String,
    val result: SalesRowResult,
    val sku: String? = null,
    val errors: List<String> = emptyList()
)

/** One legacy order-reference group's dry-run preview - a group is only importable if every row in it is [SalesRowResult.MATCHED]. */
data class SalesOrderGroupPreview(
    val orderReference: String,
    val rowNumbers: List<Int>,
    val importable: Boolean,
    val itemCount: Int,
    val total: Double?,
    val errors: List<String>
)

data class SalesDryRunReport(val rowOutcomes: List<SalesRowOutcome>, val groups: List<SalesOrderGroupPreview>)

/** One row-mapping this run produced - the "sample record mappings" a reconciliation report shows for spot-checking. */
data class SalesImportedOrder(
    val orderReference: String,
    val orderId: String,
    val storeId: String,
    val total: Double,
    val itemCount: Int
)

data class SalesImportJob(
    val id: String = UUID.randomUUID().toString(),
    val fileId: String,
    val mapping: Map<SalesImportField, String>,
    val status: SalesImportJobStatus = SalesImportJobStatus.PENDING,
    val totalRows: Int,
    val totalGroups: Int,
    val processedGroups: Int = 0,
    val importedCount: Int = 0,
    val skippedUnmatchedCount: Int = 0,
    val skippedAlreadyImportedCount: Int = 0,
    val rowOutcomes: List<SalesRowOutcome> = emptyList(),
    val importedOrders: List<SalesImportedOrder> = emptyList(),
    val rolledBack: Boolean = false,
    val startedBy: String? = null,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val error: String? = null
)

/**
 * A distinct, fetchable verification artifact once a job completes - counts plus a sample of
 * legacy-reference -> created-order-id mappings, separate from the job's own summary since this
 * ticket's acceptance criteria calls out reconciliation as its own deliverable.
 */
data class SalesReconciliationReport(
    val jobId: String,
    val totalRowsProcessed: Int,
    val totalGroups: Int,
    val importedCount: Int,
    val skippedUnmatchedCount: Int,
    val skippedAlreadyImportedCount: Int,
    val sampleMappings: List<SalesImportedOrder>,
    val generatedAt: Instant
)
