package com.beettechnologies.posly.products

import kotlinx.serialization.Serializable

@Serializable
data class ModifierRequest(
    val name: String,
    val options: List<String>,
    val additionalCost: Double = 0.0,
    val unavailableOptions: List<String> = emptyList()
)

@Serializable
data class CreateProductRequest(
    val sku: String,
    val name: String,
    val description: String? = null,
    val price: Double,
    val taxCategory: String = "STANDARD",
    val modifiers: List<ModifierRequest> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val barcode: String? = null,
    val category: String? = null,
    val inStock: Boolean = true
)

@Serializable
data class UpdateProductRequest(
    val name: String? = null,
    val description: String? = null,
    val price: Double? = null,
    val taxCategory: String? = null,
    val modifiers: List<ModifierRequest>? = null,
    val barcode: String? = null,
    val category: String? = null,
    val inStock: Boolean? = null
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

@Serializable
data class CreateProductResponse(val id: String)

@Serializable
data class ImageUploadResponse(val imageUrl: String)
