package com.beettechnologies.posly.flags

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

sealed class FeatureFlagListResult {
    data class Success(val flags: List<FeatureFlagResponse>) : FeatureFlagListResult()
    data object Forbidden : FeatureFlagListResult()
    data class NetworkError(val message: String) : FeatureFlagListResult()
}

sealed class CreateFeatureFlagOutcome {
    data class Success(val flag: FeatureFlagResponse) : CreateFeatureFlagOutcome()
    data object DuplicateKey : CreateFeatureFlagOutcome()
    data object Forbidden : CreateFeatureFlagOutcome()
    data class Rejected(val message: String) : CreateFeatureFlagOutcome()
    data class NetworkError(val message: String) : CreateFeatureFlagOutcome()
}

sealed class UpdateFeatureFlagOutcome {
    data class Success(val flag: FeatureFlagResponse) : UpdateFeatureFlagOutcome()
    data object NotFound : UpdateFeatureFlagOutcome()
    data object Forbidden : UpdateFeatureFlagOutcome()
    data class Rejected(val message: String) : UpdateFeatureFlagOutcome()
    data class NetworkError(val message: String) : UpdateFeatureFlagOutcome()
}

sealed class EvaluateFlagResult {
    data class Success(val evaluation: FlagEvaluationResponse) : EvaluateFlagResult()
    data class NetworkError(val message: String) : EvaluateFlagResult()
}

interface FeatureFlagApi {
    suspend fun listFlags(): FeatureFlagListResult
    suspend fun createFlag(key: String, description: String, enabled: Boolean = false, rolloutPercentage: Int = 0): CreateFeatureFlagOutcome
    suspend fun updateFlag(
        key: String,
        enabled: Boolean? = null,
        rolloutPercentage: Int? = null,
        enabledStoreIds: List<String>? = null
    ): UpdateFeatureFlagOutcome
    suspend fun evaluate(key: String, storeId: String): EvaluateFlagResult
    suspend fun listAuditLog(event: String? = null): List<FeatureFlagAuditLogEntryResponse>
}

class KtorFeatureFlagApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : FeatureFlagApi {

    override suspend fun listFlags(): FeatureFlagListResult = try {
        val response = httpClient.get("$baseUrl/feature-flags")
        when (response.status) {
            HttpStatusCode.OK -> FeatureFlagListResult.Success(response.body())
            HttpStatusCode.Forbidden -> FeatureFlagListResult.Forbidden
            else -> FeatureFlagListResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FeatureFlagListResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun createFlag(key: String, description: String, enabled: Boolean, rolloutPercentage: Int): CreateFeatureFlagOutcome = try {
        val response = httpClient.post("$baseUrl/feature-flags") {
            contentType(ContentType.Application.Json)
            setBody(CreateFeatureFlagRequest(key, description, enabled, rolloutPercentage))
        }
        when (response.status) {
            HttpStatusCode.Created -> CreateFeatureFlagOutcome.Success(response.body())
            HttpStatusCode.Conflict -> CreateFeatureFlagOutcome.DuplicateKey
            HttpStatusCode.Forbidden -> CreateFeatureFlagOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CreateFeatureFlagOutcome.Rejected(error?.error ?: "Unable to create flag")
            }
            else -> CreateFeatureFlagOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreateFeatureFlagOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun updateFlag(
        key: String,
        enabled: Boolean?,
        rolloutPercentage: Int?,
        enabledStoreIds: List<String>?
    ): UpdateFeatureFlagOutcome = try {
        val response = httpClient.patch("$baseUrl/feature-flags/$key") {
            contentType(ContentType.Application.Json)
            setBody(UpdateFeatureFlagRequest(enabled, rolloutPercentage, enabledStoreIds))
        }
        when (response.status) {
            HttpStatusCode.OK -> UpdateFeatureFlagOutcome.Success(response.body())
            HttpStatusCode.NotFound -> UpdateFeatureFlagOutcome.NotFound
            HttpStatusCode.Forbidden -> UpdateFeatureFlagOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                UpdateFeatureFlagOutcome.Rejected(error?.error ?: "Unable to update flag")
            }
            else -> UpdateFeatureFlagOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateFeatureFlagOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun evaluate(key: String, storeId: String): EvaluateFlagResult = try {
        val response = httpClient.get("$baseUrl/feature-flags/$key/evaluate") {
            url { parameters.append("storeId", storeId) }
        }
        when (response.status) {
            HttpStatusCode.OK -> EvaluateFlagResult.Success(response.body())
            else -> EvaluateFlagResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EvaluateFlagResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listAuditLog(event: String?): List<FeatureFlagAuditLogEntryResponse> {
        val response = httpClient.get("$baseUrl/feature-flags/audit-log") {
            url {
                event?.let { parameters.append("event", it) }
            }
        }
        return response.body()
    }
}
