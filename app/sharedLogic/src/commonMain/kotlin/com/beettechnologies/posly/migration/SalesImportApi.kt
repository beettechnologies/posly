package com.beettechnologies.posly.migration

import com.beettechnologies.posly.auth.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

sealed class UploadSalesCsvOutcome {
    data class Success(val response: UploadSalesCsvResponse) : UploadSalesCsvOutcome()
    data class Rejected(val message: String) : UploadSalesCsvOutcome()
    data object Forbidden : UploadSalesCsvOutcome()
    data class NetworkError(val message: String) : UploadSalesCsvOutcome()
}

sealed class SalesDryRunOutcome {
    data class Success(val response: SalesDryRunResponse) : SalesDryRunOutcome()
    data object FileNotFound : SalesDryRunOutcome()
    data class Rejected(val message: String) : SalesDryRunOutcome()
    data class NetworkError(val message: String) : SalesDryRunOutcome()
}

sealed class StartSalesImportOutcome {
    data class Success(val job: SalesImportJobResponse) : StartSalesImportOutcome()
    data object FileNotFound : StartSalesImportOutcome()
    data class Rejected(val message: String) : StartSalesImportOutcome()
    data class NetworkError(val message: String) : StartSalesImportOutcome()
}

sealed class SalesImportJobOutcome {
    data class Success(val job: SalesImportJobResponse) : SalesImportJobOutcome()
    data object NotFound : SalesImportJobOutcome()
    data class NetworkError(val message: String) : SalesImportJobOutcome()
}

sealed class SalesReconciliationOutcome {
    data class Success(val report: SalesReconciliationReportResponse) : SalesReconciliationOutcome()
    data object NotFound : SalesReconciliationOutcome()
    data class Rejected(val message: String) : SalesReconciliationOutcome()
    data class NetworkError(val message: String) : SalesReconciliationOutcome()
}

sealed class RollbackSalesImportOutcome {
    data class Success(val job: SalesImportJobResponse) : RollbackSalesImportOutcome()
    data object NotFound : RollbackSalesImportOutcome()
    data object Forbidden : RollbackSalesImportOutcome()
    data class Rejected(val message: String) : RollbackSalesImportOutcome()
    data class NetworkError(val message: String) : RollbackSalesImportOutcome()
}

interface SalesImportApi {
    suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadSalesCsvOutcome
    suspend fun dryRun(fileId: String, mapping: Map<String, String>): SalesDryRunOutcome
    suspend fun startImport(fileId: String, mapping: Map<String, String>): StartSalesImportOutcome
    suspend fun getJob(jobId: String): SalesImportJobOutcome
    suspend fun getReconciliationReport(jobId: String): SalesReconciliationOutcome
    suspend fun rollback(jobId: String): RollbackSalesImportOutcome
}

class KtorSalesImportApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : SalesImportApi {

    override suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadSalesCsvOutcome = try {
        val response = httpClient.post("$baseUrl/sales-import/upload") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", bytes, Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            append(HttpHeaders.ContentType, "text/csv")
                        })
                    }
                )
            )
        }
        when (response.status) {
            HttpStatusCode.Created -> UploadSalesCsvOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> UploadSalesCsvOutcome.Forbidden
            HttpStatusCode.BadRequest -> UploadSalesCsvOutcome.Rejected(response.errorMessage())
            else -> UploadSalesCsvOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UploadSalesCsvOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun dryRun(fileId: String, mapping: Map<String, String>): SalesDryRunOutcome = try {
        val response = httpClient.post("$baseUrl/sales-import/$fileId/dry-run") {
            contentType(ContentType.Application.Json)
            setBody(SalesImportMappingRequest(mapping))
        }
        when (response.status) {
            HttpStatusCode.OK -> SalesDryRunOutcome.Success(response.body())
            HttpStatusCode.NotFound -> SalesDryRunOutcome.FileNotFound
            HttpStatusCode.BadRequest -> SalesDryRunOutcome.Rejected(response.errorMessage())
            else -> SalesDryRunOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SalesDryRunOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun startImport(fileId: String, mapping: Map<String, String>): StartSalesImportOutcome = try {
        val response = httpClient.post("$baseUrl/sales-import/$fileId/start") {
            contentType(ContentType.Application.Json)
            setBody(SalesImportMappingRequest(mapping))
        }
        when (response.status) {
            HttpStatusCode.Created -> StartSalesImportOutcome.Success(response.body())
            HttpStatusCode.NotFound -> StartSalesImportOutcome.FileNotFound
            HttpStatusCode.BadRequest -> StartSalesImportOutcome.Rejected(response.errorMessage())
            else -> StartSalesImportOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StartSalesImportOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getJob(jobId: String): SalesImportJobOutcome = try {
        val response = httpClient.get("$baseUrl/sales-import/jobs/$jobId")
        when (response.status) {
            HttpStatusCode.OK -> SalesImportJobOutcome.Success(response.body())
            HttpStatusCode.NotFound -> SalesImportJobOutcome.NotFound
            else -> SalesImportJobOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SalesImportJobOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getReconciliationReport(jobId: String): SalesReconciliationOutcome = try {
        val response = httpClient.get("$baseUrl/sales-import/jobs/$jobId/reconciliation")
        when (response.status) {
            HttpStatusCode.OK -> SalesReconciliationOutcome.Success(response.body())
            HttpStatusCode.NotFound -> SalesReconciliationOutcome.NotFound
            HttpStatusCode.Conflict -> SalesReconciliationOutcome.Rejected(response.errorMessage())
            else -> SalesReconciliationOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SalesReconciliationOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun rollback(jobId: String): RollbackSalesImportOutcome = try {
        val response = httpClient.post("$baseUrl/sales-import/jobs/$jobId/rollback")
        when (response.status) {
            HttpStatusCode.OK -> RollbackSalesImportOutcome.Success(response.body())
            HttpStatusCode.NotFound -> RollbackSalesImportOutcome.NotFound
            HttpStatusCode.Forbidden -> RollbackSalesImportOutcome.Forbidden
            HttpStatusCode.Conflict -> RollbackSalesImportOutcome.Rejected(response.errorMessage())
            else -> RollbackSalesImportOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RollbackSalesImportOutcome.NetworkError(e.message ?: "Network error")
    }

    private suspend fun io.ktor.client.statement.HttpResponse.errorMessage(): String =
        runCatching { body<ErrorResponse>() }.getOrNull()?.error ?: "Request failed"
}
