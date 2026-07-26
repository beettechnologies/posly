package com.beettechnologies.posly.catalog

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

private fun parseMapping(raw: Map<String, String>): Result<Map<ProductImportField, String>> {
    val mapping = mutableMapOf<ProductImportField, String>()
    for ((fieldName, header) in raw) {
        val field = runCatching { ProductImportField.valueOf(fieldName) }.getOrElse {
            return Result.failure(IllegalArgumentException("Unknown import field '$fieldName' - must be one of: ${ProductImportField.entries.joinToString()}"))
        }
        mapping[field] = header
    }
    return Result.success(mapping)
}

fun Application.configureProductImportRoutes(importService: ProductImportService) {
    routing {
        authenticate("jwt-auth") {
            route("/products/import") {
                withRole(Role.ADMIN, Role.MANAGER, Role.MERCHANDISER) {
                    post("/upload") {
                        val multipart = call.receiveMultipart()
                        var fileName: String? = null
                        var bytes: ByteArray? = null
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem && bytes == null) {
                                fileName = part.originalFileName ?: "import.csv"
                                bytes = part.streamProvider().readBytes()
                            }
                            part.dispose()
                        }
                        val fileBytes = bytes
                        if (fileBytes == null || fileBytes.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("No CSV file provided in multipart request"))
                            return@post
                        }
                        when (val result = importService.uploadCsv(fileName ?: "import.csv", fileBytes)) {
                            is UploadCsvResult.Success -> call.respond(
                                HttpStatusCode.Created,
                                UploadCsvResponse(
                                    fileId = result.file.id,
                                    headers = result.file.headers,
                                    previewRows = result.file.rows.take(5),
                                    totalRows = result.file.rows.size
                                )
                            )
                            is UploadCsvResult.InvalidCsv -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.message))
                        }
                    }

                    post("/{fileId}/dry-run") {
                        val fileId = call.parameters["fileId"]!!
                        val req = runCatching { call.receive<ImportMappingRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val mapping = parseMapping(req.mapping).getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid mapping"))
                            return@post
                        }
                        when (val result = importService.dryRun(fileId, mapping)) {
                            is DryRunResult.Success -> call.respond(HttpStatusCode.OK, DryRunResponse(result.outcomes.map { it.toResponse() }))
                            DryRunResult.FileNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import file not found"))
                            is DryRunResult.InvalidMapping -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.errors.joinToString("; ")))
                        }
                    }

                    post("/{fileId}/start") {
                        val fileId = call.parameters["fileId"]!!
                        val req = runCatching { call.receive<ImportMappingRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val mapping = parseMapping(req.mapping).getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid mapping"))
                            return@post
                        }
                        val startedBy = call.tokenClaims()?.userId
                        when (val result = importService.startImport(fileId, mapping, startedBy)) {
                            is StartImportResult.Success -> call.respond(HttpStatusCode.Created, result.job.toResponse())
                            StartImportResult.FileNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import file not found"))
                            is StartImportResult.InvalidMapping -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.errors.joinToString("; ")))
                        }
                    }

                    get("/jobs/{jobId}") {
                        val job = importService.getJob(call.parameters["jobId"]!!)
                        if (job == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Import job not found"))
                        else call.respond(HttpStatusCode.OK, job.toResponse())
                    }
                }

                withRole(Role.ADMIN, Role.MANAGER) {
                    post("/jobs/{jobId}/rollback") {
                        val jobId = call.parameters["jobId"]!!
                        val actorUserId = call.tokenClaims()?.userId
                        when (val result = importService.rollback(jobId, actorUserId)) {
                            is RollbackResult.Success -> call.respond(HttpStatusCode.OK, result.job.toResponse())
                            RollbackResult.JobNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Import job not found"))
                            RollbackResult.JobNotCompleted -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Import job has not completed yet"))
                            RollbackResult.AlreadyRolledBack -> call.respond(HttpStatusCode.Conflict, ErrorResponse("This import has already been rolled back"))
                            RollbackResult.NotMostRecentImport -> call.respond(
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
