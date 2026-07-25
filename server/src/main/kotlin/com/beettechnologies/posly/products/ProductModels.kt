package com.beettechnologies.posly.products

import java.util.UUID

enum class TaxCategory {
    STANDARD, REDUCED, ZERO, EXEMPT
}

data class ProductModifier(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val options: List<String>,
    val additionalCost: Double = 0.0
)

data class Product(
    val id: String = UUID.randomUUID().toString(),
    val sku: String,
    val name: String,
    val description: String? = null,
    val price: Double,
    val taxCategory: TaxCategory,
    val modifiers: List<ProductModifier> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val barcode: String? = null,
    val category: String? = null,
    val inStock: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
