package com.beettechnologies.posly.users

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class InviteUserOutcome {
    data class Success(val user: UserResponse, val inviteToken: String, val emailDelivered: Boolean) : InviteUserOutcome()
    data object UsernameTaken : InviteUserOutcome()
    data object Forbidden : InviteUserOutcome()
    data class Rejected(val message: String) : InviteUserOutcome()
    data class NetworkError(val message: String) : InviteUserOutcome()
}

sealed class AcceptInviteOutcome {
    data object Success : AcceptInviteOutcome()
    data object TokenInvalid : AcceptInviteOutcome()
    data object NotInvited : AcceptInviteOutcome()
    data class NetworkError(val message: String) : AcceptInviteOutcome()
}

sealed class UserResult {
    data class Success(val user: UserResponse) : UserResult()
    data object NotFound : UserResult()
    data object Forbidden : UserResult()
    data class Rejected(val message: String) : UserResult()
    data class NetworkError(val message: String) : UserResult()
}

sealed class UserListResult {
    data class Success(val users: List<UserResponse>) : UserListResult()
    data object Forbidden : UserListResult()
    data class NetworkError(val message: String) : UserListResult()
}

sealed class SsoConfigureOutcome {
    data class Success(val configuration: SsoConfigurationResponse) : SsoConfigureOutcome()
    data object Forbidden : SsoConfigureOutcome()
    data class Rejected(val message: String) : SsoConfigureOutcome()
    data class NetworkError(val message: String) : SsoConfigureOutcome()
}

sealed class SsoConfigurationResult {
    data class Success(val configuration: SsoConfigurationResponse) : SsoConfigurationResult()
    data object NotConfigured : SsoConfigurationResult()
    data object Forbidden : SsoConfigurationResult()
    data class NetworkError(val message: String) : SsoConfigurationResult()
}

interface UserApi {
    suspend fun listUsers(): UserListResult
    suspend fun getUser(id: String): UserResult
    suspend fun inviteUser(username: String, email: String, roles: List<String>, storeIds: List<String> = emptyList()): InviteUserOutcome
    suspend fun acceptInvite(token: String, newPassword: String): AcceptInviteOutcome
    suspend fun updateRoles(userId: String, roles: List<String>): UserResult
    suspend fun updateStoreAccess(userId: String, storeIds: List<String>): UserResult
    suspend fun updateStatus(userId: String, status: String): UserResult
    suspend fun listAuditLog(username: String? = null, event: String? = null): List<AuditLogEntryResponse>
    suspend fun configureSso(
        providerName: String,
        roleMappings: List<SsoRoleMappingDto>,
        defaultRoles: List<String>,
        enabled: Boolean = true
    ): SsoConfigureOutcome
    suspend fun getSsoConfiguration(): SsoConfigurationResult
}

class KtorUserApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : UserApi {

