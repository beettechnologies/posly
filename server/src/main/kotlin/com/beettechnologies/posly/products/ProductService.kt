package com.beettechnologies.posly.products

import com.beettechnologies.posly.products.search.ProductSearchIndex
import com.beettechnologies.posly.products.search.SearchCache
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class ProductResult {
    data class Created(val product: Product) : ProductResult()
    data class Updated(val product: Product) : ProductResult()
    data object DuplicateSku : ProductResult()
    data object NotFound : ProductResult()
}

data class ProductSearchPage(val items: List<Product>, val page: Int, val size: Int, val total: Int)

class ProductService {

    private val products = ConcurrentHashMap<String, Product>() // id -> Product
    private val skuIndex = ConcurrentHashMap<String, String>()   // sku -> id
    private val imageStore = ConcurrentHashMap<String, ByteArray>() // imageId -> bytes
    private val searchIndex = ProductSearchIndex()
    private val searchCache = SearchCache()

    fun createProduct(req: CreateProductRequest): ProductResult {
        val taxCategory = runCatching { TaxCategory.valueOf(req.taxCategory) }.getOrNull()
            ?: return ProductResult.NotFound // reuse NotFound to signal invalid input handled upstream

        if (skuIndex.containsKey(req.sku)) {
            return ProductResult.DuplicateSku
        }

        val modifiers = req.modifiers.map { m ->
            ProductModifier(name = m.name, options = m.options, additionalCost = m.additionalCost, unavailableOptions = m.unavailableOptions)
        }

        val product = Product(
            sku = req.sku,
            name = req.name,
            description = req.description,
            price = req.price,
            taxCategory = taxCategory,
            modifiers = modifiers,
            imageUrls = req.imageUrls.toList(),
            barcode = req.barcode,
            category = req.category,
            inStock = req.inStock
        )

        products[product.id] = product
        skuIndex[product.sku] = product.id
        reindex(product)
        return ProductResult.Created(product)
    }

    fun getProduct(id: String): Product? = products[id]

    fun getProductBySku(sku: String): Product? = skuIndex[sku]?.let { products[it] }

    fun listProducts(): List<Product> = products.values.toList()

    fun updateProduct(id: String, req: UpdateProductRequest): ProductResult {
        val existing = products[id] ?: return ProductResult.NotFound

        val taxCategory = if (req.taxCategory != null) {
            runCatching { TaxCategory.valueOf(req.taxCategory) }.getOrNull()
                ?: return ProductResult.NotFound
        } else {
            existing.taxCategory
        }

        val modifiers = if (req.modifiers != null) {
            req.modifiers.map { m ->
                ProductModifier(name = m.name, options = m.options, additionalCost = m.additionalCost, unavailableOptions = m.unavailableOptions)
            }
        } else {
            existing.modifiers
        }

        val updated = existing.copy(
            name = req.name ?: existing.name,
            description = req.description ?: existing.description,
            price = req.price ?: existing.price,
            taxCategory = taxCategory,
            modifiers = modifiers,
            barcode = req.barcode ?: existing.barcode,
            category = req.category ?: existing.category,
            inStock = req.inStock ?: existing.inStock,
            updatedAt = System.currentTimeMillis()
        )

        products[id] = updated
        reindex(updated)
        return ProductResult.Updated(updated)
    }

    fun deleteProduct(id: String): Boolean {
        val product = products.remove(id) ?: return false
        skuIndex.remove(product.sku)
        searchIndex.remove(id)
        searchCache.invalidateAll()
        return true
    }

    fun uploadImage(productId: String, fileName: String, bytes: ByteArray): String? {
        val product = products[productId] ?: return null
        val imageId = UUID.randomUUID().toString()
        imageStore[imageId] = bytes
        val imageUrl = "/products/$productId/images/$imageId"
        val updated = product.copy(
            imageUrls = product.imageUrls + imageUrl,
            updatedAt = System.currentTimeMillis()
        )
        products[productId] = updated
        reindex(updated)
        return imageUrl
    }

    fun search(
        query: String?,
        barcode: String?,
        category: String?,
        inStock: Boolean?,
        page: Int,
        size: Int
    ): ProductSearchPage {
        val cacheKey = listOf(query.orEmpty(), barcode.orEmpty(), category.orEmpty(), inStock?.toString().orEmpty(), page, size)
            .joinToString("|")
        val result = searchCache.getOrCompute(cacheKey) {
            searchIndex.search(query, barcode, category, inStock, page, size)
        }
        val items = result.ids.mapNotNull { products[it] }
        return ProductSearchPage(items = items, page = page, size = size, total = result.total)
    }

    private fun reindex(product: Product) {
        searchIndex.index(product)
        searchCache.invalidateAll()
    }
}
