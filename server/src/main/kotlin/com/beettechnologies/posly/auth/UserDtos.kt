package com.beettechnologies.posly.auth

import com.beettechnologies.posly.audit.AuditRecord
import com.beettechnologies.posly.model.User
import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val email: String?,
    val roles: List<String>,
    val storeIds: List<String>,
    val status: String,
    val mfaEnabled: Boolean,
    val roleVersion: Int,
    val externalId: String?
)

fun User.toResponse() = UserResponse(
    id = id,
    username = username,
    email = email,
    roles = roles.map { it.name },
    storeIds = storeIds.toList(),
    status = status.name,
    mfaEnabled = mfaEnabled,
    roleVersion = roleVersion,
    externalId = externalId
)

@Serializable
data class InviteUserRequest(val username: String, val email: String, val roles: List<String>, val storeIds: List<String> = emptyList())

@Serializable
data class InviteUserResponse(
    val user: UserResponse,
    /** Exposed because this project has no real mail server - a simulator page/test can redeem it directly instead of reading an inbox. */
    val inviteToken: String,
    val emailDelivered: Boolean
)

@Serializable
data class AcceptInviteRequest(val token: String, val newPassword: String)

@Serializable
data class UpdateRolesRequest(val roles: List<String>)

@Serializable
data class UpdateStoreAccessRequest(val storeIds: List<String>)

@Serializable
data class UpdateStatusRequest(val status: String)

@Serializable
data class AuditLogEntryResponse(
    val timestamp: String,
    val event: String,
    val username: String?,
    val userId: String?,
    val remoteIp: String?,
    val detail: String?
)

fun AuditRecord.toResponse() = AuditLogEntryResponse(
    timestamp = timestamp.toString(),
    event = event.name,
    username = username,
    userId = userId,
    remoteIp = remoteIp,
    detail = detail
)

@Serializable
data class SsoRoleMappingDto(val externalGroup: String, val role: String)

@Serializable
data class SsoConfigureRequest(
    val providerName: String,
    val roleMappings: List<SsoRoleMappingDto>,
    val defaultRoles: List<String>,
    val enabled: Boolean = true
)

@Serializable
data class SsoConfigurationResponse(
    val providerName: String,
    val enabled: Boolean,
    val roleMappings: List<SsoRoleMappingDto>,
    val defaultRoles: List<String>,
    val configuredAt: String
)

fun SsoConfiguration.toResponse() = SsoConfigurationResponse(
    providerName = providerName,
    enabled = enabled,
    roleMappings = roleMappings.map { SsoRoleMappingDto(it.externalGroup, it.role.name) },
    defaultRoles = defaultRoles.map { it.name },
    configuredAt = configuredAt.toString()
)

/** Stands in for the assertion a real SAML/OIDC handler would have produced - see [SsoAssertion]. */
@Serializable
data class SsoCallbackRequest(val externalId: String, val email: String, val externalGroups: List<String> = emptyList())

@Serializable
data class SsoLoginResponse(val accessToken: String, val refreshToken: String)
