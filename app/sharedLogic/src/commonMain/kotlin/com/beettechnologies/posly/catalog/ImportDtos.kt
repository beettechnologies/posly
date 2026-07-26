package com.beettechnologies.posly.catalog

import kotlinx.serialization.Serializable

/** Mirrors the server's `ProductImportField` enum names - the mapping sent to the server keys on these exact strings. */
val PRODUCT_IMPORT_FIELDS = listOf("SKU", "NAME", "PRICE", "DESCRIPTION", "TAX_CATEGORY", "BARCODE", "CATEGORY", "IN_STOCK")
val REQUIRED_PRODUCT_IMPORT_FIELDS = setOf("SKU", "NAME", "PRICE")

@Serializable
data class UploadCsvResponse(
    val fileId: String,
    val headers: List<String>,
    val previewRows: List<List<String>>,
    val totalRows: Int
)

@Serializable
data class ImportMappingRequest(val mapping: Map<String, String>)

@Serializable
data class ImportRowOutcomeResponse(
    val rowNumber: Int,
    val action: String,
    val sku: String? = null,
    val errors: List<String> = emptyList()
)

@Serializable
data class DryRunResponse(val outcomes: List<ImportRowOutcomeResponse>)

@Serializable
data class ImportJobResponse(
    val id: String,
    val fileId: String,
    val status: String,
    val totalRows: Int,
    val processedRows: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val erroredCount: Int,
    val rowOutcomes: List<ImportRowOutcomeResponse>,
    val rolledBack: Boolean,
    val createdAt: String,
    val completedAt: String?,
    val error: String? = null
)
