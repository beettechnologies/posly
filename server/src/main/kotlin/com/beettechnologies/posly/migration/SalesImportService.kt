package com.beettechnologies.posly.migration

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.cart.Cart
import com.beettechnologies.posly.cart.CartItem
import com.beettechnologies.posly.cart.CartStatus
import com.beettechnologies.posly.cart.CartTotals
import com.beettechnologies.posly.cart.ConfirmPaymentResult
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.TaxBreakdownLine
import com.beettechnologies.posly.cart.roundCents
import com.beettechnologies.posly.products.ProductService
import com.beettechnologies.posly.stores.StoreService
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.apache.commons.csv.CSVFormat

sealed class UploadSalesCsvResult {
    data class Success(val file: SalesImportFile) : UploadSalesCsvResult()
    data class InvalidCsv(val message: String) : UploadSalesCsvResult()
}

sealed class SalesDryRunResult {
    data class Success(val report: SalesDryRunReport) : SalesDryRunResult()
    data object FileNotFound : SalesDryRunResult()
    data class InvalidMapping(val errors: List<String>) : SalesDryRunResult()
}

sealed class StartSalesImportResult {
    data class Success(val job: SalesImportJob) : StartSalesImportResult()
    data object FileNotFound : StartSalesImportResult()
    data class InvalidMapping(val errors: List<String>) : StartSalesImportResult()
}

sealed class SalesReconciliationResult {
    data class Success(val report: SalesReconciliationReport) : SalesReconciliationResult()
    data object JobNotFound : SalesReconciliationResult()
    data object JobNotCompleted : SalesReconciliationResult()
}

sealed class SalesRollbackResult {
    data class Success(val job: SalesImportJob) : SalesRollbackResult()
    data object JobNotFound : SalesRollbackResult()
    data object JobNotCompleted : SalesRollbackResult()
    data object AlreadyRolledBack : SalesRollbackResult()
    data object NotMostRecentImport : SalesRollbackResult()
}

private val REQUIRED_FIELDS = listOf(
    SalesImportField.ORDER_REFERENCE, SalesImportField.STORE_ID, SalesImportField.SKU,
    SalesImportField.QUANTITY, SalesImportField.UNIT_PRICE, SalesImportField.SOLD_AT,
    SalesImportField.PAYMENT_METHOD, SalesImportField.TOTAL_AMOUNT
)

/** One CSV row's line-item fields, resolved against the current catalog. */
private data class LineItemRow(
    val rowNumber: Int,
    val orderReference: String,
    val errors: List<String>,
    val productId: String? = null,
    val productName: String? = null,
    val taxCategory: com.beettechnologies.posly.products.TaxCategory? = null,
    val sku: String? = null,
    val quantity: Int? = null,
    val unitPrice: Double? = null
)

/** An order group's shared fields, resolved once from the group's first row. */
private data class GroupHeader(
    val errors: List<String>,
    val storeId: String? = null,
    val soldAt: Instant? = null,
    val paymentMethod: String? = null,
    val paymentReference: String? = null,
    val soldBy: String? = null,
    val totalAmount: Double? = null,
    val subtotal: Double? = null,
    val taxAmount: Double? = null
)

private data class ResolvedGroup(
    val orderReference: String,
    val rowNumbers: List<Int>,
    val lineItems: List<LineItemRow>,
    val header: GroupHeader
) {
    val importable: Boolean get() = header.errors.isEmpty() && lineItems.all { it.errors.isEmpty() }
    val allErrors: List<String> get() = (header.errors + lineItems.flatMap { it.errors }).distinct()
}

/**
 * Historical sales/order import: upload a CSV, map its columns onto [SalesImportField]s (rows
 * sharing an [SalesImportField.ORDER_REFERENCE] are grouped into one order, mirroring a typical
 * legacy POS export of one row per line item), dry-run the mapping for a matched/unmatched row
 * preview, then run the import as an async job that creates real [com.beettechnologies.posly.cart.Order]s
 * and produces a distinct reconciliation report. Unlike [com.beettechnologies.posly.sync.OfflineSyncService]
 * (which recomputes totals from the current catalog since an offline gap is short), this trusts the
 * legacy system's own subtotal/tax/total verbatim - recomputing tax with *today's* rates would
 * misrepresent what a customer was actually charged years ago. [importScope] mirrors
 * ProductImportService's pattern: null (most unit tests) leaves a started job PENDING for
 * [runImportNow] to drive synchronously; a real [CoroutineScope] runs it in the background.
 */
