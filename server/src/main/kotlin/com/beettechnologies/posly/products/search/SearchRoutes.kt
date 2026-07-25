package com.beettechnologies.posly.products.search

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.products.Product
import com.beettechnologies.posly.products.ProductService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private const val DEFAULT_SIZE = 20
private const val MAX_SIZE = 100

fun Application.configureSearchRoutes(productService: ProductService) {
    routing {
        authenticate("jwt-auth") {
            route("/search") {
                get {
                    val rawInStock = call.request.queryParameters["in_stock"]
                    val inStock = rawInStock?.toBooleanStrictOrNull()
                    if (rawInStock != null && inStock == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("in_stock must be true or false"))
                        return@get
                    }

                    val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: DEFAULT_SIZE)
                        .coerceIn(1, MAX_SIZE)

                    val resultPage = productService.search(
                        query = call.request.queryParameters["q"],
                        barcode = call.request.queryParameters["barcode"],
                        category = call.request.queryParameters["category"],
                        inStock = inStock,
                        page = page,
                        size = size
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        SearchResponse(
                            results = resultPage.items.map { it.toSearchResultItem() },
                            page = resultPage.page,
                            size = resultPage.size,
                            total = resultPage.total
                        )
                    )
                }
            }
        }
    }
}

private fun Product.toSearchResultItem() = SearchResultItem(
    id = id,
    sku = sku,
    name = name,
    price = price,
    category = category,
    inStock = inStock,
    barcode = barcode
)
