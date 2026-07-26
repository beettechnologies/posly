package com.beettechnologies.posly.flags

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.auth.toResponse
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

private val FEATURE_FLAG_AUDIT_EVENTS = setOf(AuditEvent.FEATURE_FLAG_CREATED, AuditEvent.FEATURE_FLAG_UPDATED)

fun Application.configureFeatureFlagRoutes(featureFlagService: FeatureFlagService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN) {
                route("/feature-flags") {
                    post {
                        val request = runCatching { call.receive<CreateFeatureFlagRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.key.isBlank()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("key is required"))
                            return@post
                        }
                        if (request.rolloutPercentage !in 0..100) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("rolloutPercentage must be between 0 and 100"))
                            return@post
                        }
                        when (val result = featureFlagService.createFlag(request.key, request.description, request.enabled, request.rolloutPercentage)) {
                            is CreateFlagResult.Success -> call.respond(HttpStatusCode.Created, result.flag.toResponse())
                            CreateFlagResult.DuplicateKey -> call.respond(HttpStatusCode.Conflict, ErrorResponse("A flag with this key already exists"))
                        }
                    }

                    get {
                        call.respond(HttpStatusCode.OK, featureFlagService.listFlags().map { it.toResponse() })
                    }

                    patch("/{key}") {
                        val key = call.parameters["key"]!!
                        val request = runCatching { call.receive<UpdateFeatureFlagRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@patch
                        }
                        val actorUserId = call.tokenClaims()?.userId
                        when (
                            val result = featureFlagService.updateFlag(
                                key = key,
                                enabled = request.enabled,
                                rolloutPercentage = request.rolloutPercentage,
                                enabledStoreIds = request.enabledStoreIds?.toSet(),
                                actorUserId = actorUserId
                            )
                        ) {
                            is UpdateFlagResult.Updated -> call.respond(HttpStatusCode.OK, result.flag.toResponse())
                            UpdateFlagResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Feature flag not found"))
                            UpdateFlagResult.InvalidPercentage -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("rolloutPercentage must be between 0 and 100"))
                        }
                    }

                    get("/audit-log") {
                        val eventParam = call.request.queryParameters["event"]?.let { runCatching { AuditEvent.valueOf(it) }.getOrNull() }
                        val entries = if (eventParam != null) {
                            AuditService.list(event = eventParam)
                        } else {
                            AuditService.list().filter { it.event in FEATURE_FLAG_AUDIT_EVENTS }
                        }
                        call.respond(HttpStatusCode.OK, entries.map { it.toResponse() })
                    }
                }
            }
        }

        authenticate("jwt-auth") {
            withRole(Role.ADMIN, Role.MANAGER, Role.CASHIER, Role.MERCHANDISER) {
                get("/feature-flags/{key}/evaluate") {
                    val key = call.parameters["key"]!!
                    val storeId = call.request.queryParameters["storeId"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId query parameter is required"))
                        return@get
                    }
                    val evaluation = featureFlagService.evaluate(key, storeId)
                    call.respond(HttpStatusCode.OK, evaluation.toResponse(key, storeId))
                }
            }
        }
    }
}
