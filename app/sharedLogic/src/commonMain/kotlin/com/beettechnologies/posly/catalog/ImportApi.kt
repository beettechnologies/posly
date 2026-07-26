package com.beettechnologies.posly.catalog

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

sealed class UploadCsvOutcome {
    data class Success(val response: UploadCsvResponse) : UploadCsvOutcome()
    data class Rejected(val message: String) : UploadCsvOutcome()
    data object Forbidden : UploadCsvOutcome()
    data class NetworkError(val message: String) : UploadCsvOutcome()
}

sealed class DryRunOutcome {
    data class Success(val response: DryRunResponse) : DryRunOutcome()
    data object FileNotFound : DryRunOutcome()
    data class Rejected(val message: String) : DryRunOutcome()
    data class NetworkError(val message: String) : DryRunOutcome()
}

sealed class StartImportOutcome {
    data class Success(val job: ImportJobResponse) : StartImportOutcome()
    data object FileNotFound : StartImportOutcome()
    data class Rejected(val message: String) : StartImportOutcome()
    data class NetworkError(val message: String) : StartImportOutcome()
}

sealed class ImportJobOutcome {
    data class Success(val job: ImportJobResponse) : ImportJobOutcome()
    data object NotFound : ImportJobOutcome()
    data class NetworkError(val message: String) : ImportJobOutcome()
}

sealed class RollbackImportOutcome {
    data class Success(val job: ImportJobResponse) : RollbackImportOutcome()
    data object NotFound : RollbackImportOutcome()
    data object Forbidden : RollbackImportOutcome()
    data class Rejected(val message: String) : RollbackImportOutcome()
    data class NetworkError(val message: String) : RollbackImportOutcome()
}

interface ImportApi {
    suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadCsvOutcome
    suspend fun dryRun(fileId: String, mapping: Map<String, String>): DryRunOutcome
    suspend fun startImport(fileId: String, mapping: Map<String, String>): StartImportOutcome
    suspend fun getJob(jobId: String): ImportJobOutcome
    suspend fun rollback(jobId: String): RollbackImportOutcome
}

class KtorImportApi(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ImportApi {

    override suspend fun uploadCsv(fileName: String, bytes: ByteArray): UploadCsvOutcome = try {
        val response = httpClient.post("$baseUrl/products/import/upload") {
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
            HttpStatusCode.Created -> UploadCsvOutcome.Success(response.body())
            HttpStatusCode.Forbidden -> UploadCsvOutcome.Forbidden
            HttpStatusCode.BadRequest -> UploadCsvOutcome.Rejected(response.errorMessage())
            else -> UploadCsvOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UploadCsvOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun dryRun(fileId: String, mapping: Map<String, String>): DryRunOutcome = try {
        val response = httpClient.post("$baseUrl/products/import/$fileId/dry-run") {
            contentType(ContentType.Application.Json)
            setBody(ImportMappingRequest(mapping))
        }
        when (response.status) {
            HttpStatusCode.OK -> DryRunOutcome.Success(response.body())
            HttpStatusCode.NotFound -> DryRunOutcome.FileNotFound
            HttpStatusCode.BadRequest -> DryRunOutcome.Rejected(response.errorMessage())
            else -> DryRunOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DryRunOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun startImport(fileId: String, mapping: Map<String, String>): StartImportOutcome = try {
        val response = httpClient.post("$baseUrl/products/import/$fileId/start") {
            contentType(ContentType.Application.Json)
            setBody(ImportMappingRequest(mapping))
        }
        when (response.status) {
            HttpStatusCode.Created -> StartImportOutcome.Success(response.body())
            HttpStatusCode.NotFound -> StartImportOutcome.FileNotFound
            HttpStatusCode.BadRequest -> StartImportOutcome.Rejected(response.errorMessage())
            else -> StartImportOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StartImportOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun getJob(jobId: String): ImportJobOutcome = try {
        val response = httpClient.get("$baseUrl/products/import/jobs/$jobId")
        when (response.status) {
            HttpStatusCode.OK -> ImportJobOutcome.Success(response.body())
            HttpStatusCode.NotFound -> ImportJobOutcome.NotFound
            else -> ImportJobOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ImportJobOutcome.NetworkError(e.message ?: "Network error")
    }

    override suspend fun rollback(jobId: String): RollbackImportOutcome = try {
        val response = httpClient.post("$baseUrl/products/import/jobs/$jobId/rollback")
        when (response.status) {
            HttpStatusCode.OK -> RollbackImportOutcome.Success(response.body())
            HttpStatusCode.NotFound -> RollbackImportOutcome.NotFound
            HttpStatusCode.Forbidden -> RollbackImportOutcome.Forbidden
            HttpStatusCode.Conflict -> RollbackImportOutcome.Rejected(response.errorMessage())
            else -> RollbackImportOutcome.NetworkError("Server error (${response.status.value})")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RollbackImportOutcome.NetworkError(e.message ?: "Network error")
    }

    private suspend fun io.ktor.client.statement.HttpResponse.errorMessage(): String =
        runCatching { body<ErrorResponse>() }.getOrNull()?.error ?: "Request failed"
}
