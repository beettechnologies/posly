package com.beettechnologies.posly.stores

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class TaxProfileResult {
    data class Success(val profile: TaxProfileResponse) : TaxProfileResult()
    data class ValidationError(val message: String) : TaxProfileResult()
    data object NotFound : TaxProfileResult()
    data object Forbidden : TaxProfileResult()
    data class NetworkError(val message: String) : TaxProfileResult()
}

sealed class TaxProfileListResult {
    data class Success(val profiles: List<TaxProfileResponse>) : TaxProfileListResult()
    data object Forbidden : TaxProfileListResult()
    data class NetworkError(val message: String) : TaxProfileListResult()
}

sealed class DeleteTaxProfileResult {
    data object Success : DeleteTaxProfileResult()
    data object NotFound : DeleteTaxProfileResult()
    data object Forbidden : DeleteTaxProfileResult()
    data class NetworkError(val message: String) : DeleteTaxProfileResult()
}

sealed class CalculateTaxResult {
    data class Success(val response: CalculateTaxResponse) : CalculateTaxResult()
    data object NotFound : CalculateTaxResult()
    data class NetworkError(val message: String) : CalculateTaxResult()
}

interface TaxProfileApi {
    suspend fun createProfile(request: CreateTaxProfileRequest): TaxProfileResult
    suspend fun listProfiles(): TaxProfileListResult
    suspend fun getProfile(id: String): TaxProfileResult
    suspend fun updateProfile(id: String, request: UpdateTaxProfileRequest): TaxProfileResult
    suspend fun deleteProfile(id: String): DeleteTaxProfileResult
    suspend fun calculateTax(id: String, amount: Double): CalculateTaxResult
}

class KtorTaxProfileApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : TaxProfileApi {

    override suspend fun createProfile(request: CreateTaxProfileRequest): TaxProfileResult = try {
        toResult(
            httpClient.post("$baseUrl/tax-profiles") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TaxProfileResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listProfiles(): TaxProfileListResult = try {
        val response = httpClient.get("$baseUrl/tax-profiles")
        when (response.status) {
            HttpStatusCode.OK -> TaxProfileListResult.Success(response.body())
            HttpStatusCode.Forbidden -> TaxProfileListResult.Forbidden
            else -> TaxProfileListResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TaxProfileListResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getProfile(id: String): TaxProfileResult = try {
        toResult(httpClient.get("$baseUrl/tax-profiles/$id"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TaxProfileResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun updateProfile(id: String, request: UpdateTaxProfileRequest): TaxProfileResult = try {
        toResult(
            httpClient.put("$baseUrl/tax-profiles/$id") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TaxProfileResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun deleteProfile(id: String): DeleteTaxProfileResult = try {
        val response = httpClient.delete("$baseUrl/tax-profiles/$id")
        when (response.status) {
            HttpStatusCode.NoContent -> DeleteTaxProfileResult.Success
            HttpStatusCode.NotFound -> DeleteTaxProfileResult.NotFound
            HttpStatusCode.Forbidden -> DeleteTaxProfileResult.Forbidden
            else -> DeleteTaxProfileResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DeleteTaxProfileResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun calculateTax(id: String, amount: Double): CalculateTaxResult = try {
        val response = httpClient.post("$baseUrl/tax-profiles/$id/calculate") {
            contentType(ContentType.Application.Json)
            setBody(CalculateTaxRequest(amount))
        }
        when (response.status) {
            HttpStatusCode.OK -> CalculateTaxResult.Success(response.body())
            HttpStatusCode.NotFound -> CalculateTaxResult.NotFound
            else -> CalculateTaxResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CalculateTaxResult.NetworkError(e.message ?: "Network error")
    }

    private suspend fun toResult(response: HttpResponse): TaxProfileResult = when (response.status) {
        HttpStatusCode.OK, HttpStatusCode.Created -> TaxProfileResult.Success(response.body())
        HttpStatusCode.NotFound -> TaxProfileResult.NotFound
        HttpStatusCode.Forbidden -> TaxProfileResult.Forbidden
        HttpStatusCode.BadRequest -> {
            val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
            TaxProfileResult.ValidationError(error?.error ?: "Invalid request")
        }
        else -> TaxProfileResult.NetworkError("Server error (${response.status.value})")
    }
}
