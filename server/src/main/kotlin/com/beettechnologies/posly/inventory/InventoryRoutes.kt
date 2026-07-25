package com.beettechnologies.posly.inventory

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

fun Application.configureInventoryRoutes(inventoryService: InventoryService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER) {
                route("/inventory/adjustments") {
                    post {
                        val request = runCatching { call.receive<AdjustStockRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.reason.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("reason is required"))
                            return@post
                        }
                        if (request.delta == 0) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("delta must be non-zero"))
                            return@post
                        }

                        val actorId = call.tokenClaims()?.userId
                        when (
                            val result = inventoryService.adjustStock(
                                productId = request.productId,
                                storeId = request.storeId,
                                delta = request.delta,
                                reason = request.reason,
                                actorId = actorId
                            )
                        ) {
                            is AdjustStockResult.Success -> call.respond(HttpStatusCode.Created, result.snapshot.toResponse())
                            AdjustStockResult.ProductNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Product not found"))
                            AdjustStockResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            AdjustStockResult.WouldGoNegative -> call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Adjustment would reduce on-hand stock below the reserved quantity")
                            )
                        }
                    }
                }

                route("/inventory/transactions") {
                    get {
                        val storeId = call.request.queryParameters["storeId"]
                        val productId = call.request.queryParameters["productId"]
                        call.respond(
                            HttpStatusCode.OK,
                            inventoryService.listTransactions(storeId, productId).map { it.toResponse() }
                        )
                    }
                }
            }

            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                route("/inventory/reservations") {
                    post {
                        val request = runCatching { call.receive<ReserveRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.quantity <= 0) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("quantity must be positive"))
                            return@post
                        }
                        if (request.referenceId.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("referenceId is required"))
                            return@post
                        }

                        when (
                            val result = inventoryService.reserve(
                                productId = request.productId,
                                storeId = request.storeId,
                                quantity = request.quantity,
                                referenceId = request.referenceId
                            )
                        ) {
                            is ReserveResult.Success -> call.respond(HttpStatusCode.Created, result.reservation.toResponse())
                            ReserveResult.ProductNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Product not found"))
                            ReserveResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            ReserveResult.InsufficientStock -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Insufficient available stock for this reservation")
                            )
                        }
                    }

                    post("/{id}/release") {
                        val id = call.parameters["id"]!!
                        when (val result = inventoryService.release(id)) {
                            is ReleaseResult.Success -> call.respond(HttpStatusCode.OK, result.reservation.toResponse())
                            ReleaseResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Reservation not found"))
                            ReleaseResult.NotActive -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Reservation is not active")
                            )
                        }
                    }

                    post("/{id}/commit") {
                        val id = call.parameters["id"]!!
                        when (val result = inventoryService.commit(id)) {
                            is CommitReservationResult.Success -> call.respond(HttpStatusCode.OK, result.reservation.toResponse())
                            CommitReservationResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Reservation not found"))
                            CommitReservationResult.NotActive -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Reservation is not active")
                            )
                        }
                    }
                }
            }

            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.MERCHANDISER) {
                get("/inventory/snapshot") {
                    val productId = call.request.queryParameters["productId"]
                    val storeId = call.request.queryParameters["storeId"]
                    if (productId == null || storeId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("productId and storeId query params are required"))
                        return@get
                    }
                    val snapshot = inventoryService.getSnapshot(productId, storeId)
                    if (snapshot == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("No inventory snapshot for this product/store"))
                    } else {
                        call.respond(HttpStatusCode.OK, snapshot.toResponse())
                    }
                }

                get("/inventory/snapshots") {
                    val storeId = call.request.queryParameters["storeId"]
                    val productId = call.request.queryParameters["productId"]
                    call.respond(
                        HttpStatusCode.OK,
                        inventoryService.listSnapshots(storeId, productId).map { it.toResponse() }
                    )
                }
            }
        }
    }
}
