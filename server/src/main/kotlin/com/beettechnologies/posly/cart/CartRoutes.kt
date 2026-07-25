package com.beettechnologies.posly.cart

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private class InvalidDiscountDtoException(message: String) : Exception(message)

private fun DiscountDto.toDomain(): Discount {
    val type = runCatching { DiscountType.valueOf(this.type) }.getOrElse {
        throw InvalidDiscountDtoException("Invalid discount type '${this.type}'")
    }
    return Discount(type, value)
}

fun Application.configureCartRoutes(cartService: CartService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                route("/carts") {
                    post {
                        val request = runCatching { call.receive<CreateCartRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.storeId.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId is required"))
                            return@post
                        }

                        val actorId = call.tokenClaims()?.userId
                        when (val result = cartService.createCart(request.storeId, actorId)) {
                            is CreateCartResult.Success -> call.respond(
                                HttpStatusCode.Created,
                                result.cart.toResponse(cartService.getTotals(result.cart))
                            )
                            CreateCartResult.StoreNotFound -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("storeId does not reference an existing store")
                            )
                        }
                    }

                    get("/{id}") {
                        val cart = cartService.getCart(call.parameters["id"]!!)
                        if (cart == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Cart not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, cart.toResponse(cartService.getTotals(cart)))
                        }
                    }

                    post("/{id}/items") {
                        val request = runCatching { call.receive<AddCartItemRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val discount = try {
                            request.discount?.toDomain()
                        } catch (e: InvalidDiscountDtoException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid discount"))
                            return@post
                        }
                        val modifiers = request.selectedModifiers.map { ModifierSelection(it.modifierId, it.option) }

                        when (
                            val result = cartService.addItem(
                                cartId = call.parameters["id"]!!,
                                productId = request.productId,
                                quantity = request.quantity,
                                selectedModifiers = modifiers,
                                discount = discount
                            )
                        ) {
                            is AddItemResult.Success -> call.respond(
                                HttpStatusCode.OK,
                                result.cart.toResponse(cartService.getTotals(result.cart))
                            )
                            AddItemResult.CartNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Cart not found"))
                            AddItemResult.CartNotOpen -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Cart is not open")
                            )
                            AddItemResult.ProductNotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Product not found")
                            )
                            AddItemResult.InvalidQuantity -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("quantity must be positive")
                            )
                            is AddItemResult.InvalidModifier -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(result.message)
                            )
                            is AddItemResult.InvalidDiscount -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(result.message)
                            )
                        }
                    }

                    delete("/{id}/items/{itemId}") {
                        when (
                            val result = cartService.removeItem(
                                cartId = call.parameters["id"]!!,
                                itemId = call.parameters["itemId"]!!
                            )
                        ) {
                            is RemoveItemResult.Success -> call.respond(
                                HttpStatusCode.OK,
                                result.cart.toResponse(cartService.getTotals(result.cart))
                            )
                            RemoveItemResult.CartNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Cart not found"))
                            RemoveItemResult.CartNotOpen -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Cart is not open")
                            )
                            RemoveItemResult.ItemNotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Item not found")
                            )
                        }
                    }

                    put("/{id}/discount") {
                        val request = runCatching { call.receive<SetCartDiscountRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@put
                        }
                        val discount = try {
                            request.discount?.toDomain()
                        } catch (e: InvalidDiscountDtoException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid discount"))
                            return@put
                        }

                        when (val result = cartService.setCartDiscount(call.parameters["id"]!!, discount)) {
                            is SetCartDiscountResult.Success -> call.respond(
                                HttpStatusCode.OK,
                                result.cart.toResponse(cartService.getTotals(result.cart))
                            )
                            SetCartDiscountResult.CartNotFound -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("Cart not found")
                            )
                            SetCartDiscountResult.CartNotOpen -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Cart is not open")
                            )
                            is SetCartDiscountResult.InvalidDiscount -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse(result.message)
                            )
                        }
                    }

                    post("/{id}/checkout") {
                        val request = runCatching { call.receive<CheckoutRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.idempotencyKey.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("idempotencyKey is required"))
                            return@post
                        }

                        when (val result = cartService.checkout(call.parameters["id"]!!, request.idempotencyKey)) {
                            is CheckoutResult.Success -> call.respond(
                                if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created,
                                result.order.toResponse()
                            )
                            CheckoutResult.CartNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Cart not found"))
                            CheckoutResult.EmptyCart -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Cannot checkout an empty cart")
                            )
                            CheckoutResult.CartAlreadyCheckedOut -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Cart has already been checked out with a different idempotency key")
                            )
                        }
                    }
                }

                route("/orders/{id}") {
                    get {
                        val order = cartService.getOrder(call.parameters["id"]!!)
                        if (order == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, order.toResponse())
                        }
                    }
                }
            }
        }
    }
}
