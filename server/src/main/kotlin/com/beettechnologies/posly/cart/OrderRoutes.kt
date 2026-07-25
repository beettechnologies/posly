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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureOrderRoutes(orderService: OrderService) {
    routing {
        authenticate("jwt-auth") {
            route("/orders/{id}") {
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

                        val actorId = call.tokenClaims()?.userId
                        when (val result = orderService.refund(id, request.reason, actorId)) {
                            is RefundResult.Success -> call.respond(HttpStatusCode.OK, result.order.toResponse())
                            RefundResult.OrderNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Order not found"))
                            RefundResult.NotPaid -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Only a paid order can be refunded")
                            )
                        }
                    }
                }
            }
        }
    }
}
