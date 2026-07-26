package com.beettechnologies.posly.finance

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.tokenClaims
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

private fun parseType(raw: String): FinanceReportType? = runCatching { FinanceReportType.valueOf(raw) }.getOrNull()

private fun parseFormat(raw: String): FinanceReportFormat? = runCatching { FinanceReportFormat.valueOf(raw) }.getOrNull()

private fun parseFrequency(raw: String): ScheduleFrequency? = runCatching { ScheduleFrequency.valueOf(raw) }.getOrNull()

fun Application.configureFinanceReportRoutes(financeReportService: FinanceReportService) {
    routing {
        authenticate("jwt-auth") {
            route("/finance/reports") {
                withRole(Role.ADMIN, Role.MANAGER) {
                    get("/generate") {
                        val storeId = call.request.queryParameters["storeId"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId query parameter is required"))
                            return@get
                        }
                        val type = call.request.queryParameters["type"]?.let {
                            parseType(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("type must be one of: ${FinanceReportType.entries.joinToString()}"))
                                return@get
                            }
                        } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("type query parameter is required"))
                            return@get
                        }
                        val format = call.request.queryParameters["format"]?.let {
                            parseFormat(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("format must be one of: ${FinanceReportFormat.entries.joinToString()}"))
                                return@get
                            }
                        } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("format query parameter is required"))
                            return@get
                        }
                        val timezone = call.request.queryParameters["timezone"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("timezone query parameter is required"))
                            return@get
                        }
                        val from = call.request.queryParameters["from"]?.let {
                            parseInstant(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("from must be an ISO-8601 instant"))
                                return@get
                            }
                        } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("from query parameter is required"))
                            return@get
                        }
                        val to = call.request.queryParameters["to"]?.let {
                            parseInstant(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("to must be an ISO-8601 instant"))
                                return@get
                            }
                        } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("to query parameter is required"))
                            return@get
                        }

                        when (val result = financeReportService.generateReport(type, format, storeId, from, to, timezone)) {
                            is GenerateReportResult.Success -> {
                                call.response.header(
                                    HttpHeaders.ContentDisposition,
                                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, result.report.fileName).toString()
                                )
                                call.respondBytes(result.report.bytes, ContentType.parse(result.report.contentType))
                            }
                            GenerateReportResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            is GenerateReportResult.InvalidTimezone -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("'${result.timezone}' is not a valid timezone"))
                            GenerateReportResult.InvalidRange -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("from must be before to"))
                        }
                    }

                    get("/schedules") {
                        val storeId = call.request.queryParameters["storeId"]
                        call.respond(HttpStatusCode.OK, financeReportService.listSchedules(storeId).map { it.toResponse() })
                    }

                    get("/schedules/{id}/runs") {
                        val id = call.parameters["id"]!!
                        if (financeReportService.getSchedule(id) == null) {
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                            return@get
                        }
                        call.respond(HttpStatusCode.OK, financeReportService.listRuns(id).map { it.toResponse() })
                    }
                }

                withRole(Role.ADMIN) {
                    post("/schedules") {
                        val req = runCatching { call.receive<CreateScheduleRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val type = parseType(req.type) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("type must be one of: ${FinanceReportType.entries.joinToString()}"))
                            return@post
                        }
                        val format = parseFormat(req.format) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("format must be one of: ${FinanceReportFormat.entries.joinToString()}"))
                            return@post
                        }
                        val frequency = parseFrequency(req.frequency) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("frequency must be one of: ${ScheduleFrequency.entries.joinToString()}"))
                            return@post
                        }
                        val createdBy = call.tokenClaims()?.userId
                        when (val result = financeReportService.createSchedule(req.storeId, type, format, req.timezone, frequency, req.recipients, createdBy)) {
                            is CreateScheduleResult.Success -> call.respond(HttpStatusCode.Created, result.schedule.toResponse())
                            CreateScheduleResult.StoreNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Store not found"))
                            is CreateScheduleResult.InvalidTimezone -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("'${result.timezone}' is not a valid timezone"))
                            CreateScheduleResult.EmptyRecipients -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one recipient is required"))
                            is CreateScheduleResult.InvalidRecipient -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("'${result.recipient}' is not a valid email address"))
                        }
                    }

                    delete("/schedules/{id}") {
                        val id = call.parameters["id"]!!
                        if (financeReportService.deleteSchedule(id)) call.respond(HttpStatusCode.NoContent)
                        else call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                    }

                    post("/schedules/{id}/run-now") {
                        val id = call.parameters["id"]!!
                        when (val result = financeReportService.runScheduleNow(id)) {
                            is RunScheduleResult.Success -> call.respond(HttpStatusCode.OK, result.run.toResponse())
                            RunScheduleResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Schedule not found"))
                        }
                    }
                }
            }
        }
    }
}
