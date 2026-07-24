package com.beettechnologies.posly.rbac

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.auth.TokenClaims
import com.beettechnologies.posly.model.Role
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Wraps a route block to enforce that the authenticated caller holds at least one of [requiredRoles].
 * Installs an intercept on a transparent child route that runs before route handlers.
 * Returns 403 if the caller's roles do not intersect with the required set.
 * Unauthenticated requests (null principal) are left to the JWT auth challenge (→ 401).
 */
fun Route.withRole(vararg requiredRoles: Role, build: Route.() -> Unit): Route {
    val selector = object : RouteSelector() {
        override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
            RouteSelectorEvaluation.Transparent

        override fun toString() = "rbac(${requiredRoles.joinToString()})"
    }
    val rbacRoute = createChild(selector)
    rbacRoute.intercept(ApplicationCallPipeline.Call) {
        val principal = call.principal<JWTPrincipal>()
        if (principal == null) {
            // JWT challenge already sent 401; stop here so the handler doesn't run
            return@intercept
        }
        val rolesRaw = principal.payload.getClaim("roles")?.asList(String::class.java) ?: emptyList()
        val userRoles = rolesRaw.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
        if (requiredRoles.none { it in userRoles }) {
            AuditService.record(
                AuditEvent.ACCESS_DENIED,
                userId = principal.payload.subject,
                detail = "required=${requiredRoles.toList()}, has=$userRoles"
            )
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Insufficient role"))
            return@intercept
        }
        proceed()
    }
    rbacRoute.build()
    return rbacRoute
}

/**
 * Returns the [TokenClaims] for the current authenticated call, or null.
 */
fun ApplicationCall.tokenClaims(): TokenClaims? {
    val principal = principal<JWTPrincipal>() ?: return null
    val rolesRaw = principal.payload.getClaim("roles")?.asList(String::class.java) ?: emptyList()
    val roles = rolesRaw.mapNotNull { runCatching { Role.valueOf(it) }.getOrNull() }.toSet()
    return TokenClaims(userId = principal.payload.subject, roles = roles)
}

