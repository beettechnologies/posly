package com.beettechnologies.posly.shifts

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
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
private data class RequiresOverrideOrNoteResponse(val error: String, val variance: Double, val threshold: Double)

fun Application.configureShiftRoutes(shiftService: ShiftService) {
    routing {
        authenticate("jwt-auth") {
            route("/shifts") {
                withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER) {
                    post("/open") {
                        val request = runCatching { call.receive<OpenShiftRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }

                        val cashierId = call.tokenClaims()?.userId
                        when (val result = shiftService.openShift(request.storeId, cashierId, request.openingFloat)) {
                            is OpenShiftResult.Success -> call.respond(HttpStatusCode.Created, result.shift.toResponse())
                            OpenShiftResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            OpenShiftResult.ShiftAlreadyOpen -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("You already have an open shift at this store")
                            )
                            is OpenShiftResult.InvalidAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    }

                    post("/{id}/close") {
                        val id = call.parameters["id"]!!
                        val request = runCatching { call.receive<CloseShiftRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }

                        val claims = call.tokenClaims()
                        val isManagerOrAdmin = claims?.roles.orEmpty().any { it == Role.MANAGER || it == Role.ADMIN }
                        when (
                            val result = shiftService.closeShift(
                                shiftId = id,
                                closingCount = request.closingCount,
                                note = request.note,
                                closedBy = claims?.userId,
                                closedByIsManagerOrAdmin = isManagerOrAdmin
                            )
                        ) {
                            is CloseShiftResult.Success -> call.respond(HttpStatusCode.OK, result.shift.toResponse())
                            CloseShiftResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Shift not found"))
                            CloseShiftResult.NotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Shift is not open"))
                            is CloseShiftResult.InvalidAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                            is CloseShiftResult.RequiresOverrideOrNote -> call.respond(
                                HttpStatusCode.Conflict,
                                RequiresOverrideOrNoteResponse(
                                    error = "Variance of ${result.variance} exceeds the ${result.threshold} threshold - " +
                                        "add a note or have a manager close this shift",
                                    variance = result.variance,
                                    threshold = result.threshold
                                )
                            )
                        }
                    }

                    get {
                        val storeId = call.request.queryParameters["storeId"]
                        val cashierId = call.request.queryParameters["cashierId"]
                        call.respond(HttpStatusCode.OK, shiftService.listShifts(storeId, cashierId).map { it.toResponse() })
                    }

                    get("/{id}") {
                        val shift = shiftService.getShift(call.parameters["id"]!!)
                        if (shift == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Shift not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, shift.toResponse())
                        }
                    }

                    get("/{id}/expected-cash") {
                        val expectedCash = shiftService.previewExpectedCash(call.parameters["id"]!!)
                        if (expectedCash == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Shift not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, ExpectedCashResponse(expectedCash, Instant.now().toString()))
                        }
                    }
                }
            }
        }
    }
}
