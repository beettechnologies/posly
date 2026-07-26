package com.beettechnologies.posly.finance

import java.time.Instant
import java.util.UUID

enum class FinanceReportType { TAX, SALES, RECONCILIATION }

enum class FinanceReportFormat { CSV, PDF }

enum class ScheduleFrequency { DAILY, WEEKLY, MONTHLY }

/** A generic tabular report body - every report type renders down to this before CSV/PDF encoding. */
data class ReportTable(
    val title: String,
    val headers: List<String>,
    val rows: List<List<String>>
)

data class GeneratedReport(
    val type: FinanceReportType,
    val format: FinanceReportFormat,
    val storeId: String,
    val timezone: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val generatedAt: Instant,
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray
)

/**
 * A recurring "generate and email this report" job. Mirrors [com.beettechnologies.posly.reporting.ReportingService]'s
 * single self-scheduling nightly loop, except a store can have many independently-timed schedules,
 * so each row tracks its own [nextRunAt] rather than relying on one fixed interval.
 */
data class ScheduledReport(
    val id: String = UUID.randomUUID().toString(),
    val storeId: String,
    val type: FinanceReportType,
    val format: FinanceReportFormat,
    val timezone: String,
    val frequency: ScheduleFrequency,
    val recipients: List<String>,
    val createdBy: String?,
    val createdAt: Instant,
    val nextRunAt: Instant,
    val lastRunAt: Instant? = null,
    val lastRunStatus: String? = null
)

enum class ScheduledReportRunStatus { SUCCESS, PARTIAL_FAILURE, FAILED }

/** One delivery attempt for a [ScheduledReport] - the durable record a delivery test asserts against. */
data class ScheduledReportRun(
    val id: String = UUID.randomUUID().toString(),
    val scheduleId: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val runAt: Instant,
    val status: ScheduledReportRunStatus,
    val deliveredTo: List<String>,
    val failedRecipients: List<String>
)
