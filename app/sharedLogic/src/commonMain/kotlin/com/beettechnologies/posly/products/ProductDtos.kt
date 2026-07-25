package com.beettechnologies.posly.products

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultItem(
    val id: String,
    val sku: String,
    val name: String,
    val price: Double,
    val category: String? = null,
    val inStock: Boolean = true,
    val barcode: String? = null
)

@Serializable
data class SearchResponse(
    val results: List<SearchResultItem>,
    val page: Int,
    val size: Int,
    val total: Int
)

@Serializable
data class ModifierResponse(
    val id: String,
    val name: String,
    val options: List<String>,
    val additionalCost: Double,
    val unavailableOptions: List<String> = emptyList()
)

@Serializable
data class ProductResponse(
    val id: String,
    val sku: String,
    val name: String,
    val description: String? = null,
    val price: Double,
    val taxCategory: String,
    val modifiers: List<ModifierResponse>,
    val imageUrls: List<String>,
    val barcode: String? = null,
    val category: String? = null,
    val inStock: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)
