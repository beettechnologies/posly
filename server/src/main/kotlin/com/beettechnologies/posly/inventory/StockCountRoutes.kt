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

fun Application.configureStockCountRoutes(stockCountService: StockCountService) {
    routing {
        authenticate("jwt-auth") {
            route("/inventory/stock-counts") {
                withRole(Role.ADMIN, Role.MANAGER) {
                    post {
                        val request = runCatching { call.receive<SubmitStockCountRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }

                        val actorId = call.tokenClaims()?.userId
                        val lines = request.lines.map { StockCountLineInput(it.productId, it.countedQuantity) }
                        when (val result = stockCountService.submitStockCount(request.storeId, lines, actorId)) {
                            is SubmitStockCountResult.Success -> call.respond(HttpStatusCode.Created, result.stockCount.toResponse())
                            SubmitStockCountResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            is SubmitStockCountResult.InvalidLine -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    }
                }

                withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.MERCHANDISER) {
                    get {
                        val storeId = call.request.queryParameters["storeId"]
                        call.respond(HttpStatusCode.OK, stockCountService.listStockCounts(storeId).map { it.toResponse() })
                    }

                    get("/{id}") {
                        val stockCount = stockCountService.getStockCount(call.parameters["id"]!!)
                        if (stockCount == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Stock count not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, stockCount.toResponse())
                        }
                    }
                }
            }
        }
    }
}
