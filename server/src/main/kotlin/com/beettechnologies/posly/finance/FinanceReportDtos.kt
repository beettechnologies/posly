package com.beettechnologies.posly.finance

import kotlinx.serialization.Serializable

@Serializable
data class CreateScheduleRequest(
    val storeId: String,
    val type: String,
    val format: String,
    val timezone: String,
    val frequency: String,
    val recipients: List<String>
)

@Serializable
data class ScheduledReportResponse(
    val id: String,
    val storeId: String,
    val type: String,
    val format: String,
    val timezone: String,
    val frequency: String,
    val recipients: List<String>,
    val createdBy: String?,
    val createdAt: String,
    val nextRunAt: String,
    val lastRunAt: String?,
    val lastRunStatus: String?
)

fun ScheduledReport.toResponse() = ScheduledReportResponse(
    id = id,
    storeId = storeId,
    type = type.name,
    format = format.name,
    timezone = timezone,
    frequency = frequency.name,
    recipients = recipients,
    createdBy = createdBy,
    createdAt = createdAt.toString(),
    nextRunAt = nextRunAt.toString(),
    lastRunAt = lastRunAt?.toString(),
    lastRunStatus = lastRunStatus
)

@Serializable
data class ScheduledReportRunResponse(
    val id: String,
    val scheduleId: String,
    val periodStart: String,
    val periodEnd: String,
    val runAt: String,
    val status: String,
    val deliveredTo: List<String>,
    val failedRecipients: List<String>
)

fun ScheduledReportRun.toResponse() = ScheduledReportRunResponse(
    id = id,
    scheduleId = scheduleId,
    periodStart = periodStart.toString(),
    periodEnd = periodEnd.toString(),
    runAt = runAt.toString(),
    status = status.name,
    deliveredTo = deliveredTo,
    failedRecipients = failedRecipients
)
