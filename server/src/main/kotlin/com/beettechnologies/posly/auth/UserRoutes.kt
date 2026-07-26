package com.beettechnologies.posly.auth

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.model.UserStatus
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Parses role names, returning null (rather than throwing) the moment any name doesn't match [Role]. */
private fun parseRoles(names: List<String>): Set<Role>? {
    val roles = mutableSetOf<Role>()
    for (name in names) {
        roles.add(runCatching { Role.valueOf(name) }.getOrElse { return null })
    }
    return roles
}

fun Application.configureUserRoutes(
    authService: AuthService,
    userService: UserService,
    ssoConfigService: SsoConfigService
) {
    routing {
        // Public: the invited user has no token yet, so this can't sit behind jwt-auth.
        post("/users/accept-invite") {
            val req = runCatching { call.receive<AcceptInviteRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                return@post
            }
            when (authService.acceptInvite(req.token, req.newPassword)) {
                AcceptInviteResult.Success -> call.respond(HttpStatusCode.NoContent)
                AcceptInviteResult.TokenInvalid -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired invite token"))
                AcceptInviteResult.UserNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                AcceptInviteResult.NotInvited -> call.respond(HttpStatusCode.Conflict, ErrorResponse("User is not in an invited state"))
            }
        }

        // Public: stands in for the callback a real SAML/OIDC handler would invoke after validating the login.
        post("/auth/sso/callback") {
            val req = runCatching { call.receive<SsoCallbackRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                return@post
            }
            val ip = call.request.origin.remoteHost
            val assertion = SsoAssertion(externalId = req.externalId, email = req.email, externalGroups = req.externalGroups)
            when (val result = authService.ssoLogin(assertion, ip)) {
                is SsoLoginResult.Success -> call.respond(HttpStatusCode.OK, SsoLoginResponse(result.accessToken, result.refreshToken))
                SsoLoginResult.NotConfigured -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("SSO is not configured"))
                SsoLoginResult.NoRoleMapped -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("No role mapping matched this user's groups"))
                SsoLoginResult.AccountDisabled -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Account is disabled"))
                SsoLoginResult.ProvisioningConflict -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Username conflict during provisioning"))
            }
        }

        authenticate("jwt-auth") {
            route("/users") {
                withRole(Role.ADMIN) {
                    get {
                        call.respond(HttpStatusCode.OK, userService.listUsers().map { it.toResponse() })
                    }

                    get("/audit-log") {
                        val username = call.request.queryParameters["username"]
                        val event = call.request.queryParameters["event"]?.let { runCatching { AuditEvent.valueOf(it) }.getOrNull() }
                        call.respond(HttpStatusCode.OK, AuditService.list(username, event).map { it.toResponse() })
                    }

                    post("/invite") {
                        val req = runCatching { call.receive<InviteUserRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val roles = parseRoles(req.roles) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role name"))
                            return@post
                        }
                        when (val result = authService.inviteUser(req.username, req.email, roles, req.storeIds.toSet())) {
                            is InviteResult.Success -> call.respond(
                                HttpStatusCode.Created,
                                InviteUserResponse(
                                    user = result.user.toResponse(),
                                    inviteToken = result.inviteToken,
                                    emailDelivered = result.emailMessageId != null
                                )
                            )
                            InviteResult.UsernameTaken -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Username is already taken"))
                        }
                    }

                    post("/sso/configure") {
                        val req = runCatching { call.receive<SsoConfigureRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val defaultRoles = parseRoles(req.defaultRoles) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role name"))
                            return@post
                        }
                        val mappings = mutableListOf<SsoRoleMapping>()
                        for (mapping in req.roleMappings) {
                            val role = runCatching { Role.valueOf(mapping.role) }.getOrElse {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role name: ${mapping.role}"))
                                return@post
                            }
                            mappings.add(SsoRoleMapping(mapping.externalGroup, role))
                        }
                        val config = ssoConfigService.configure(req.providerName, mappings, defaultRoles, req.enabled)
                        AuditService.record(AuditEvent.SSO_CONFIGURED, detail = "provider=${req.providerName} enabled=${req.enabled}")
                        call.respond(HttpStatusCode.OK, config.toResponse())
                    }

                    get("/sso/configuration") {
                        val config = ssoConfigService.getConfiguration()
                        if (config == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("SSO is not configured"))
                        else call.respond(HttpStatusCode.OK, config.toResponse())
                    }

                    get("/{id}") {
                        val user = userService.findById(call.parameters["id"]!!)
                        if (user == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                        else call.respond(HttpStatusCode.OK, user.toResponse())
                    }

                    patch("/{id}/roles") {
                        val id = call.parameters["id"]!!
                        val req = runCatching { call.receive<UpdateRolesRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@patch
                        }
                        val roles = parseRoles(req.roles) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid role name"))
                            return@patch
                        }
                        val changedBy = call.tokenClaims()?.userId
                        val updated = authService.updateUserRoles(id, roles, changedBy)
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                        else call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    patch("/{id}/store-access") {
                        val id = call.parameters["id"]!!
                        val req = runCatching { call.receive<UpdateStoreAccessRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@patch
                        }
                        val updated = authService.updateUserStoreAccess(id, req.storeIds.toSet())
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                        else call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    patch("/{id}/status") {
                        val id = call.parameters["id"]!!
                        val req = runCatching { call.receive<UpdateStatusRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@patch
                        }
                        val status = runCatching { UserStatus.valueOf(req.status) }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid status"))
                            return@patch
                        }
                        val changedBy = call.tokenClaims()?.userId
                        val updated = authService.setUserStatus(id, status, changedBy)
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                        else call.respond(HttpStatusCode.OK, updated.toResponse())
                    }
                }
            }
        }
    }
}
