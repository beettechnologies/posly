package com.beettechnologies.posly.finance

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class ListSchedulesOutcome {
    data class Success(val schedules: List<ScheduledReportResponse>) : ListSchedulesOutcome()
    data object Forbidden : ListSchedulesOutcome()
    data class NetworkError(val message: String) : ListSchedulesOutcome()
}

sealed class CreateScheduleOutcome {
    data class Success(val schedule: ScheduledReportResponse) : CreateScheduleOutcome()
    data object Forbidden : CreateScheduleOutcome()
    data class Rejected(val message: String) : CreateScheduleOutcome()
    data class NetworkError(val message: String) : CreateScheduleOutcome()
}

sealed class DeleteScheduleOutcome {
    data object Success : DeleteScheduleOutcome()
    data object Forbidden : DeleteScheduleOutcome()
    data object NotFound : DeleteScheduleOutcome()
    data class NetworkError(val message: String) : DeleteScheduleOutcome()
}

sealed class RunScheduleOutcome {
    data class Success(val run: ScheduledReportRunResponse) : RunScheduleOutcome()
    data object Forbidden : RunScheduleOutcome()
    data object NotFound : RunScheduleOutcome()
    data class NetworkError(val message: String) : RunScheduleOutcome()
}

sealed class ListRunsOutcome {
    data class Success(val runs: List<ScheduledReportRunResponse>) : ListRunsOutcome()
    data object Forbidden : ListRunsOutcome()
    data object NotFound : ListRunsOutcome()
    data class NetworkError(val message: String) : ListRunsOutcome()
}

interface FinanceReportApi {
    suspend fun listSchedules(storeId: String? = null): ListSchedulesOutcome
    suspend fun createSchedule(request: CreateScheduleRequest): CreateScheduleOutcome
    suspend fun deleteSchedule(id: String): DeleteScheduleOutcome
    suspend fun runScheduleNow(id: String): RunScheduleOutcome
    suspend fun listRuns(scheduleId: String): ListRunsOutcome
}

class KtorFinanceReportApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : FinanceReportApi {

    override suspend fun listSchedules(storeId: String?): ListSchedulesOutcome = try {
        val response = httpClient.get("$baseUrl/finance/reports/schedules") {
            url { storeId?.let { parameters.append("storeId", it) } }
        }
        when (response.status) {
            HttpStatusCode.OK -> ListSchedulesOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> ListSchedulesOutcome.Forbidden
            else -> ListSchedulesOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ListSchedulesOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun createSchedule(request: CreateScheduleRequest): CreateScheduleOutcome = try {
        val response = httpClient.post("$baseUrl/finance/reports/schedules") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        when (response.status) {
            HttpStatusCode.Created -> CreateScheduleOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> CreateScheduleOutcome.Forbidden
            HttpStatusCode.BadRequest, HttpStatusCode.NotFound -> {
                val error = runCatching { response.body<ErrorResponse>() }.getOrNull()
                CreateScheduleOutcome.Rejected(error?.error ?: "Unable to create schedule")
            }
            else -> CreateScheduleOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CreateScheduleOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun deleteSchedule(id: String): DeleteScheduleOutcome = try {
        val response = httpClient.delete("$baseUrl/finance/reports/schedules/$id")
        when (response.status) {
            HttpStatusCode.NoContent -> DeleteScheduleOutcome.Success
            HttpStatusCode.Forbidden -> DeleteScheduleOutcome.Forbidden
            HttpStatusCode.NotFound -> DeleteScheduleOutcome.NotFound
            else -> DeleteScheduleOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DeleteScheduleOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun runScheduleNow(id: String): RunScheduleOutcome = try {
        val response = httpClient.post("$baseUrl/finance/reports/schedules/$id/run-now")
        when (response.status) {
            HttpStatusCode.OK -> RunScheduleOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> RunScheduleOutcome.Forbidden
            HttpStatusCode.NotFound -> RunScheduleOutcome.NotFound
            else -> RunScheduleOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RunScheduleOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun listRuns(scheduleId: String): ListRunsOutcome = try {
        val response = httpClient.get("$baseUrl/finance/reports/schedules/$scheduleId/runs")
        when (response.status) {
            HttpStatusCode.OK -> ListRunsOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> ListRunsOutcome.Forbidden
            HttpStatusCode.NotFound -> ListRunsOutcome.NotFound
            else -> ListRunsOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ListRunsOutcome.NetworkError(e.message ?: "Network error")
    }
}
