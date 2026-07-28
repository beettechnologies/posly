package com.beettechnologies.posly.apikeys

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.model.Role
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Wraps a route block to accept EITHER a user JWT (checked against [requiredRoles], exactly like
 * [com.beettechnologies.posly.rbac.withRole]) OR an API key (checked against [requiredScope]) -
 * for the small set of read endpoints 3rd-party integrations use (see API_KEYS.md). The route
 * itself must `authenticate("jwt-auth", "api-key-auth") { }` for both principal types to ever be
 * present to check here; this function only decides which check applies once one of them is.
 *
 * Deliberately a separate function from [com.beettechnologies.posly.rbac.withRole] rather than an
 * extension of it: every *other* route in this codebase is JWT-only, and giving them an unused
 * `requiredScope` parameter (or overloading role-checking with an API-key branch they can never
 * hit) would be a worse API than one guard for JWT-only routes and one for routes that
 * deliberately accept both.
 */
fun Route.withRoleOrScope(requiredRoles: Set<Role>, requiredScope: ApiKeyScope, build: Route.() -> Unit): Route {
    val selector = object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
            RouteSelectorEvaluation.Transparent

        override fun toString() = "rbacOrScope(roles=${requiredRoles.joinToString()}, scope=$requiredScope)"
    }
    val guardRoute = createChild(selector)
    guardRoute.intercept(ApplicationCallPipeline.Call) {
        val jwtPrincipal = call.principal<JWTPrincipal>()
        if (jwtPrincipal != null) {
            val rolesRaw = jwtPrincipal.payload.getClaim("roles")?.asList(String::class.java) ?: emptyList()
            val userRoles = rolesRaw.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
            if (requiredRoles.none { it in userRoles }) {
                AuditService.record(
                    AuditEvent.ACCESS_DENIED,
                    userId = jwtPrincipal.payload.subject,
                    detail = "required roles=$requiredRoles, has=$userRoles"
                )
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Insufficient role"))
                return@intercept
            }
            proceed()
            return@intercept
        }

        val apiKeyPrincipal = call.principal<ApiKeyPrincipal>()
        if (apiKeyPrincipal != null) {
            if (requiredScope !in apiKeyPrincipal.scopes) {
                AuditService.record(
                    AuditEvent.ACCESS_DENIED,
                    detail = "apiKeyId=${apiKeyPrincipal.apiKeyId} required scope=$requiredScope, has=${apiKeyPrincipal.scopes}"
                )
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Insufficient scope"))
                return@intercept
            }
            proceed()
            return@intercept
        }

        // Neither principal present - the authenticate(...) challenge already sent 401.
    }
    guardRoute.build()
    return guardRoute
}
