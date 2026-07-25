package com.beettechnologies.posly.auth

import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureAuthRoutes(authService: AuthService) {
    routing {
        route("/auth") {
            post("/login") {
                val req = runCatching { call.receive<LoginRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    return@post
                }
                val ip = call.request.origin.remoteHost
                when (val result = authService.login(req.username, req.password, ip)) {
                    is AuthResult.Success -> call.respond(
                        HttpStatusCode.OK,
                        LoginResponse(accessToken = result.accessToken, refreshToken = result.refreshToken)
                    )
                    is AuthResult.MfaRequired -> call.respond(
                        HttpStatusCode.OK,
                        LoginResponse(mfaRequired = true, mfaToken = result.mfaToken)
                    )
                    is AuthResult.InvalidCredentials -> call.respond(
                        HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password")
                    )
                    else -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Unexpected error"))
                }
            }

            post("/refresh") {
                val req = runCatching { call.receive<RefreshRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    return@post
                }
                val ip = call.request.origin.remoteHost
                when (val result = authService.refresh(req.refreshToken, ip)) {
                    is AuthResult.Success -> call.respond(
                        HttpStatusCode.OK,
                        RefreshResponse(accessToken = result.accessToken)
                    )
                    is AuthResult.TokenInvalid -> call.respond(
                        HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired refresh token")
                    )
                    is AuthResult.UserNotFound -> call.respond(
                        HttpStatusCode.Unauthorized, ErrorResponse("User not found")
                    )
                    else -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Unexpected error"))
                }
            }

            post("/logout") {
                val req = runCatching { call.receive<LogoutRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    return@post
                }
                val ip = call.request.origin.remoteHost
                authService.logout(req.refreshToken, ip)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/mfa/verify") {
                val req = runCatching { call.receive<MfaVerifyRequest>() }.getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                    return@post
                }
                val ip = call.request.origin.remoteHost
                when (val result = authService.verifyMfa(req.mfaToken, req.code, ip)) {
                    is AuthResult.Success -> call.respond(
                        HttpStatusCode.OK,
                        MfaVerifyResponse(accessToken = result.accessToken, refreshToken = result.refreshToken)
                    )
                    is AuthResult.MfaInvalid -> call.respond(
                        HttpStatusCode.Unauthorized, ErrorResponse("Invalid MFA code")
                    )
                    is AuthResult.TokenInvalid -> call.respond(
                        HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired MFA token")
                    )
                    is AuthResult.UserNotFound -> call.respond(
                        HttpStatusCode.Unauthorized, ErrorResponse("User not found")
                    )
                    else -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Unexpected error"))
                }
            }
        }

        // Example of RBAC-protected routes — demonstrates the 403 enforcement
        authenticate("jwt-auth") {
            withRole(Role.ADMIN) {
                get("/admin/dashboard") {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Admin dashboard"))
                }
            }

            route("/protected") {
                withRole(Role.ADMIN, Role.MANAGER) {
                    get("/reports") {
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Reports"))
                    }
                }
                get("/me") {
                    val claims = call.tokenClaims()
                    call.respond(HttpStatusCode.OK, mapOf("userId" to claims?.userId, "roles" to claims?.roles?.map { it.name }))
                }
            }
        }
    }
}
