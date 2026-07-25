package com.beettechnologies.posly.stores

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.products.TaxCategory
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

private const val TAX_CATEGORY_ERROR = "taxCategory must be one of STANDARD, REDUCED, ZERO, EXEMPT"

/**
 * The tax engine's own dedicated API, distinct from the tax-profile-scoped
 * /tax-profiles/{id}/calculate: takes a full set of order lines (each carrying its own
 * TaxCategory) so exemption, composite ordering/compounding, inclusive pricing, and the
 * configured rounding mode are all exercised together in one call, with the exempt amount
 * surfaced explicitly rather than silently folded away.
 */
fun Application.configureTaxRoutes(taxProfileService: TaxProfileService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.MERCHANDISER) {
                post("/taxes/calculate") {
                    val request = runCatching { call.receive<TaxCalculateRequest>() }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                        return@post
                    }

                    val profile = taxProfileService.getProfile(request.taxProfileId)
                    if (profile == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Tax profile not found"))
                        return@post
                    }
                    if (request.lines.any { it.amount < 0 }) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("each line amount must be non-negative"))
                        return@post
                    }
                    val hasInvalidCategory = request.lines.any { line ->
                        runCatching { TaxCategory.valueOf(line.taxCategory) }.isFailure
                    }
                    if (hasInvalidCategory) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(TAX_CATEGORY_ERROR))
                        return@post
                    }

                    val lines = request.lines.map { line ->
                        TaxCalculateLine(line.id, line.amount, TaxCategory.valueOf(line.taxCategory))
                    }
                    val result = TaxEngine.calculateForLines(profile, lines)

                    call.respond(
                        HttpStatusCode.OK,
                        TaxCalculateResponse(
                            taxableAmount = result.taxableAmount,
                            exemptAmount = result.exemptAmount,
                            breakdown = result.breakdown.map { TaxBreakdownItem(it.name, it.ratePercent, it.amount) },
                            totalTax = result.totalTax,
                            total = result.total
                        )
                    )
                }
            }
        }
    }
}
