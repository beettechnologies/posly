package com.beettechnologies.posly.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class LoginOutcome {
    data class Success(val accessToken: String, val refreshToken: String) : LoginOutcome()
    data class MfaRequired(val mfaToken: String) : LoginOutcome()
    data class InvalidCredentials(val message: String) : LoginOutcome()
    data class NetworkError(val message: String) : LoginOutcome()
}

sealed class MfaOutcome {
    data class Success(val accessToken: String, val refreshToken: String) : MfaOutcome()
    data class InvalidCode(val message: String) : MfaOutcome()
    data class NetworkError(val message: String) : MfaOutcome()
}

sealed class RefreshOutcome {
    data class Success(val accessToken: String) : RefreshOutcome()
    data object Unauthorized : RefreshOutcome()
    data class NetworkError(val message: String) : RefreshOutcome()
}

/**
 * Thin wrapper over the auth endpoints. Does not itself hold any state;
 * see AuthRepository for session/token orchestration.
 */
interface AuthApi {
    suspend fun login(username: String, password: String): LoginOutcome
    suspend fun verifyMfa(mfaToken: String, code: String): MfaOutcome
    suspend fun refresh(refreshToken: String): RefreshOutcome
    suspend fun logout(refreshToken: String)
}

class KtorAuthApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : AuthApi {
    override suspend fun login(username: String, password: String): LoginOutcome = try {
        val response = httpClient.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<LoginResponse>()
                when {
                    body.mfaRequired && body.mfaToken != null -> LoginOutcome.MfaRequired(body.mfaToken)
                    body.accessToken != null && body.refreshToken != null ->
                        LoginOutcome.Success(body.accessToken, body.refreshToken)
                    else -> LoginOutcome.NetworkError("Unexpected response from server")
                }
            }
            HttpStatusCode.Unauthorized -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                LoginOutcome.InvalidCredentials(error?.error ?: "Invalid username or password")
            }
            else -> LoginOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoginOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun verifyMfa(mfaToken: String, code: String): MfaOutcome = try {
        val response = httpClient.post("$baseUrl/auth/mfa/verify") {
            contentType(ContentType.Application.Json)
            setBody(MfaVerifyRequest(mfaToken, code))
        }
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<MfaVerifyResponse>()
                MfaOutcome.Success(body.accessToken, body.refreshToken)
            }
            HttpStatusCode.Unauthorized -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                MfaOutcome.InvalidCode(error?.error ?: "Invalid or expired code")
            }
            else -> MfaOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MfaOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun refresh(refreshToken: String): RefreshOutcome = try {
        val response = httpClient.post("$baseUrl/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }
        when (response.status) {
            HttpStatusCode.OK -> RefreshOutcome.Success(response.body<RefreshResponse>().accessToken)
            HttpStatusCode.Unauthorized -> RefreshOutcome.Unauthorized
            else -> RefreshOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RefreshOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun logout(refreshToken: String) {
        runCatching {
            httpClient.post("$baseUrl/auth/logout") {
                contentType(ContentType.Application.Json)
                setBody(LogoutRequest(refreshToken))
            }
        }
    }
}
