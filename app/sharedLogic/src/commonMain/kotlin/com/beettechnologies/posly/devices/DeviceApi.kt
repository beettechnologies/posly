package com.beettechnologies.posly.devices

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class CreatePairCodeOutcome {
    data class Success(val response: PairCodeResponse) : CreatePairCodeOutcome()
    data class Rejected(val message: String) : CreatePairCodeOutcome()
    data object Forbidden : CreatePairCodeOutcome()
    data class NetworkError(val message: String) : CreatePairCodeOutcome()
}

sealed class EnrollDeviceOutcome {
    data class Success(val response: EnrollDeviceResponse) : EnrollDeviceOutcome()
    data class Rejected(val message: String) : EnrollDeviceOutcome()
    data class NetworkError(val message: String) : EnrollDeviceOutcome()
}

interface DeviceApi {
    suspend fun createPairCode(request: CreatePairCodeRequest): CreatePairCodeOutcome
    suspend fun enrollDevice(request: EnrollDeviceRequest): EnrollDeviceOutcome
}

class KtorDeviceApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : DeviceApi {

    override suspend fun createPairCode(request: CreatePairCodeRequest): CreatePairCodeOutcome = try {
        val response = httpClient.post("$baseUrl/devices/create-pair-code") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        when (response.status) {
            HttpStatusCode.Created -> CreatePairCodeOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> CreatePairCodeOutcome.Forbidden
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CreatePairCodeOutcome.Rejected(error?.error ?: "Invalid request")
            }
            else -> CreatePairCodeOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreatePairCodeOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun enrollDevice(request: EnrollDeviceRequest): EnrollDeviceOutcome = try {
        val response = httpClient.post("$baseUrl/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        when (response.status) {
            HttpStatusCode.OK -> EnrollDeviceOutcome.Success(response.body())
            HttpStatusCode.NotFound, HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                EnrollDeviceOutcome.Rejected(error?.error ?: "Invalid or expired pairing code")
            }
            else -> EnrollDeviceOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EnrollDeviceOutcome.NetworkError(e.message ?: "Network error")
    }
}
