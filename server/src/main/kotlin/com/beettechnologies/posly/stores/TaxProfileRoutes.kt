package com.beettechnologies.posly.stores

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
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
import java.math.RoundingMode

private val ALLOWED_ROUNDING_MODES = setOf(
    RoundingMode.UP, RoundingMode.DOWN, RoundingMode.CEILING, RoundingMode.FLOOR,
    RoundingMode.HALF_UP, RoundingMode.HALF_DOWN, RoundingMode.HALF_EVEN
)

private const val ROUNDING_MODE_ERROR = "roundingMode must be one of UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN"
private const val PRICING_MODE_ERROR = "pricingMode must be INCLUSIVE or EXCLUSIVE"

private fun parseRoundingMode(value: String): RoundingMode? =
    runCatching { RoundingMode.valueOf(value) }.getOrNull()?.takeIf { it in ALLOWED_ROUNDING_MODES }

private fun parsePricingMode(value: String): PricingMode? = runCatching { PricingMode.valueOf(value) }.getOrNull()

fun Application.configureTaxProfileRoutes(taxProfileService: TaxProfileService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN) {
                route("/tax-profiles") {
                    post {
                        val request = runCatching { call.receive<CreateTaxProfileRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.name.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("name is required"))
                            return@post
                        }
                        if (request.rates.any { it.name.isBlank() || it.ratePercent < 0 }) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("each rate needs a non-blank name and a non-negative ratePercent")
                            )
                            return@post
                        }
                        val pricingMode = parsePricingMode(request.pricingMode) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(PRICING_MODE_ERROR))
                            return@post
                        }
                        val roundingMode = parseRoundingMode(request.roundingMode) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ROUNDING_MODE_ERROR))
                            return@post
                        }

                        val created = taxProfileService.createProfile(
                            name = request.name,
                            rates = request.rates.map { TaxRate(it.name, it.ratePercent, it.order, it.compoundsOnPrior) },
                            pricingMode = pricingMode,
                            roundingMode = roundingMode
                        )
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }

                    get {
                        call.respond(HttpStatusCode.OK, taxProfileService.listProfiles().map { it.toResponse() })
                    }

                    get("/{id}") {
                        val id = call.parameters["id"]!!
                        val profile = taxProfileService.getProfile(id)
                        if (profile == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Tax profile not found"))
                        } else {
                            call.respond(HttpStatusCode.OK, profile.toResponse())
                        }
                    }

                    put("/{id}") {
                        val id = call.parameters["id"]!!
                        val request = runCatching { call.receive<UpdateTaxProfileRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@put
                        }
                        val pricingMode = request.pricingMode?.let { value ->
                            parsePricingMode(value) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse(PRICING_MODE_ERROR))
                                return@put
                            }
                        }
                        val roundingMode = request.roundingMode?.let { value ->
                            parseRoundingMode(value) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ROUNDING_MODE_ERROR))
                                return@put
                            }
                        }

                        when (
                            val result = taxProfileService.updateProfile(
                                id = id,
                                name = request.name,
                                rates = request.rates?.map { TaxRate(it.name, it.ratePercent, it.order, it.compoundsOnPrior) },
                                pricingMode = pricingMode,
                                roundingMode = roundingMode
                            )
                        ) {
                            is UpdateTaxProfileResult.Updated -> call.respond(HttpStatusCode.OK, result.profile.toResponse())
                            UpdateTaxProfileResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Tax profile not found"))
                        }
                    }

                    delete("/{id}") {
                        val id = call.parameters["id"]!!
                        if (taxProfileService.deleteProfile(id)) {
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Tax profile not found"))
                        }
                    }
                }
            }
        }

        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.MERCHANDISER) {
                post("/tax-profiles/{id}/calculate") {
                    val id = call.parameters["id"]!!
                    val request = runCatching { call.receive<CalculateTaxRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                        return@post
                    }
                    if (request.amount < 0) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("amount must be non-negative"))
                        return@post
                    }

                    when (val result = taxProfileService.calculateTax(id, request.amount)) {
                        is CalculateTaxResult.Success -> call.respond(
                            HttpStatusCode.OK,
                            CalculateTaxResponse(
                                subtotal = result.subtotal,
                                breakdown = result.breakdown,
                                totalTax = result.totalTax,
                                total = result.total
                            )
                        )
                        CalculateTaxResult.ProfileNotFound -> call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Tax profile not found")
                        )
                    }
                }
            }
        }
    }
}
