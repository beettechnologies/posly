package com.beettechnologies.posly.apikeys

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class ApiKeyListResult {
    data class Success(val keys: List<ApiKeyResponse>) : ApiKeyListResult()
    data object Forbidden : ApiKeyListResult()
    data class NetworkError(val message: String) : ApiKeyListResult()
}

sealed class CreateApiKeyOutcome {
    data class Success(val created: ApiKeyCreatedResponse) : CreateApiKeyOutcome()
    data object Forbidden : CreateApiKeyOutcome()
    data class Rejected(val message: String) : CreateApiKeyOutcome()
    data class NetworkError(val message: String) : CreateApiKeyOutcome()
}

sealed class RevokeApiKeyOutcome {
    data class Success(val apiKey: ApiKeyResponse) : RevokeApiKeyOutcome()
    data object NotFound : RevokeApiKeyOutcome()
    data object AlreadyRevoked : RevokeApiKeyOutcome()
    data object Forbidden : RevokeApiKeyOutcome()
    data class NetworkError(val message: String) : RevokeApiKeyOutcome()
}

sealed class RotateApiKeyOutcome {
    data class Success(val created: ApiKeyCreatedResponse) : RotateApiKeyOutcome()
    data object NotFound : RotateApiKeyOutcome()
    data object Revoked : RotateApiKeyOutcome()
    data object Forbidden : RotateApiKeyOutcome()
    data class NetworkError(val message: String) : RotateApiKeyOutcome()
}

sealed class ApiKeyUsageResult {
    data class Success(val usage: List<ApiKeyUsageResponse>) : ApiKeyUsageResult()
    data object NotFound : ApiKeyUsageResult()
    data class NetworkError(val message: String) : ApiKeyUsageResult()
}

interface ApiKeyApi {
    suspend fun listKeys(): ApiKeyListResult
    suspend fun createKey(name: String, scopes: List<String>): CreateApiKeyOutcome
    suspend fun revokeKey(id: String): RevokeApiKeyOutcome
    suspend fun rotateKey(id: String): RotateApiKeyOutcome
    suspend fun getUsage(id: String): ApiKeyUsageResult
}

class KtorApiKeyApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ApiKeyApi {

    override suspend fun listKeys(): ApiKeyListResult = try {
        val response = httpClient.get("$baseUrl/api-keys")
        when (response.status) {
            HttpStatusCode.OK -> ApiKeyListResult.Success(response.body())
            HttpStatusCode.Forbidden -> ApiKeyListResult.Forbidden
            else -> ApiKeyListResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiKeyListResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun createKey(name: String, scopes: List<String>): CreateApiKeyOutcome = try {
        val response = httpClient.post("$baseUrl/api-keys") {
            contentType(ContentType.Application.Json)
            setBody(CreateApiKeyRequest(name, scopes))
        }
        when (response.status) {
            HttpStatusCode.Created -> CreateApiKeyOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> CreateApiKeyOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CreateApiKeyOutcome.Rejected(error?.error ?: "Unable to create API key")
            }
            else -> CreateApiKeyOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreateApiKeyOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun revokeKey(id: String): RevokeApiKeyOutcome = try {
        val response = httpClient.post("$baseUrl/api-keys/$id/revoke")
        when (response.status) {
            HttpStatusCode.OK -> RevokeApiKeyOutcome.Success(response.body())
            HttpStatusCode.NotFound -> RevokeApiKeyOutcome.NotFound
            HttpStatusCode.Conflict -> RevokeApiKeyOutcome.AlreadyRevoked
            HttpStatusCode.Forbidden -> RevokeApiKeyOutcome.Forbidden
            else -> RevokeApiKeyOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RevokeApiKeyOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun rotateKey(id: String): RotateApiKeyOutcome = try {
        val response = httpClient.post("$baseUrl/api-keys/$id/rotate")
        when (response.status) {
            HttpStatusCode.OK -> RotateApiKeyOutcome.Success(response.body())
            HttpStatusCode.NotFound -> RotateApiKeyOutcome.NotFound
            HttpStatusCode.Conflict -> RotateApiKeyOutcome.Revoked
            HttpStatusCode.Forbidden -> RotateApiKeyOutcome.Forbidden
            else -> RotateApiKeyOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RotateApiKeyOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getUsage(id: String): ApiKeyUsageResult = try {
        val response = httpClient.get("$baseUrl/api-keys/$id/usage")
        when (response.status) {
            HttpStatusCode.OK -> ApiKeyUsageResult.Success(response.body())
            HttpStatusCode.NotFound -> ApiKeyUsageResult.NotFound
            else -> ApiKeyUsageResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiKeyUsageResult.NetworkError(e.message ?: "Network error")
    }
}
