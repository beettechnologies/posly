package com.beettechnologies.posly.migration

import kotlinx.serialization.Serializable

/** Mirrors the server's `SalesImportField` enum names - the mapping sent to the server keys on these exact strings. */
val SALES_IMPORT_FIELDS = listOf(
    "ORDER_REFERENCE", "STORE_ID", "SKU", "QUANTITY", "UNIT_PRICE", "SOLD_AT", "PAYMENT_METHOD", "TOTAL_AMOUNT",
    "SUBTOTAL", "TAX_AMOUNT", "PAYMENT_REFERENCE", "SOLD_BY"
)
val REQUIRED_SALES_IMPORT_FIELDS = setOf(
    "ORDER_REFERENCE", "STORE_ID", "SKU", "QUANTITY", "UNIT_PRICE", "SOLD_AT", "PAYMENT_METHOD", "TOTAL_AMOUNT"
)

@Serializable
data class UploadSalesCsvResponse(
    val fileId: String,
    val headers: List<String>,
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