class SalesImportService(
    private val productService: ProductService,
    private val storeService: StoreService,
    private val orderService: OrderService,
    private val importScope: CoroutineScope? = null,
    private val nowProvider: () -> Instant = { Instant.now() }
) {
    private val files = ConcurrentHashMap<String, SalesImportFile>()
    private val jobs = ConcurrentHashMap<String, SalesImportJob>()

    @Volatile
    private var lastCompletedJobId: String? = null

    fun uploadCsv(originalFileName: String, bytes: ByteArray): UploadSalesCsvResult {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return UploadSalesCsvResult.InvalidCsv("The uploaded file is empty")

        val records = try {
            CSVFormat.DEFAULT.parse(StringReader(text)).use { it.records }
        } catch (e: Exception) {
            return UploadSalesCsvResult.InvalidCsv("Could not parse CSV: ${e.message}")
        }
        if (records.isEmpty()) return UploadSalesCsvResult.InvalidCsv("The uploaded file has no rows")

        val headers = records.first().toList()
        val rows = records.drop(1).map { it.toList() }
        val file = SalesImportFile(originalFileName = originalFileName, headers = headers, rows = rows, uploadedAt = nowProvider())
        files[file.id] = file
        return UploadSalesCsvResult.Success(file)
    }

    fun getFile(fileId: String): SalesImportFile? = files[fileId]

    fun dryRun(fileId: String, mapping: Map<SalesImportField, String>): SalesDryRunResult {
        val file = files[fileId] ?: return SalesDryRunResult.FileNotFound
        val mappingErrors = validateMapping(file, mapping)
        if (mappingErrors.isNotEmpty()) return SalesDryRunResult.InvalidMapping(mappingErrors)

        val groups = resolveGroups(file, mapping)
        val rowOutcomes = groups.flatMap { group ->
            group.lineItems.map { line ->
                val combinedErrors = (line.errors + group.header.errors).distinct()
                SalesRowOutcome(
                    rowNumber = line.rowNumber,
                    orderReference = group.orderReference,
                    result = if (combinedErrors.isEmpty()) SalesRowResult.MATCHED else SalesRowResult.UNMATCHED,
                    sku = line.sku,
                    errors = combinedErrors
                )
            }
        }.sortedBy { it.rowNumber }

        val groupPreviews = groups.map { group ->
            SalesOrderGroupPreview(
                orderReference = group.orderReference,
                rowNumbers = group.rowNumbers,
                importable = group.importable,
                itemCount = group.lineItems.size,
                total = group.header.totalAmount,
                errors = group.allErrors
            )
        }
        return SalesDryRunResult.Success(SalesDryRunReport(rowOutcomes, groupPreviews))
    }

    fun startImport(fileId: String, mapping: Map<SalesImportField, String>, startedBy: String?): StartSalesImportResult {
        val file = files[fileId] ?: return StartSalesImportResult.FileNotFound
        val mappingErrors = validateMapping(file, mapping)
        if (mappingErrors.isNotEmpty()) return StartSalesImportResult.InvalidMapping(mappingErrors)

        val groups = resolveGroups(file, mapping)
        val job = SalesImportJob(
            fileId = fileId,
            mapping = mapping,
            totalRows = file.rows.size,
            totalGroups = groups.size,
            startedBy = startedBy,
            createdAt = nowProvider()
        )
        jobs[job.id] = job
        AuditService.record(AuditEvent.SALES_IMPORT_STARTED, userId = startedBy, detail = "fileId=$fileId totalRows=${file.rows.size} totalGroups=${groups.size}")
        importScope?.launch { runImportNow(job.id) }
        return StartSalesImportResult.Success(job)
    }

    fun getJob(jobId: String): SalesImportJob? = jobs[jobId]

    /** Synchronously drives a PENDING job to completion - production runs this via [importScope], tests call it directly. */
    fun runImportNow(jobId: String) {
        val job = jobs[jobId] ?: return
        if (job.status != SalesImportJobStatus.PENDING) return
        val file = files[job.fileId] ?: run {
            jobs[jobId] = job.copy(status = SalesImportJobStatus.FAILED, error = "Import file no longer available", completedAt = nowProvider())
            return
        }

        jobs[jobId] = job.copy(status = SalesImportJobStatus.RUNNING)

        val groups = resolveGroups(file, job.mapping)
        val rowOutcomes = mutableListOf<SalesRowOutcome>()
        val importedOrders = mutableListOf<SalesImportedOrder>()
        var imported = 0
        var skippedUnmatched = 0
        var skippedAlreadyImported = 0

        for (group in groups) {
            val combinedErrors = group.allErrors
            for (line in group.lineItems) {
                val lineErrors = (line.errors + group.header.errors).distinct()
                rowOutcomes += SalesRowOutcome(
                    rowNumber = line.rowNumber,
                    orderReference = group.orderReference,
                    result = if (lineErrors.isEmpty()) SalesRowResult.MATCHED else SalesRowResult.UNMATCHED,
                    sku = line.sku,
                    errors = lineErrors
                )
            }

            if (!group.importable) {
                skippedUnmatched++
                jobs.computeIfPresent(jobId) { _, j -> j.copy(processedGroups = j.processedGroups + 1, rowOutcomes = rowOutcomes.toList()) }
                continue
            }

            val header = group.header
            val storeId = requireNotNull(header.storeId) { "storeId missing despite group being importable" }
            val soldAt = requireNotNull(header.soldAt) { "soldAt missing despite group being importable" }
            val totalAmount = requireNotNull(header.totalAmount) { "totalAmount missing despite group being importable" }
            val idempotencyKey = "legacy-sale:$storeId:${group.orderReference}"

            val existingOrder = orderService.getOrderByIdempotencyKey(idempotencyKey)
            if (existingOrder != null) {
                skippedAlreadyImported++
                importedOrders += SalesImportedOrder(group.orderReference, existingOrder.id, storeId, totalAmount, group.lineItems.size)
                jobs.computeIfPresent(jobId) { _, j -> j.copy(processedGroups = j.processedGroups + 1, rowOutcomes = rowOutcomes.toList()) }
                continue
            }

            val cartItems = group.lineItems.map { line ->
                CartItem(
                    productId = requireNotNull(line.productId),
                    productName = requireNotNull(line.productName),
                    quantity = requireNotNull(line.quantity),
                    unitPrice = requireNotNull(line.unitPrice),
                    taxCategory = requireNotNull(line.taxCategory)
                )
            }
            val subtotal = header.subtotal ?: roundCents(cartItems.sumOf { it.lineTotal })
            val taxAmount = header.taxAmount ?: 0.0
            val cart = Cart(
                id = java.util.UUID.randomUUID().toString(),
                storeId = storeId,
                createdBy = header.soldBy,
                items = cartItems,
                discount = null,
                status = CartStatus.CHECKED_OUT,
                checkoutIdempotencyKey = idempotencyKey,
                createdAt = soldAt,
                updatedAt = soldAt
            )
            val totals = CartTotals(
                subtotal = subtotal,
                itemDiscountTotal = 0.0,
                cartDiscountAmount = 0.0,
                taxableAmount = subtotal,
                taxBreakdown = if (taxAmount > 0) listOf(TaxBreakdownLine("Historical (as imported)", 0.0, taxAmount)) else emptyList(),
                totalTax = taxAmount,
                total = totalAmount
            )
            val order = orderService.createOrder(cart, totals, idempotencyKey, checkedOutAt = soldAt, currency = "USD")
            if (totalAmount > 0) {
                val paymentResult = orderService.confirmPayment(
                    orderId = order.id,
                    method = requireNotNull(header.paymentMethod),
                    amount = totalAmount,
                    reference = header.paymentReference,
                    actorId = header.soldBy
                )
                if (paymentResult !is ConfirmPaymentResult.Success) {
                    orderService.deleteOrder(order.id)
                    skippedUnmatched++
                    jobs.computeIfPresent(jobId) { _, j -> j.copy(processedGroups = j.processedGroups + 1, rowOutcomes = rowOutcomes.toList()) }
                    continue
                }
            }

            imported++
            importedOrders += SalesImportedOrder(group.orderReference, order.id, storeId, totalAmount, group.lineItems.size)
            jobs.computeIfPresent(jobId) { _, j -> j.copy(processedGroups = j.processedGroups + 1, rowOutcomes = rowOutcomes.toList()) }
        }

        val completed = jobs.computeIfPresent(jobId) { _, j ->
            j.copy(
                status = SalesImportJobStatus.COMPLETED,
                processedGroups = groups.size,
                importedCount = imported,
                skippedUnmatchedCount = skippedUnmatched,
                skippedAlreadyImportedCount = skippedAlreadyImported,
                rowOutcomes = rowOutcomes.sortedBy { it.rowNumber },
                importedOrders = importedOrders,
                completedAt = nowProvider()
            )
        }
        if (completed != null) {
            lastCompletedJobId = jobId
            AuditService.record(
                AuditEvent.SALES_IMPORT_COMPLETED, userId = job.startedBy,
                detail = "jobId=$jobId imported=$imported skippedUnmatched=$skippedUnmatched skippedAlreadyImported=$skippedAlreadyImported"
            )
        }
    }

    fun getReconciliationReport(jobId: String): SalesReconciliationResult {
        val job = jobs[jobId] ?: return SalesReconciliationResult.JobNotFound
        if (job.status != SalesImportJobStatus.COMPLETED) return SalesReconciliationResult.JobNotCompleted
        return SalesReconciliationResult.Success(
            SalesReconciliationReport(
                jobId = job.id,
                totalRowsProcessed = job.rowOutcomes.size,
                totalGroups = job.totalGroups,
                importedCount = job.importedCount,
                skippedUnmatchedCount = job.skippedUnmatchedCount,
                skippedAlreadyImportedCount = job.skippedAlreadyImportedCount,
                sampleMappings = job.importedOrders.take(20),
                generatedAt = nowProvider()
            )
        )
    }

    fun rollback(jobId: String, actorUserId: String?): SalesRollbackResult {
        val job = jobs[jobId] ?: return SalesRollbackResult.JobNotFound
        if (job.status != SalesImportJobStatus.COMPLETED) return SalesRollbackResult.JobNotCompleted
        if (job.rolledBack) return SalesRollbackResult.AlreadyRolledBack
        if (jobId != lastCompletedJobId) return SalesRollbackResult.NotMostRecentImport

        for (imported in job.importedOrders) {
            orderService.deleteOrder(imported.orderId)
        }

        val rolledBackJob = jobs.computeIfPresent(jobId) { _, j -> j.copy(rolledBack = true) }!!
        AuditService.record(AuditEvent.SALES_IMPORT_ROLLED_BACK, userId = actorUserId, detail = "jobId=$jobId orders=${job.importedOrders.size}")
        return SalesRollbackResult.Success(rolledBackJob)
    }

    private fun validateMapping(file: SalesImportFile, mapping: Map<SalesImportField, String>): List<String> {
        val errors = mutableListOf<String>()
        for (required in REQUIRED_FIELDS) {
            val header = mapping[required]
            if (header == null) {
                errors += "${required.name} must be mapped to a column"
            } else if (header !in file.headers) {
                errors += "Mapped column '$header' for ${required.name} is not a column in this file"
            }
        }
        for ((field, header) in mapping) {
            if (field !in REQUIRED_FIELDS && header !in file.headers) {
                errors += "Mapped column '$header' for ${field.name} is not a column in this file"
            }
        }
        return errors
    }

    private fun resolveGroups(file: SalesImportFile, mapping: Map<SalesImportField, String>): List<ResolvedGroup> {
        val headerIndex = file.headers.withIndex().associate { (i, h) -> h to i }

        fun valueOf(row: List<String>, field: SalesImportField): String? {
            val header = mapping[field] ?: return null
            val index = headerIndex[header] ?: return null
            return row.getOrNull(index)?.trim()
        }

        val lineItems = file.rows.mapIndexed { index, row ->
            val rowNumber = index + 1
            val orderReference = valueOf(row, SalesImportField.ORDER_REFERENCE).orEmpty()
            val errors = mutableListOf<String>()
            if (orderReference.isBlank()) errors += "orderReference is required"

            val sku = valueOf(row, SalesImportField.SKU).orEmpty()
            var productId: String? = null
            var productName: String? = null
            var taxCategory: com.beettechnologies.posly.products.TaxCategory? = null
            if (sku.isBlank()) {
                errors += "sku is required"
            } else {
                val product = productService.getProductBySku(sku)
                if (product == null) {
                    errors += "sku '$sku' does not match any product"
                } else {
                    productId = product.id
                    productName = product.name
                    taxCategory = product.taxCategory
                }
            }

            val quantityRaw = valueOf(row, SalesImportField.QUANTITY).orEmpty()
            val quantity = quantityRaw.toIntOrNull()
            if (quantity == null) errors += "quantity '$quantityRaw' is not a valid integer"
            else if (quantity <= 0) errors += "quantity must be positive"

            val unitPriceRaw = valueOf(row, SalesImportField.UNIT_PRICE).orEmpty()
            val unitPrice = unitPriceRaw.toDoubleOrNull()
            if (unitPrice == null) errors += "unitPrice '$unitPriceRaw' is not a valid number"
            else if (unitPrice < 0) errors += "unitPrice must be non-negative"

            LineItemRow(
                rowNumber = rowNumber,
                orderReference = orderReference.ifBlank { "__missing_reference_row_$rowNumber" },
                errors = errors,
                productId = productId,
                productName = productName,
                taxCategory = taxCategory,
                sku = sku.ifBlank { null },
                quantity = quantity,
                unitPrice = unitPrice
            )
        }

        val grouped = LinkedHashMap<String, MutableList<LineItemRow>>()
        for (line in lineItems) grouped.getOrPut(line.orderReference) { mutableListOf() }.add(line)

        return grouped.map { (orderReference, lines) ->
            val firstRowIndex = lines.first().rowNumber - 1
            val firstRow = file.rows[firstRowIndex]
            val header = resolveGroupHeader(firstRow, mapping, valueOf = { row, field -> valueOf(row, field) })
            ResolvedGroup(orderReference = orderReference, rowNumbers = lines.map { it.rowNumber }, lineItems = lines, header = header)
        }
    }

    private fun resolveGroupHeader(
        firstRow: List<String>,
        mapping: Map<SalesImportField, String>,
        valueOf: (List<String>, SalesImportField) -> String?
    ): GroupHeader {
        val errors = mutableListOf<String>()

        val storeIdRaw = valueOf(firstRow, SalesImportField.STORE_ID).orEmpty()
        var storeId: String? = null
        if (storeIdRaw.isBlank()) {
            errors += "storeId is required"
        } else if (storeService.getStore(storeIdRaw) == null) {
            errors += "storeId '$storeIdRaw' does not match any store"
        } else {
            storeId = storeIdRaw
        }

        val soldAtRaw = valueOf(firstRow, SalesImportField.SOLD_AT).orEmpty()
        val soldAt = if (soldAtRaw.isBlank()) {
            errors += "soldAt is required"
            null
        } else {
            try {
                Instant.parse(soldAtRaw)
            } catch (e: DateTimeParseException) {
                errors += "soldAt '$soldAtRaw' is not a valid ISO-8601 instant"
                null
            }
        }

        val paymentMethod = valueOf(firstRow, SalesImportField.PAYMENT_METHOD)?.takeIf { it.isNotBlank() }
        if (paymentMethod == null) errors += "paymentMethod is required"

        val totalAmountRaw = valueOf(firstRow, SalesImportField.TOTAL_AMOUNT).orEmpty()
        val totalAmount = totalAmountRaw.toDoubleOrNull()
        if (totalAmount == null) errors += "totalAmount '$totalAmountRaw' is not a valid number"
        else if (totalAmount < 0) errors += "totalAmount must be non-negative"

        val subtotalRaw = valueOf(firstRow, SalesImportField.SUBTOTAL)?.takeIf { it.isNotBlank() }
        val subtotal = subtotalRaw?.toDoubleOrNull()
        if (subtotalRaw != null && subtotal == null) errors += "subtotal '$subtotalRaw' is not a valid number"

        val taxAmountRaw = valueOf(firstRow, SalesImportField.TAX_AMOUNT)?.takeIf { it.isNotBlank() }
        val taxAmount = taxAmountRaw?.toDoubleOrNull()
        if (taxAmountRaw != null && taxAmount == null) errors += "taxAmount '$taxAmountRaw' is not a valid number"

        return GroupHeader(
            errors = errors,
            storeId = storeId,
            soldAt = soldAt,
            paymentMethod = paymentMethod,
            paymentReference = valueOf(firstRow, SalesImportField.PAYMENT_REFERENCE)?.takeIf { it.isNotBlank() },
            soldBy = valueOf(firstRow, SalesImportField.SOLD_BY)?.takeIf { it.isNotBlank() },
            totalAmount = totalAmount,
            subtotal = subtotal,
            taxAmount = taxAmount
        )
    }
}
