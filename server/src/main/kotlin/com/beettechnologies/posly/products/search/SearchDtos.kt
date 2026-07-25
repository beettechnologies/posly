package com.beettechnologies.posly.products.search

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
