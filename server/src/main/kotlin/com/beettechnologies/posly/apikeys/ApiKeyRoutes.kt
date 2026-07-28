package com.beettechnologies.posly.apikeys

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

fun Application.configureApiKeyRoutes(apiKeyService: ApiKeyService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN) {
                route("/api-keys") {
                    post {
                        val request = runCatching { call.receive<CreateApiKeyRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        if (request.scopes.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one scope is required"))
                            return@post
                        }
                        val scopes = request.scopes.map {
                            runCatching { ApiKeyScope.valueOf(it) }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("scopes must be one of: ${ApiKeyScope.entries.joinToString()}"))
                                return@post
                            }
                        }.toSet()

                        val actorId = call.tokenClaims()?.userId
                        when (val result = apiKeyService.createKey(request.name, scopes, actorId)) {
                            is CreateApiKeyResult.Success -> call.respond(
                                HttpStatusCode.Created,
                                ApiKeyCreatedResponse(result.apiKey.toResponse(), result.rawKey)
                            )
                            CreateApiKeyResult.EmptyName -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("name is required"))
                            CreateApiKeyResult.NoScopes -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one scope is required"))
                        }
                    }

                    get {
                        call.respond(HttpStatusCode.OK, apiKeyService.listKeys().map { it.toResponse() })
                    }

                    post("/{id}/revoke") {
                        val id = call.parameters["id"]!!
                        val actorId = call.tokenClaims()?.userId
                        when (val result = apiKeyService.revokeKey(id, actorId)) {
                            is RevokeApiKeyResult.Success -> call.respond(HttpStatusCode.OK, result.apiKey.toResponse())
                            RevokeApiKeyResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("API key not found"))
                            RevokeApiKeyResult.AlreadyRevoked -> call.respond(HttpStatusCode.Conflict, ErrorResponse("API key is already revoked"))
                        }
                    }

                    post("/{id}/rotate") {
                        val id = call.parameters["id"]!!
                        val actorId = call.tokenClaims()?.userId
                        when (val result = apiKeyService.rotateKey(id, actorId)) {
                            is RotateApiKeyResult.Success -> call.respond(
                                HttpStatusCode.OK,
                                ApiKeyCreatedResponse(result.apiKey.toResponse(), result.rawKey)
                            )
                            RotateApiKeyResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("API key not found"))
                            RotateApiKeyResult.Revoked -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot rotate a revoked API key"))
                        }
                    }

                    get("/{id}/usage") {
                        val id = call.parameters["id"]!!
                        if (apiKeyService.getKey(id) == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("API key not found"))
                            return@get
                        }
                        call.respond(HttpStatusCode.OK, apiKeyService.listUsage(id).map { it.toResponse() })
                    }
                }
            }
        }
    }
}
