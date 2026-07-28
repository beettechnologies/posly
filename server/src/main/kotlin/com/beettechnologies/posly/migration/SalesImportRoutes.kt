package com.beettechnologies.posly.migration

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun parseMapping(raw: Map<String, String>): Result<Map<SalesImportField, String>> {
    val mapping = mutableMapOf<SalesImportField, String>()
    for ((fieldName, header) in raw) {
        val field = runCatching { SalesImportField.valueOf(fieldName) }.getOrElse {
            return Result.failure(IllegalArgumentException("Unknown import field '$fieldName' - must be one of: ${SalesImportField.entries.joinToString()}"))
        }
        mapping[field] = header
    }
    return Result.success(mapping)
}

fun Application.configureSalesImportRoutes(importService: SalesImportService) {
    routing {
        authenticate("jwt-auth") {
            route("/sales-import") {
                withRole(Role.ADMIN, Role.MANAGER) {
                    post("/upload") {
                        val multipart = call.receiveMultipart()
                        var fileName: String? = null
                        var bytes: ByteArray? = null
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem && bytes == null) {
                                fileName = part.originalFileName ?: "sales-import.csv"
                                bytes = part.streamProvider().readBytes()
                            }
                            part.dispose()
                        }
                        val fileBytes = bytes
                        if (fileBytes == null || fileBytes.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("No CSV file provided in multipart request"))
                            return@post
                        }
                        when (val result = importService.uploadCsv(fileName ?: "sales-import.csv", fileBytes)) {
                            is UploadSalesCsvResult.Success -> call.respond(
                                HttpStatusCode.Created,
                                UploadSalesCsvResponse(
                                    fileId = result.file.id,
                                    headers = result.file.headers,
                                    previewRows = result.file.rows.take(5),
                                    totalRows = result.file.rows.size
                                )
                            )
                            is UploadSalesCsvResult.InvalidCsv -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    }

                    post("/{fileId}/dry-run") {
                        val fileId = call.parameters["fileId"]!!
                        val req = runCatching { call.receive<SalesImportMappingRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val mapping = parseMapping(req.mapping).getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid mapping"))
                            return@post
                        }
                        when (val result = importService.dryRun(fileId, mapping)) {
                            is SalesDryRunResult.Success -> call.respond(HttpStatusCode.OK, result.report.toResponse())
                            SalesDryRunResult.FileNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import file not found"))
                            is SalesDryRunResult.InvalidMapping -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.errors.joinToString("; ")))
                        }
                    }

                    post("/{fileId}/start") {
                        val fileId = call.parameters["fileId"]!!
                        val req = runCatching { call.receive<SalesImportMappingRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val mapping = parseMapping(req.mapping).getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid mapping"))
                            return@post
                        }
                        val startedBy = call.tokenClaims()?.userId
                        when (val result = importService.startImport(fileId, mapping, startedBy)) {
                            is StartSalesImportResult.Success -> call.respond(HttpStatusCode.Created, result.job.toResponse())
                            StartSalesImportResult.FileNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import file not found"))
                            is StartSalesImportResult.InvalidMapping -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.errors.joinToString("; ")))
                        }
                    }

                    get("/jobs/{jobId}") {
                        val job = importService.getJob(call.parameters["jobId"]!!)
                        if (job == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Import job not found"))
                        else call.respond(HttpStatusCode.OK, job.toResponse())
                    }

                    get("/jobs/{jobId}/reconciliation") {
                        when (val result = importService.getReconciliationReport(call.parameters["jobId"]!!)) {
                            is SalesReconciliationResult.Success -> call.respond(HttpStatusCode.OK, result.report.toResponse())
                            SalesReconciliationResult.JobNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import job not found"))
                            SalesReconciliationResult.JobNotCompleted -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Import job has not completed yet"))
                        }
                    }

                    post("/jobs/{jobId}/rollback") {
                        val jobId = call.parameters["jobId"]!!
                        val actorUserId = call.tokenClaims()?.userId
                        when (val result = importService.rollback(jobId, actorUserId)) {
                            is SalesRollbackResult.Success -> call.respond(HttpStatusCode.OK, result.job.toResponse())
                            SalesRollbackResult.JobNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import job not found"))
                            SalesRollbackResult.JobNotCompleted -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Import job has not completed yet"))
                            SalesRollbackResult.AlreadyRolledBack -> call.respond(HttpStatusCode.Conflict, ErrorResponse("This import has already been rolled back"))
                            SalesRollbackResult.NotMostRecentImport -> call.respond(
                                HttpStatusCode.Conflict,
                                ErrorResponse("Only the most recently completed import can be rolled back")
                            )
                        }
                    }
                }
            }
        }
    }
}
