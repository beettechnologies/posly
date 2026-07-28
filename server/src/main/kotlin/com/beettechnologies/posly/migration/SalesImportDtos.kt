package com.beettechnologies.posly.migration

import kotlinx.serialization.Serializable

@Serializable
data class UploadSalesCsvResponse(
    val fileId: String,
    val headers: List<String>,
    /** First few data rows, so the mapping UI can show the user a preview alongside the header list. */
    val previewRows: List<List<String>>,
    val totalRows: Int
)

@Serializable
data class SalesImportMappingRequest(val mapping: Map<String, String>)

@Serializable
data class SalesRowOutcomeResponse(
    val rowNumber: Int,
    val orderReference: String,
    val result: String,
    val sku: String? = null,
    val errors: List<String> = emptyList()
)

@Serializable
data class SalesOrderGroupPreviewResponse(
    val orderReference: String,
    val rowNumbers: List<Int>,
    val importable: Boolean,
    val itemCount: Int,
    val total: Double?,
    val errors: List<String>
)

@Serializable
data class SalesDryRunResponse(val rowOutcomes: List<SalesRowOutcomeResponse>, val groups: List<SalesOrderGroupPreviewResponse>)

@Serializable
data class SalesImportedOrderResponse(
    val orderReference: String,
    val orderId: String,
    val storeId: String,
    val total: Double,
    val itemCount: Int
)

@Serializable
data class SalesImportJobResponse(
    val id: String,
    val fileId: String,
    val status: String,
    val totalRows: Int,
    val totalGroups: Int,
    val processedGroups: Int,
    val importedCount: Int,
    val skippedUnmatchedCount: Int,
    val skippedAlreadyImportedCount: Int,
    val rowOutcomes: List<SalesRowOutcomeResponse>,
    val importedOrders: List<SalesImportedOrderResponse>,
    val rolledBack: Boolean,
    val createdAt: String,
    val completedAt: String?,
    val error: String? = null
)

@Serializable
data class SalesReconciliationReportResponse(
    val jobId: String,
    val totalRowsProcessed: Int,
    val totalGroups: Int,
    val importedCount: Int,
    val skippedUnmatchedCount: Int,
    val skippedAlreadyImportedCount: Int,
    val sampleMappings: List<SalesImportedOrderResponse>,
    val generatedAt: String
)

fun SalesRowOutcome.toResponse() = SalesRowOutcomeResponse(
    rowNumber = rowNumber, orderReference = orderReference, result = result.name, sku = sku, errors = errors
)

fun SalesOrderGroupPreview.toResponse() = SalesOrderGroupPreviewResponse(
    orderReference = orderReference, rowNumbers = rowNumbers, importable = importable, itemCount = itemCount, total = total, errors = errors
)

fun SalesDryRunReport.toResponse() = SalesDryRunResponse(rowOutcomes.map { it.toResponse() }, groups.map { it.toResponse() })

fun SalesImportedOrder.toResponse() = SalesImportedOrderResponse(orderReference, orderId, storeId, total, itemCount)

fun SalesImportJob.toResponse() = SalesImportJobResponse(
    id = id,
    fileId = fileId,
    status = status.name,
    totalRows = totalRows,
    totalGroups = totalGroups,
    processedGroups = processedGroups,
    importedCount = importedCount,
    skippedUnmatchedCount = skippedUnmatchedCount,
    skippedAlreadyImportedCount = skippedAlreadyImportedCount,
    rowOutcomes = rowOutcomes.map { it.toResponse() },
    importedOrders = importedOrders.map { it.toResponse() },
    rolledBack = rolledBack,
    createdAt = createdAt.toString(),
    completedAt = completedAt?.toString(),
    error = error
)

fun SalesReconciliationReport.toResponse() = SalesReconciliationReportResponse(
    jobId = jobId,
    totalRowsProcessed = totalRowsProcessed,
    totalGroups = totalGroups,
    importedCount = importedCount,
    skippedUnmatchedCount = skippedUnmatchedCount,
    skippedAlreadyImportedCount = skippedAlreadyImportedCount,
    sampleMappings = sampleMappings.map { it.toResponse() },
    generatedAt = generatedAt.toString()
)
