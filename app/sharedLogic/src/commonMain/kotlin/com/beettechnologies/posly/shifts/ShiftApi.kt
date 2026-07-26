package com.beettechnologies.posly.shifts

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
import kotlinx.serialization.Serializable

@Serializable
private data class RequiresOverrideOrNoteResponse(val error: String, val variance: Double, val threshold: Double)

sealed class OpenShiftOutcome {
    data class Success(val shift: ShiftResponse) : OpenShiftOutcome()
    data object StoreNotFound : OpenShiftOutcome()
    data object ShiftAlreadyOpen : OpenShiftOutcome()
    data class Rejected(val message: String) : OpenShiftOutcome()
    data class NetworkError(val message: String) : OpenShiftOutcome()
}

sealed class CloseShiftOutcome {
    data class Success(val shift: ShiftResponse) : CloseShiftOutcome()
    data object NotFound : CloseShiftOutcome()
    data object NotOpen : CloseShiftOutcome()
    /** Over-threshold variance with neither a note nor a manager/admin closer - retry with one or the other. */
    data class RequiresOverrideOrNote(val variance: Double, val threshold: Double) : CloseShiftOutcome()
    data class Rejected(val message: String) : CloseShiftOutcome()
    data class NetworkError(val message: String) : CloseShiftOutcome()
}

sealed class GetShiftOutcome {
    data class Success(val shift: ShiftResponse) : GetShiftOutcome()
    data object NotFound : GetShiftOutcome()
    data class NetworkError(val message: String) : GetShiftOutcome()
}

sealed class ExpectedCashOutcome {
    data class Success(val expectedCash: Double, val asOf: String) : ExpectedCashOutcome()
    data object NotFound : ExpectedCashOutcome()
    data class NetworkError(val message: String) : ExpectedCashOutcome()
}

interface ShiftApi {
    suspend fun openShift(storeId: String, openingFloat: Double): OpenShiftOutcome
    suspend fun closeShift(shiftId: String, closingCount: Double, note: String? = null): CloseShiftOutcome
    suspend fun getShift(id: String): GetShiftOutcome
    suspend fun listShifts(storeId: String? = null, cashierId: String? = null): List<ShiftResponse>
    suspend fun getExpectedCash(shiftId: String): ExpectedCashOutcome
}

class KtorShiftApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ShiftApi {

    override suspend fun openShift(storeId: String, openingFloat: Double): OpenShiftOutcome = try {
        val response = httpClient.post("$baseUrl/shifts/open") {
            contentType(ContentType.Application.Json)
            setBody(OpenShiftRequest(storeId, openingFloat))
        }
        when (response.status) {
            HttpStatusCode.Created -> OpenShiftOutcome.Success(response.body())
            HttpStatusCode.NotFound -> OpenShiftOutcome.StoreNotFound
            HttpStatusCode.Conflict -> OpenShiftOutcome.ShiftAlreadyOpen
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                OpenShiftOutcome.Rejected(error?.error ?: "Unable to open shift")
            }
            else -> OpenShiftOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        OpenShiftOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun closeShift(shiftId: String, closingCount: Double, note: String?): CloseShiftOutcome = try {
        val response = httpClient.post("$baseUrl/shifts/$shiftId/close") {
            contentType(ContentType.Application.Json)
            setBody(CloseShiftRequest(closingCount, note))
        }
        when (response.status) {
            HttpStatusCode.OK -> CloseShiftOutcome.Success(response.body())
            HttpStatusCode.NotFound -> CloseShiftOutcome.NotFound
            HttpStatusCode.Conflict -> {
                val requirement = runCatching { response.body<RequiresOverrideOrNoteResponse>() }.getOrNull()
                if (requirement != null) {
                    CloseShiftOutcome.RequiresOverrideOrNote(requirement.variance, requirement.threshold)
                } else {
                    CloseShiftOutcome.NotOpen
                }
            }
            HttpStatusCode.BadRequest -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CloseShiftOutcome.Rejected(error?.error ?: "Unable to close shift")
            }
            else -> CloseShiftOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CloseShiftOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getShift(id: String): GetShiftOutcome = try {
        val response = httpClient.get("$baseUrl/shifts/$id")
        when (response.status) {
            HttpStatusCode.OK -> GetShiftOutcome.Success(response.body())
            HttpStatusCode.NotFound -> GetShiftOutcome.NotFound
            else -> GetShiftOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GetShiftOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listShifts(storeId: String?, cashierId: String?): List<ShiftResponse> {
        val response = httpClient.get("$baseUrl/shifts") {
            url {
                storeId?.let { parameters.append("storeId", it) }
                cashierId?.let { parameters.append("cashierId", it) }
            }
        }
        return response.body()
    }

    override suspend fun getExpectedCash(shiftId: String): ExpectedCashOutcome = try {
        val response = httpClient.get("$baseUrl/shifts/$shiftId/expected-cash")
        when (response.status) {
            HttpStatusCode.OK -> {
                val body = response.body<ExpectedCashResponse>()
                ExpectedCashOutcome.Success(body.expectedCash, body.asOf)
            }
            HttpStatusCode.NotFound -> ExpectedCashOutcome.NotFound
            else -> ExpectedCashOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ExpectedCashOutcome.NetworkError(e.message ?: "Network error")
    }
}