    override suspend fun listUsers(): UserListResult = try {
        val response = httpClient.get("$baseUrl/users")
        when (response.status) {
            HttpStatusCode.OK -> UserListResult.Success(response.body())
            HttpStatusCode.Forbidden -> UserListResult.Forbidden
            else -> UserListResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UserListResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getUser(id: String): UserResult = try {
        val response = httpClient.get("$baseUrl/users/$id")
        when (response.status) {
            HttpStatusCode.OK -> UserResult.Success(response.body())
            HttpStatusCode.NotFound -> UserResult.NotFound
            HttpStatusCode.Forbidden -> UserResult.Forbidden
            else -> UserResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UserResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun inviteUser(username: String, email: String, roles: List<String>, storeIds: List<String>): InviteUserOutcome = try {
        val response = httpClient.post("$baseUrl/users/invite") {
            contentType(ContentType.Application.Json)
            setBody(InviteUserRequest(username, email, roles, storeIds))
        }
        when (response.status) {
            HttpStatusCode.Created -> {
                val body = response.body<InviteUserResponse>()
                InviteUserOutcome.Success(body.user, body.inviteToken, body.emailDelivered)
            }
            HttpStatusCode.Conflict -> InviteUserOutcome.UsernameTaken
            HttpStatusCode.Forbidden -> InviteUserOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                InviteUserOutcome.Rejected(error?.error ?: "Unable to invite user")
            }
            else -> InviteUserOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        InviteUserOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun acceptInvite(token: String, newPassword: String): AcceptInviteOutcome = try {
        val response = httpClient.post("$baseUrl/users/accept-invite") {
            contentType(ContentType.Application.Json)
            setBody(AcceptInviteRequest(token, newPassword))
        }
        when (response.status) {
            HttpStatusCode.NoContent -> AcceptInviteOutcome.Success
            HttpStatusCode.Unauthorized -> AcceptInviteOutcome.TokenInvalid
            HttpStatusCode.Conflict -> AcceptInviteOutcome.NotInvited
            HttpStatusCode.NotFound -> AcceptInviteOutcome.TokenInvalid
            else -> AcceptInviteOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AcceptInviteOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun updateRoles(userId: String, roles: List<String>): UserResult = try {
        val response = httpClient.patch("$baseUrl/users/$userId/roles") {
            contentType(ContentType.Application.Json)
            setBody(UpdateRolesRequest(roles))
        }
        when (response.status) {
            HttpStatusCode.OK -> UserResult.Success(response.body())
            HttpStatusCode.NotFound -> UserResult.NotFound
            HttpStatusCode.Forbidden -> UserResult.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                UserResult.Rejected(error?.error ?: "Unable to update roles")
            }
            else -> UserResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UserResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun updateStoreAccess(userId: String, storeIds: List<String>): UserResult = try {
        val response = httpClient.patch("$baseUrl/users/$userId/store-access") {
            contentType(ContentType.Application.Json)
            setBody(UpdateStoreAccessRequest(storeIds))
        }
        when (response.status) {
            HttpStatusCode.OK -> UserResult.Success(response.body())
            HttpStatusCode.NotFound -> UserResult.NotFound
            HttpStatusCode.Forbidden -> UserResult.Forbidden
            else -> UserResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UserResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun updateStatus(userId: String, status: String): UserResult = try {
        val response = httpClient.patch("$baseUrl/users/$userId/status") {
            contentType(ContentType.Application.Json)
            setBody(UpdateStatusRequest(status))
        }
        when (response.status) {
            HttpStatusCode.OK -> UserResult.Success(response.body())
            HttpStatusCode.NotFound -> UserResult.NotFound
            HttpStatusCode.Forbidden -> UserResult.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                UserResult.Rejected(error?.error ?: "Unable to update status")
            }
            else -> UserResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UserResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listAuditLog(username: String?, event: String?): List<AuditLogEntryResponse> {
        val response = httpClient.get("$baseUrl/users/audit-log") {
            url {
                username?.let { parameters.append("username", it) }
                event?.let { parameters.append("event", it) }
            }
        }
        return response.body()
    }

    override suspend fun configureSso(
        providerName: String,
        roleMappings: List<SsoRoleMappingDto>,
        defaultRoles: List<String>,
        enabled: Boolean
    ): SsoConfigureOutcome = try {
        val response = httpClient.post("$baseUrl/users/sso/configure") {
            contentType(ContentType.Application.Json)
            setBody(SsoConfigureRequest(providerName, roleMappings, defaultRoles, enabled))
        }
        when (response.status) {
            HttpStatusCode.OK -> SsoConfigureOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> SsoConfigureOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                SsoConfigureOutcome.Rejected(error?.error ?: "Unable to configure SSO")
            }
            else -> SsoConfigureOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SsoConfigureOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getSsoConfiguration(): SsoConfigurationResult = try {
        val response = httpClient.get("$baseUrl/users/sso/configuration")
        when (response.status) {
            HttpStatusCode.OK -> SsoConfigurationResult.Success(response.body())
            HttpStatusCode.NotFound -> SsoConfigurationResult.NotConfigured
            HttpStatusCode.Forbidden -> SsoConfigurationResult.Forbidden
            else -> SsoConfigurationResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SsoConfigurationResult.NetworkError(e.message ?: "Network error")
    }
}
