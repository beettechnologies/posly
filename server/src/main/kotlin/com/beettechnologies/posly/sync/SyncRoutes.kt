package com.beettechnologies.posly.sync

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.cart.InvalidDiscountDtoException
import com.beettechnologies.posly.cart.toDomain
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Instant
import java.time.format.DateTimeParseException

private fun OfflineSaleItemRequest.toDomain(): OfflineSaleItemInput = OfflineSaleItemInput(
    sku = sku,
    productName = productName,
    quantity = quantity,
    unitPriceAtSale = unitPriceAtSale,
    taxCategoryAtSale = taxCategoryAtSale,
    selectedModifiers = selectedModifiers.map { OfflineSaleModifierInput(it.modifierId, it.option, it.additionalCost) },
    discount = discount?.toDomain()
)

private fun OfflineSaleRequest.toDomain(): OfflineSaleInput = OfflineSaleInput(
    idempotencyKey = idempotencyKey,
    items = items.map { it.toDomain() },
    discount = discount?.toDomain(),
    payments = payments.map { OfflineSalePaymentInput(it.method, it.amount, it.reference) },
    soldAt = Instant.parse(soldAt),
    soldBy = soldBy
)

fun Application.configureSyncRoutes(offlineSyncService: OfflineSyncService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER) {
                // Admin/manager review surface for anything that didn't cleanly resolve to CREATED -
                // the "admin notified" side of conflict handling. No messaging infra exists in this
                // codebase; as with payments' unresolved-refunds surface, this list IS the notification.
                get("/sync/conflicts") {
                    call.respond(HttpStatusCode.OK, offlineSyncService.listConflicts().map { it.toResponse() })
                }
            }
        }

        // Public w.r.t. JWT: a syncing device authenticates with its own client credentials, like
        // the heartbeat endpoint, so reconnect-and-sync works even if no cashier currently holds an
        // unexpired login session on the device.
        post("/sync/offline-sales") {
            val request = runCatching { call.receive<OfflineSaleBatchRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                return@post
            }

            val conflictPolicy = runCatching { ConflictPolicy.valueOf(request.conflictPolicy) }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("conflictPolicy must be one of REJECT, MAP, CONVERT"))
                return@post
            }

            val sales = try {
                request.sales.map { it.toDomain() }
            } catch (e: InvalidDiscountDtoException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid discount"))
                return@post
            } catch (e: DateTimeParseException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("soldAt must be a valid ISO-8601 instant"))
                return@post
            }

            when (
                val result = offlineSyncService.ingestBatch(request.clientId, request.clientSecret, conflictPolicy, sales, correlationId = call.callId)
            ) {
                is IngestBatchResult.Success -> call.respond(
                    HttpStatusCode.OK,
                    OfflineSaleBatchResponse(result.results.map { it.toResponse() })
                )
                IngestBatchResult.InvalidCredentials -> call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid device credentials")
                )
                IngestBatchResult.DeviceDeprovisioned -> call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Device has been deprovisioned")
                )
            }
        }
    }
}
