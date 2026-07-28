package com.beettechnologies.posly.stores

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class StoreResult {
    data class Success(val store: StoreResponse) : StoreResult()
    data class ValidationError(val message: String) : StoreResult()
    data object NotFound : StoreResult()
    data object Forbidden : StoreResult()
    data class NetworkError(val message: String) : StoreResult()
}

sealed class StoreListResult {
    data class Success(val stores: List<StoreResponse>) : StoreListResult()
    data object Forbidden : StoreListResult()
    data class NetworkError(val message: String) : StoreListResult()
}

sealed class DeleteStoreResult {
    data object Success : DeleteStoreResult()
    data object NotFound : DeleteStoreResult()
    data object Forbidden : DeleteStoreResult()
    data class NetworkError(val message: String) : DeleteStoreResult()
}

sealed class UploadLogoOutcome {
    data class Success(val response: LogoUploadResponse) : UploadLogoOutcome()
    data class Rejected(val message: String) : UploadLogoOutcome()
    data object Forbidden : UploadLogoOutcome()
    data class NetworkError(val message: String) : UploadLogoOutcome()
}

interface StoreApi {
    suspend fun createStore(request: CreateStoreRequest): StoreResult
    suspend fun listStores(): StoreListResult
    suspend fun getStore(id: String): StoreResult
    suspend fun updateStore(id: String, request: UpdateStoreRequest): StoreResult
    suspend fun deleteStore(id: String): DeleteStoreResult
    suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome
}

class KtorStoreApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : StoreApi {

    override suspend fun createStore(request: CreateStoreRequest): StoreResult = try {
        toStoreResult(
            httpClient.post("$baseUrl/stores") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StoreResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listStores(): StoreListResult = try {
        val response = httpClient.get("$baseUrl/stores")
        when (response.status) {
            HttpStatusCode.OK -> StoreListResult.Success(response.body())
            HttpStatusCode.Forbidden -> StoreListResult.Forbidden
            else -> StoreListResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StoreListResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getStore(id: String): StoreResult = try {
        toStoreResult(httpClient.get("$baseUrl/stores/$id"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StoreResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun updateStore(id: String, request: UpdateStoreRequest): StoreResult = try {
        toStoreResult(
            httpClient.put("$baseUrl/stores/$id") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StoreResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun deleteStore(id: String): DeleteStoreResult = try {
        val response = httpClient.delete("$baseUrl/stores/$id")
        when (response.status) {
            HttpStatusCode.NoContent -> DeleteStoreResult.Success
            HttpStatusCode.NotFound -> DeleteStoreResult.NotFound
            HttpStatusCode.Forbidden -> DeleteStoreResult.Forbidden
            else -> DeleteStoreResult.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DeleteStoreResult.NetworkError(e.message ?: "Network error")
    }

    override suspend fun uploadLogo(storeId: String, fileName: String, bytes: ByteArray): UploadLogoOutcome = try {
        val response = httpClient.post("$baseUrl/stores/$storeId/logo") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        })
                    }
                )
            )
        }
        when (response.status) {
            HttpStatusCode.Created -> UploadLogoOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> UploadLogoOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                UploadLogoOutcome.Rejected(error?.error ?: "Invalid request")
            }
            else -> UploadLogoOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UploadLogoOutcome.NetworkError(e.message ?: "Network error")
    }

    private suspend fun toStoreResult(response: HttpResponse): StoreResult = when (response.status) {
        HttpStatusCode.OK, HttpStatusCode.Created -> StoreResult.Success(response.body())
        HttpStatusCode.NotFound -> StoreResult.NotFound
        HttpStatusCode.Forbidden -> StoreResult.Forbidden
        HttpStatusCode.BadRequest -> {
            val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
            StoreResult.ValidationError(error?.error ?: "Invalid request")
        }
        else -> StoreResult.NetworkError("Server error (${response.status.value})")
    }
}
