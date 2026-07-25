package com.beettechnologies.posly.products

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureProductRoutes(productService: ProductService) {
    routing {
        authenticate("jwt-auth") {
            route("/products") {
                withRole(Role.ADMIN, Role.MANAGER, Role.MERCHANDISER) {
                    post {
                        val req = runCatching { call.receive<CreateProductRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (req.sku.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("sku is required"))
                            return@post
                        }
                        if (req.name.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("name is required"))
                            return@post
                        }
                        if (req.price < 0) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("price must be non-negative"))
                            return@post
                        }
                        if (runCatching { TaxCategory.valueOf(req.taxCategory) }.isFailure) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("taxCategory must be one of: ${TaxCategory.entries.joinToString()}")
                            )
                            return@post
                        }
                        when (val result = productService.createProduct(req)) {
                            is ProductResult.Created -> call.respond(
                                HttpStatusCode.Created,
                                CreateProductResponse(id = result.product.id)
                            )
                            ProductResult.DuplicateSku -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("A product with SKU '${req.sku}' already exists")
                            )
                            else -> call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Unexpected error creating product")
                            )
                        }
                    }
                }

                get {
                    val productList = productService.listProducts().map { it.toResponse() }
                    call.respond(HttpStatusCode.OK, productList)
                }

                route("/{id}") {
                    get {
                        val id = call.parameters["id"]!!
                        val product = productService.getProduct(id)
                        if (product == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Product not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, product.toResponse())
                        }
                    }

                    withRole(Role.ADMIN, Role.MANAGER, Role.MERCHANDISER) {
                        put {
                            val id = call.parameters["id"]!!
                            val req = runCatching { call.receive<UpdateProductRequest>() }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                                return@put
                            }
                            if (req.price != null && req.price < 0) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("price must be non-negative"))
                                return@put
                            }
                            if (req.taxCategory != null && runCatching { TaxCategory.valueOf(req.taxCategory) }.isFailure) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("taxCategory must be one of: ${TaxCategory.entries.joinToString()}")
                                )
                                return@put
                            }
                            when (val result = productService.updateProduct(id, req)) {
                                is ProductResult.Updated -> call.respond(HttpStatusCode.OK, result.product.toResponse())
                                ProductResult.NotFound -> call.respond(
                                    HttpStatusCode.NotFound,
                                    ErrorResponse("Product not found")
                                )
                                else -> call.respond(
                                    HttpStatusCode.InternalServerError,
                                    ErrorResponse("Unexpected error updating product")
                                )
                            }
                        }
                    }

                    withRole(Role.ADMIN, Role.MANAGER) {
                        delete {
                            val id = call.parameters["id"]!!
                            if (productService.deleteProduct(id)) {
                                call.respond(HttpStatusCode.NoContent)
                            } else {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Product not found"))
                            }
                        }
                    }

                    withRole(Role.ADMIN, Role.MANAGER, Role.MERCHANDISER) {
                        post("/images") {
                            val id = call.parameters["id"]!!
                            if (productService.getProduct(id) == null) {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Product not found"))
                                return@post
                            }
                            val multipart = call.receiveMultipart()
                            var imageUrl: String? = null
                            multipart.forEachPart { part ->
                                if (part is PartData.FileItem && imageUrl == null) {
                                    val bytes = part.streamProvider().readBytes()
                                    if (bytes.isNotEmpty()) {
                                        imageUrl = productService.uploadImage(
                                            id,
                                            part.originalFileName ?: "image",
                                            bytes
                                        )
                                    }
                                }
                                part.dispose()
                            }
                            if (imageUrl == null) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("No image file provided in multipart request")
                                )
                            } else {
                                call.respond(HttpStatusCode.Created, ImageUploadResponse(imageUrl = imageUrl!!))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Product.toResponse() = ProductResponse(
    id = id,
    sku = sku,
    name = name,
    description = description,
    price = price,
    taxCategory = taxCategory.name,
    modifiers = modifiers.map { m ->
        ModifierResponse(
            id = m.id,
            name = m.name,
            options = m.options,
            additionalCost = m.additionalCost
        )
    },
    imageUrls = imageUrls,
    createdAt = createdAt,
    updatedAt = updatedAt
)
