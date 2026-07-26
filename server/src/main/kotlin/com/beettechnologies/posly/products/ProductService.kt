package com.beettechnologies.posly.products

import com.beettechnologies.posly.db.ProductImagesTable
import com.beettechnologies.posly.db.ProductsTable
import com.beettechnologies.posly.products.search.ProductSearchIndex
import com.beettechnologies.posly.products.search.SearchCache
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

sealed class ProductResult {
    data class Created(val product: Product) : ProductResult()
    data class Updated(val product: Product) : ProductResult()
    data object DuplicateSku : ProductResult()
    data object NotFound : ProductResult()
}

data class ProductSearchPage(val items: List<Product>, val page: Int, val size: Int, val total: Int)

private fun rowToProduct(row: ResultRow) = Product(
    id = row[ProductsTable.id],
    sku = row[ProductsTable.sku],
    name = row[ProductsTable.name],
    description = row[ProductsTable.description],
    price = row[ProductsTable.price],
    taxCategory = TaxCategory.valueOf(row[ProductsTable.taxCategory]),
    modifiers = row[ProductsTable.modifiers],
    imageUrls = row[ProductsTable.imageUrls],
    barcode = row[ProductsTable.barcode],
    category = row[ProductsTable.category],
    inStock = row[ProductsTable.inStock],
    createdAt = row[ProductsTable.createdAt],
    updatedAt = row[ProductsTable.updatedAt]
)

class ProductService {

    private val searchIndex = ProductSearchIndex()
    private val searchCache = SearchCache()

    init {
        // Rebuilds the (in-memory, rebuildable) search index from whatever products already
        // persisted from a previous run - the index itself isn't part of the durable core data.
        transaction { ProductsTable.selectAll().map { rowToProduct(it) } }.forEach { searchIndex.index(it) }
    }

    fun createProduct(req: CreateProductRequest): ProductResult {
        val taxCategory = runCatching { TaxCategory.valueOf(req.taxCategory) }.getOrNull()
            ?: return ProductResult.NotFound // reuse NotFound to signal invalid input handled upstream

        return transaction {
            if (!ProductsTable.selectAll().where { ProductsTable.sku eq req.sku }.empty()) {
                return@transaction ProductResult.DuplicateSku
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

            ProductsTable.insert {
                it[ProductsTable.id] = product.id
                it[ProductsTable.sku] = product.sku
                it[ProductsTable.name] = product.name
                it[ProductsTable.description] = product.description
                it[ProductsTable.price] = product.price
                it[ProductsTable.taxCategory] = product.taxCategory.name
                it[ProductsTable.modifiers] = product.modifiers
                it[ProductsTable.imageUrls] = product.imageUrls
                it[ProductsTable.barcode] = product.barcode
                it[ProductsTable.category] = product.category
                it[ProductsTable.inStock] = product.inStock
                it[ProductsTable.createdAt] = product.createdAt
                it[ProductsTable.updatedAt] = product.updatedAt
            }
            reindex(product)
            ProductResult.Created(product)
        }
    }

    fun getProduct(id: String): Product? = transaction {
        ProductsTable.selectAll().where { ProductsTable.id eq id }.singleOrNull()?.let { rowToProduct(it) }
    }

    fun getProductBySku(sku: String): Product? = transaction {
        ProductsTable.selectAll().where { ProductsTable.sku eq sku }.singleOrNull()?.let { rowToProduct(it) }
    }

    fun listProducts(): List<Product> = transaction {
        ProductsTable.selectAll().map { rowToProduct(it) }
    }

    fun updateProduct(id: String, req: UpdateProductRequest): ProductResult = transaction {
        val existing = ProductsTable.selectAll().where { ProductsTable.id eq id }.singleOrNull()?.let { rowToProduct(it) }
            ?: return@transaction ProductResult.NotFound

        val taxCategory = if (req.taxCategory != null) {
            runCatching { TaxCategory.valueOf(req.taxCategory) }.getOrNull()
                ?: return@transaction ProductResult.NotFound
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

        persistUpdate(updated)
        reindex(updated)
        ProductResult.Updated(updated)
    }

    /**
     * Full replacement used only for import rollback - unlike [updateProduct], every field of
     * [snapshot] is applied verbatim, including nulls. [updateProduct]'s partial-update semantics
     * (`null` means "leave unchanged") can't express "clear this field back to null," which a
     * rollback sometimes needs to do.
     */
    fun restoreProduct(snapshot: Product): ProductResult = transaction {
        val exists = !ProductsTable.selectAll().where { ProductsTable.id eq snapshot.id }.empty()
        if (!exists) return@transaction ProductResult.NotFound

        val restored = snapshot.copy(updatedAt = System.currentTimeMillis())
        persistUpdate(restored)
        reindex(restored)
        ProductResult.Updated(restored)
    }

    fun deleteProduct(id: String): Boolean = transaction {
        val product = ProductsTable.selectAll().where { ProductsTable.id eq id }.singleOrNull()?.let { rowToProduct(it) } ?: return@transaction false
        ProductsTable.deleteWhere { ProductsTable.id eq id }
        searchIndex.remove(id)
        searchCache.invalidateAll()
        true
    }

    fun uploadImage(productId: String, fileName: String, bytes: ByteArray): String? = transaction {
        val product = ProductsTable.selectAll().where { ProductsTable.id eq productId }.singleOrNull()?.let { rowToProduct(it) }
            ?: return@transaction null
        val imageId = UUID.randomUUID().toString()
        ProductImagesTable.insert {
            it[id] = imageId
            it[ProductImagesTable.productId] = productId
            it[ProductImagesTable.fileName] = fileName
            it[ProductImagesTable.bytes] = ExposedBlob(bytes)
        }
        val imageUrl = "/products/$productId/images/$imageId"
        val updated = product.copy(
            imageUrls = product.imageUrls + imageUrl,
            updatedAt = System.currentTimeMillis()
        )
        persistUpdate(updated)
        reindex(updated)
        imageUrl
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
        val items = result.ids.mapNotNull { getProduct(it) }
        return ProductSearchPage(items = items, page = page, size = size, total = result.total)
    }

    private fun persistUpdate(product: Product) {
        ProductsTable.update({ ProductsTable.id eq product.id }) {
            it[sku] = product.sku
            it[name] = product.name
            it[description] = product.description
            it[price] = product.price
            it[taxCategory] = product.taxCategory.name
            it[modifiers] = product.modifiers
            it[imageUrls] = product.imageUrls
            it[barcode] = product.barcode
            it[category] = product.category
            it[inStock] = product.inStock
            it[updatedAt] = product.updatedAt
        }
    }

    private fun reindex(product: Product) {
        searchIndex.index(product)
        searchCache.invalidateAll()
    }
}
