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
