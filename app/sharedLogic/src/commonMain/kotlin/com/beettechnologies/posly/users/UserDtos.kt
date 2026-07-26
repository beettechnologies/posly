package com.beettechnologies.posly.users

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

@Serializable
data class InviteUserRequest(val username: String, val email: String, val roles: List<String>, val storeIds: List<String> = emptyList())

@Serializable
data class InviteUserResponse(val user: UserResponse, val inviteToken: String, val emailDelivered: Boolean)

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
