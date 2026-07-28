package com.beettechnologies.posly.reporting

import com.beettechnologies.posly.auth.ErrorResponse
import com.beettechnologies.posly.capacity.HeavyAnalyticsRateLimit
import com.beettechnologies.posly.capacity.blockedByHeavyAnalyticsKillSwitch
import com.beettechnologies.posly.flags.FeatureFlagService
import com.beettechnologies.posly.model.Role
import com.beettechnologies.posly.rbac.withRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

private fun parsePeriod(raw: String): ReportPeriod? = runCatching { ReportPeriod.valueOf(raw) }.getOrNull()

private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

fun Application.configureReportingRoutes(reportingService: ReportingService, featureFlagService: FeatureFlagService) {
    routing {
        authenticate("jwt-auth") {
            route("/reports") {
                withRole(Role.ADMIN, Role.MANAGER) {
                    rateLimit(HeavyAnalyticsRateLimit) {
                    post("/pipeline/run") {
                        if (call.blockedByHeavyAnalyticsKillSwitch(featureFlagService)) return@post
                        val req = runCatching { call.receive<RunPipelineRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val period = parsePeriod(req.period) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("period must be one of: ${ReportPeriod.entries.joinToString()}"))
                            return@post
                        }
                        val asOf = req.asOf?.let {
                            parseInstant(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("asOf must be an ISO-8601 instant"))
                                return@post
                            }
                        }
                        val run = if (asOf != null) reportingService.runPipeline(period, asOf, req.storeIds) else reportingService.runPipeline(period, storeIds = req.storeIds)
                        call.respond(HttpStatusCode.Created, run.toResponse())
                    }

                    post("/pipeline/backfill") {
                        if (call.blockedByHeavyAnalyticsKillSwitch(featureFlagService)) return@post
                        val req = runCatching { call.receive<BackfillRequest>() }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
                            return@post
                        }
                        val period = parsePeriod(req.period) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("period must be one of: ${ReportPeriod.entries.joinToString()}"))
                            return@post
                        }
                        val from = parseInstant(req.from) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("from must be an ISO-8601 instant"))
                            return@post
                        }
                        val to = parseInstant(req.to) ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("to must be an ISO-8601 instant"))
                            return@post
                        }
                        if (!from.isBefore(to)) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("from must be before to"))
                            return@post
                        }
                        val runs = reportingService.backfill(period, from, to, req.storeIds)
                        call.respond(HttpStatusCode.Created, runs.map { it.toResponse() })
                    }
                    }

                    get("/pipeline/runs") {
                        call.respond(HttpStatusCode.OK, reportingService.listPipelineRuns().map { it.toResponse() })
                    }

                    get("/pipeline/runs/{id}") {
                        val run = reportingService.getPipelineRun(call.parameters["id"]!!)
                        if (run == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Pipeline run not found"))
                        else call.respond(HttpStatusCode.OK, run.toResponse())
                    }

                    get("/sales/realtime") {
                        val storeId = call.request.queryParameters["storeId"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId query parameter is required"))
                            return@get
                        }
                        call.respond(HttpStatusCode.OK, reportingService.getRealtimeSales(storeId).toResponse())
                    }

                    get("/sales") {
                        val storeId = call.request.queryParameters["storeId"]
                        val period = call.request.queryParameters["period"]?.let {
                            parsePeriod(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("period must be one of: ${ReportPeriod.entries.joinToString()}"))
                                return@get
                            }
                        }
                        val periodStartRaw = call.request.queryParameters["periodStart"]
                        if (periodStartRaw != null) {
                            if (storeId == null || period == null) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId and period are required when periodStart is given"))
                                return@get
                            }
                            val periodStart = parseInstant(periodStartRaw) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("periodStart must be an ISO-8601 instant"))
                                return@get
                            }
                            val aggregate = reportingService.getSalesAggregate(storeId, period, periodStart)
                            if (aggregate == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("No sales aggregate found for that store/period/periodStart"))
                            else call.respond(HttpStatusCode.OK, aggregate.toResponse())
                        } else {
                            call.respond(HttpStatusCode.OK, reportingService.listSalesAggregates(storeId, period).map { it.toResponse() })
                        }
                    }

                    get("/inventory") {
                        val storeId = call.request.queryParameters["storeId"]
                        val period = call.request.queryParameters["period"]?.let {
                            parsePeriod(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("period must be one of: ${ReportPeriod.entries.joinToString()}"))
                                return@get
                            }
                        }
                        val periodStartRaw = call.request.queryParameters["periodStart"]
                        if (periodStartRaw != null) {
                            if (storeId == null || period == null) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId and period are required when periodStart is given"))
                                return@get
                            }
                            val periodStart = parseInstant(periodStartRaw) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("periodStart must be an ISO-8601 instant"))
                                return@get
                            }
                            val aggregate = reportingService.getInventoryAggregate(storeId, period, periodStart)
                            if (aggregate == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("No inventory aggregate found for that store/period/periodStart"))
                            else call.respond(HttpStatusCode.OK, aggregate.toResponse())
                        } else {
                            call.respond(HttpStatusCode.OK, reportingService.listInventoryAggregates(storeId, period).map { it.toResponse() })
                        }
                    }

                    get("/top-products") {
                        val storeId = call.request.queryParameters["storeId"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId query parameter is required"))
                            return@get
                        }
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 5
                        if (limit <= 0) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("limit must be positive"))
                            return@get
                        }
                        val period = call.request.queryParameters["period"]?.let {
                            parsePeriod(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("period must be one of: ${ReportPeriod.entries.joinToString()}"))
                                return@get
                            }
                        } ?: ReportPeriod.DAILY
                        val asOf = call.request.queryParameters["asOf"]?.let {
                            parseInstant(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("asOf must be an ISO-8601 instant"))
                                return@get
                            }
                        }
                        val topProducts = if (asOf != null) {
                            reportingService.getTopProducts(storeId, limit, period, asOf)
                        } else {
                            reportingService.getTopProducts(storeId, limit, period)
                        }
                        call.respond(HttpStatusCode.OK, topProducts.map { it.toResponse() })
                    }

                    get("/cash-on-hand") {
                        val storeId = call.request.queryParameters["storeId"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId query parameter is required"))
                            return@get
                        }
                        call.respond(HttpStatusCode.OK, reportingService.getCashOnHand(storeId).toResponse())
                    }

                    get("/staff") {
                        val storeId = call.request.queryParameters["storeId"]
                        val period = call.request.queryParameters["period"]?.let {
                            parsePeriod(it) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("period must be one of: ${ReportPeriod.entries.joinToString()}"))
                                return@get
                            }
                        }
                        val periodStartRaw = call.request.queryParameters["periodStart"]
                        if (periodStartRaw != null) {
                            if (storeId == null || period == null) {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("storeId and period are required when periodStart is given"))
                                return@get
                            }
                            val periodStart = parseInstant(periodStartRaw) ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse("periodStart must be an ISO-8601 instant"))
                                return@get
                            }
                            val aggregate = reportingService.getStaffAggregate(storeId, period, periodStart)
                            if (aggregate == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("No staff aggregate found for that store/period/periodStart"))
                            else call.respond(HttpStatusCode.OK, aggregate.toResponse())
                        } else {
                            call.respond(HttpStatusCode.OK, reportingService.listStaffAggregates(storeId, period).map { it.toResponse() })
                        }
                    }
                }
            }
        }
    }
}
