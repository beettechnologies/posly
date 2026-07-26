package com.beettechnologies.posly.secrets

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun SecretName.pathSegment(): String = when (this) {
    SecretName.JWT_SIGNING_KEY -> "jwt-signing-key"
    SecretName.PAYMENT_WEBHOOK_SECRET -> "payment-webhook-secret"
}

private fun secretNameFromPathSegment(segment: String): SecretName? =
    SecretName.entries.find { it.pathSegment() == segment }

fun Application.configureSecretsRoutes(secretsManager: SecretsManager) {
    routing {
        authenticate("jwt-auth") {
            route("/ops/secrets") {
                withRole(Role.ADMIN) {
                    get {
                        val summaries = SecretName.entries.map { name ->
                            val current = secretsManager.current(name)
                            SecretSummaryResponse(
                                name = name.pathSegment(),
                                current = SecretVersionSummaryResponse(
                                    id = current.id,
                                    issuedAt = current.issuedAt.toString(),
                                    validUntil = null,
                                    status = SecretVersionStatus.CURRENT.name
                                ),
                                history = secretsManager.history(name).map { it.toResponse() }
                            )
                        }
                        call.respond(HttpStatusCode.OK, summaries)
                    }

                    post("/{name}/rotate") {
                        val name = secretNameFromPathSegment(call.parameters["name"] ?: "")
                        if (name == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown secret name"))
                            return@post
                        }
                        val actorUserId = call.tokenClaims()?.userId
                        val newVersion = secretsManager.rotate(name, actorUserId)
                        val graceExpiresAt = secretsManager.history(name)
                            .filter { it.status == SecretVersionStatus.IN_GRACE_PERIOD }
                            .maxByOrNull { it.issuedAt }
                            ?.validUntil
                        call.respond(
                            HttpStatusCode.OK,
                            SecretRotationResponse(
                                secretName = name.pathSegment(),
                                newVersionId = newVersion.id,
                                newValue = newVersion.value,
                                issuedAt = newVersion.issuedAt.toString(),
                                previousVersionGraceExpiresAt = graceExpiresAt?.toString()
                            )
                        )
                    }
                }
            }
        }
    }
}
