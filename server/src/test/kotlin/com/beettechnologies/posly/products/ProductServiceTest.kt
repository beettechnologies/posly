package com.beettechnologies.posly.products

import com.beettechnologies.posly.TestDatabase

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProductServiceTest {

    @BeforeTest
    fun resetDb() {
        TestDatabase.reset()
    }

    @Test
    fun `getProductBySku finds a product by its sku`() {
        val service = ProductService()
        val created = assertIs<ProductResult.Created>(
            service.createProduct(CreateProductRequest(sku = "WID-1", name = "Widget", price = 10.0))
        ).product

        assertEquals(created.id, service.getProductBySku("WID-1")?.id)
    }

    @Test
    fun `getProductBySku returns null for an unknown sku`() {
        val service = ProductService()

        assertNull(service.getProductBySku("does-not-exist"))
    }

    @Test
    fun `getProductBySku no longer resolves once the product is deleted`() {
        val service = ProductService()
        val created = assertIs<ProductResult.Created>(
            service.createProduct(CreateProductRequest(sku = "WID-1", name = "Widget", price = 10.0))
        ).product

        service.deleteProduct(created.id)

        assertNull(service.getProductBySku("WID-1"))
    }
}
