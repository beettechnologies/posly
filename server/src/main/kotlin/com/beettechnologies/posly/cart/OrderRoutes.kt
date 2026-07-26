package com.beettechnologies.posly.cart

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.inventory.InventoryService
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.payments.PaymentGatewayService
import com.beettechnologies.posly.payments.RefundOrderResult
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Instant

private val REFUND_METHODS = setOf("CARD", "MANUAL")

private fun restock(
    inventoryService: InventoryService,
    order: Order,
    lineItems: List<RefundLineItemInput>,
    actorId: String?
) {
    lineItems.filter { it.restock }.forEach { line ->
        val item = order.items.find { it.id == line.cartItemId } ?: return@forEach
        inventoryService.adjustStock(
            productId = item.productId,
            storeId = order.storeId,
            delta = line.quantity,
            reason = "Refund restock",
            actorId = actorId
        )
    }
}

fun Application.configureOrderRoutes(
    orderService: OrderService,
    paymentGatewayService: PaymentGatewayService,
    inventoryService: InventoryService
) {
    routing {
        authenticate("jwt-auth") {
            route("/orders") {
                withRole(Role.ADMIN, Role.MANAGER) {
                    get {
                        val storeId = call.request.queryParameters["storeId"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId query parameter is required"))
                            return@get
                        }
                        val from = call.request.queryParameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("from must be an ISO-8601 instant"))
                            return@get
                        }
                        val to = call.request.queryParameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("to must be an ISO-8601 instant"))
                            return@get
                        }
                        call.respond(HttpStatusCode.OK, orderService.listOrders(storeId, from, to).map { it.toResponse() })
                    }
                }

                route("/{id}") {
                    withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                        get {
                            val order = orderService.getOrder(call.parameters["id"]!!)
                            if (order == null) {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                            } else {
                                call.respond(HttpStatusCode.OK, order.toResponse())
                            }
                        }

                        get("/events") {
                            val id = call.parameters["id"]!!
                            if (orderService.getOrder(id) == null) {
                                call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                                return@get
                            }
                            call.respond(HttpStatusCode.OK, orderService.listEvents(id).map { it.toResponse() })
                        }

                        post("/payments") {
                            val id = call.parameters["id"]!!
                            val request = runCatching { call.receive<ConfirmPaymentRequest>() }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                                return@post
                            }
                            if (request.method.isBlank()) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("method is required"))
                                return@post
                            }

                            val actorId = call.tokenClaims()?.userId
                            when (
                                val result = orderService.confirmPayment(
                                    orderId = id,
                                    method = request.method,
                                    amount = request.amount,
                                    reference = request.reference,
                                    actorId = actorId
                                )
                            ) {
                                is ConfirmPaymentResult.Success -> call.respond(HttpStatusCode.OK, result.order.toResponse())
                                ConfirmPaymentResult.OrderNotFound -> call.respond(
                                    HttpStatusCode.NotFound,
                                    ErrorResponse("Order not found")
                                )
                                ConfirmPaymentResult.NotPending -> call.respond(
                                    HttpStatusCode.Conflict,
                                    ErrorResponse("Order is not awaiting payment")
                                )
                                is ConfirmPaymentResult.InvalidAmount -> call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse(result.message)
                                )
                            }
                        }
                    }

                    withRole(Role.ADMIN, Role.MANAGER) {
                        post("/refund") {
                            val id = call.parameters["id"]!!
                            val request = runCatching { call.receive<RefundRequest>() }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                                return@post
                            }
                            if (request.refundId.isBlank()) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("refundId is required"))
                                return@post
                            }
                            val method = request.method.uppercase()
                            if (method !in REFUND_METHODS) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("method must be CARD or MANUAL"))
                                return@post
                            }
                            if (method == "MANUAL" && request.reason.isNullOrBlank()) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("reason is required for a manual refund"))
                                return@post
                            }

                            val actorId = call.tokenClaims()?.userId
                            val lineItems = request.lineItems.map { RefundLineItemInput(it.cartItemId, it.quantity, it.restock) }

                            if (method == "CARD") {
                                when (
                                    val result = paymentGatewayService.refundOrder(id, request.refundId, lineItems, request.reason, actorId)
                                ) {
                                    is RefundOrderResult.Success -> {
                                        restock(inventoryService, result.order, lineItems, actorId)
                                        call.respond(HttpStatusCode.OK, result.order.toResponse())
                                    }
                                    RefundOrderResult.OrderNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                                    RefundOrderResult.NoApprovedCardPayment -> call.respond(
                                        HttpStatusCode.Conflict,
                                        ErrorResponse("No approved card payment was found for this order")
                                    )
                                    RefundOrderResult.NotRefundable -> call.respond(
                                        HttpStatusCode.Conflict,
                                        ErrorResponse("Order has nothing left to refund")
                                    )
                                    RefundOrderResult.RefundWindowExpired -> call.respond(
                                        HttpStatusCode.Conflict,
                                        ErrorResponse("The refund window for this order has expired")
                                    )
                                    is RefundOrderResult.InvalidLineItem -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                                    is RefundOrderResult.GatewayError -> call.respond(HttpStatusCode.BadGateway, ErrorResponse(result.message))
                                }
                            } else {
                                when (val result = orderService.refund(id, request.refundId, "MANUAL", lineItems, request.reason, actorId)) {
                                    is RefundResult.Success -> {
                                        restock(inventoryService, result.order, lineItems, actorId)
                                        call.respond(HttpStatusCode.OK, result.order.toResponse())
                                    }
                                    RefundResult.OrderNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                                    RefundResult.NotRefundable -> call.respond(
                                        HttpStatusCode.Conflict,
                                        ErrorResponse("Order has nothing left to refund")
                                    )
                                    RefundResult.RefundWindowExpired -> call.respond(
                                        HttpStatusCode.Conflict,
                                        ErrorResponse("The refund window for this order has expired")
                                    )
                                    is RefundResult.InvalidLineItem -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
