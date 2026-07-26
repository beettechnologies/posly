package com.beettechnologies.posly.audit

import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAuditRoutes(auditRetentionService: AuditRetentionService) {
    routing {
        authenticate("jwt-auth") {
            withRole(Role.ADMIN) {
                route("/ops/audit-log") {
                    get {
                        val username = call.request.queryParameters["username"]
                        val event = call.request.queryParameters["event"]?.let { runCatching { AuditEvent.valueOf(it) }.getOrNull() }
                        val correlationId = call.request.queryParameters["correlationId"]
                        call.respond(HttpStatusCode.OK, AuditService.list(username, event, correlationId).map { it.toResponse() })
                    }
                }

                route("/ops/audit/retention") {
                    post("/run-now") {
                        call.respond(HttpStatusCode.OK, auditRetentionService.runRetentionNow().toResponse())
                    }
                }
            }
        }
    }
}
