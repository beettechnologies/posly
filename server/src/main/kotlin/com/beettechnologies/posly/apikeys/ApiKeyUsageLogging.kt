package com.beettechnologies.posly.apikeys

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*

/**
 * Records one [ApiKeyUsageRecord] per API-key-authenticated request - the "usage logs" this
 * ticket's DoD asks for, queryable via `GET /api-keys/{id}/usage`. Hooked at `onCallRespond` (not
 * at authentication time) specifically so the logged status code reflects the *actual* outcome -
 * a 200, a 403 from [withRoleOrScope]'s scope check, whatever - not just "auth succeeded."
 * Requests authenticated via a user JWT (no [ApiKeyPrincipal] attached) are silently skipped -
 * this is API-key usage logging, not a general request log (that's already
 * [com.beettechnologies.posly.observability.configureObservability]'s job).
 */
fun Application.installApiKeyUsageLogging(apiKeyService: ApiKeyService) {
    val plugin = createApplicationPlugin("ApiKeyUsageLogging") {
        onCallRespond { call ->
            val principal = call.principal<ApiKeyPrincipal>() ?: return@onCallRespond
            apiKeyService.recordUsage(
                apiKeyId = principal.apiKeyId,
                method = call.request.httpMethod.value,
                path = call.request.path(),
                statusCode = call.response.status()?.value ?: 0
            )
        }
    }
    install(plugin)
}
