package com.beettechnologies.posly.finance

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.email.EmailGateway
import com.beettechnologies.posly.gateway.GatewayException
import com.beettechnologies.posly.gateway.RetryPolicy
import com.beettechnologies.posly.shifts.ShiftService
import com.beettechnologies.posly.stores.StoreService
import com.beettechnologies.posly.stores.StoreTimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

sealed class GenerateReportResult {
    data class Success(val report: GeneratedReport) : GenerateReportResult()
    data object StoreNotFound : GenerateReportResult()
    data class InvalidTimezone(val timezone: String) : GenerateReportResult()
    data object InvalidRange : GenerateReportResult()
}

sealed class CreateScheduleResult {
    data class Success(val schedule: ScheduledReport) : CreateScheduleResult()
    data object StoreNotFound : CreateScheduleResult()
    data class InvalidTimezone(val timezone: String) : CreateScheduleResult()
    data object EmptyRecipients : CreateScheduleResult()
    data class InvalidRecipient(val recipient: String) : CreateScheduleResult()
}

sealed class RunScheduleResult {
    data class Success(val run: ScheduledReportRun) : RunScheduleResult()
    data object NotFound : RunScheduleResult()
}

/**
 * Generates finance-grade tax/sales/reconciliation reports on demand, and maintains recurring
 * schedules that email a report to a recipient list on a DAILY/WEEKLY/MONTHLY cadence. Every
 * schedule tracks its own [ScheduledReport.nextRunAt], since (unlike
 * [com.beettechnologies.posly.reporting.ReportingService]'s single fixed nightly loop) a store can
 * have many schedules ticking independently. [scheduleScope] follows the same nullable-CoroutineScope
 * idiom used by [com.beettechnologies.posly.webhooks.WebhookService] and
 * [com.beettechnologies.posly.catalog.ProductImportService]: a real scope in production, null in
 * tests (which drive [runDueSchedulesNow]/[runScheduleNow] synchronously instead).
 */
class FinanceReportService(
    private val orderService: OrderService,
    private val shiftService: ShiftService,
    private val storeService: StoreService,
    private val emailGateway: EmailGateway,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    scheduleScope: CoroutineScope? = null,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val scheduleCheckIntervalMillis: Long = 60_000L
) {
    private val schedules = ConcurrentHashMap<String, ScheduledReport>()
    private val runs = ConcurrentHashMap<String, CopyOnWriteArrayList<ScheduledReportRun>>()

    init {
        scheduleScope?.launch {
            while (isActive) {
                delay(scheduleCheckIntervalMillis)
                runDueSchedulesNow()
            }
        }
    }

    fun generateReport(
        type: FinanceReportType,
        format: FinanceReportFormat,
        storeId: String,
        from: Instant,
        to: Instant,
        timezone: String
    ): GenerateReportResult {
        if (storeService.getStore(storeId) == null) return GenerateReportResult.StoreNotFound
        if (!StoreTimeZone.isValid(timezone)) return GenerateReportResult.InvalidTimezone(timezone)
        if (!from.isBefore(to)) return GenerateReportResult.InvalidRange

        val report = buildReport(type, format, storeId, from, to, timezone)
        return GenerateReportResult.Success(report)
    }

    fun createSchedule(
        storeId: String,
        type: FinanceReportType,
        format: FinanceReportFormat,
        timezone: String,
        frequency: ScheduleFrequency,
        recipients: List<String>,
        createdBy: String?
    ): CreateScheduleResult {
        if (storeService.getStore(storeId) == null) return CreateScheduleResult.StoreNotFound
        if (!StoreTimeZone.isValid(timezone)) return CreateScheduleResult.InvalidTimezone(timezone)
        if (recipients.isEmpty()) return CreateScheduleResult.EmptyRecipients
        recipients.forEach { if (!EMAIL_PATTERN.matches(it)) return CreateScheduleResult.InvalidRecipient(it) }

        val now = nowProvider()
        val schedule = ScheduledReport(
            storeId = storeId,
            type = type,
            format = format,
            timezone = timezone,
            frequency = frequency,
            recipients = recipients,
            createdBy = createdBy,
            createdAt = now,
            nextRunAt = nextBoundaryAfter(now, frequency, timezone)
        )
        schedules[schedule.id] = schedule
        AuditService.record(
            AuditEvent.FINANCE_REPORT_SCHEDULED,
            userId = createdBy,
            detail = "scheduleId=${schedule.id}, storeId=$storeId, type=$type, frequency=$frequency, recipients=${recipients.size}"
        )
        return CreateScheduleResult.Success(schedule)
    }

    fun listSchedules(storeId: String? = null): List<ScheduledReport> =
        schedules.values.filter { storeId == null || it.storeId == storeId }.sortedBy { it.createdAt }

    fun getSchedule(id: String): ScheduledReport? = schedules[id]

    fun deleteSchedule(id: String): Boolean = schedules.remove(id) != null

    fun listRuns(scheduleId: String): List<ScheduledReportRun> = runs[scheduleId]?.toList() ?: emptyList()

    /** Ad-hoc "send now" using the schedule's own config, for the period that just completed - does not affect its recurring cadence. */
    suspend fun runScheduleNow(id: String): RunScheduleResult {
        val schedule = schedules[id] ?: return RunScheduleResult.NotFound
        val now = nowProvider()
        val periodEnd = now
        val periodStart = periodStartFor(nextBoundaryAfter(now, schedule.frequency, schedule.timezone), schedule.frequency, schedule.timezone)
        val run = deliver(schedule, periodStart, periodEnd)
        return RunScheduleResult.Success(run)
    }

    /** Called by the scheduling loop (or directly in tests) - generates and delivers every schedule whose period has elapsed. */
    suspend fun runDueSchedulesNow() {
        val now = nowProvider()
        schedules.values.filter { !it.nextRunAt.isAfter(now) }.forEach { schedule ->
            val periodEnd = schedule.nextRunAt
            val periodStart = periodStartFor(periodEnd, schedule.frequency, schedule.timezone)
            val run = deliver(schedule, periodStart, periodEnd)
            schedules[schedule.id] = schedule.copy(
                nextRunAt = nextBoundaryAfter(periodEnd, schedule.frequency, schedule.timezone),
                lastRunAt = now,
                lastRunStatus = run.status.name
            )
        }
    }

    private suspend fun deliver(schedule: ScheduledReport, periodStart: Instant, periodEnd: Instant): ScheduledReportRun {
        val report = buildReport(schedule.type, schedule.format, schedule.storeId, periodStart, periodEnd, schedule.timezone)
        val delivered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        schedule.recipients.forEach { recipient ->
            try {
                retryPolicy.withBackoff {
                    emailGateway.sendReceipt(recipient, subject = "${schedule.type.name.lowercase().replaceFirstChar { it.uppercase() }} report", pdfBytes = report.bytes)
                }
                delivered += recipient
            } catch (e: GatewayException) {
                failed += recipient
            }
        }
        val status = when {
            failed.isEmpty() -> ScheduledReportRunStatus.SUCCESS
            delivered.isEmpty() -> ScheduledReportRunStatus.FAILED
            else -> ScheduledReportRunStatus.PARTIAL_FAILURE
        }
        AuditService.record(
            if (failed.isEmpty()) AuditEvent.FINANCE_REPORT_DELIVERED else AuditEvent.FINANCE_REPORT_DELIVERY_FAILED,
            detail = "scheduleId=${schedule.id}, delivered=${delivered.size}, failed=${failed.size}"
        )
        val run = ScheduledReportRun(
            scheduleId = schedule.id,
            periodStart = periodStart,
            periodEnd = periodEnd,
            runAt = nowProvider(),
            status = status,
            deliveredTo = delivered,
            failedRecipients = failed
        )
        runs.getOrPut(schedule.id) { CopyOnWriteArrayList() }.add(run)
        return run
    }

    private fun buildReport(
        type: FinanceReportType,
        format: FinanceReportFormat,
        storeId: String,
        from: Instant,
        to: Instant,
        timezone: String
    ): GeneratedReport {
        val now = nowProvider()
        val table = when (type) {
            FinanceReportType.TAX -> FinanceReportBuilder.buildTaxTable(orderService.listOrders(storeId, from, to))
            FinanceReportType.SALES -> FinanceReportBuilder.buildSalesTable(orderService.listOrders(storeId, from, to), timezone)
            FinanceReportType.RECONCILIATION -> FinanceReportBuilder.buildReconciliationTable(
                shiftService.listShifts(storeId).filter { it.closedAt != null && !it.closedAt.isBefore(from) && it.closedAt.isBefore(to) },
                timezone
            )
        }
        val bytes = when (format) {
            FinanceReportFormat.CSV -> FinanceReportBuilder.renderCsv(table)
            FinanceReportFormat.PDF -> FinanceReportBuilder.renderPdf(table, now, timezone)
        }
        val extension = format.name.lowercase()
        val fileName = "${type.name.lowercase()}-report_${StoreTimeZone.toLocalDate(from, timezone)}_${StoreTimeZone.toLocalDate(to.minusMillis(1), timezone)}.$extension"
        val contentType = when (format) {
            FinanceReportFormat.CSV -> "text/csv"
            FinanceReportFormat.PDF -> "application/pdf"
        }
        AuditService.record(AuditEvent.FINANCE_REPORT_GENERATED, detail = "storeId=$storeId, type=$type, format=$format")
        return GeneratedReport(
            type = type, format = format, storeId = storeId, timezone = timezone,
            periodStart = from, periodEnd = to, generatedAt = now,
            fileName = fileName, contentType = contentType, bytes = bytes
        )
    }

    private fun nextBoundaryAfter(instant: Instant, frequency: ScheduleFrequency, timezone: String): Instant {
        val date = StoreTimeZone.toLocalDate(instant, timezone)
        return StoreTimeZone.startOfLocalDay(advance(date, frequency), timezone)
    }

    private fun periodStartFor(periodEnd: Instant, frequency: ScheduleFrequency, timezone: String): Instant {
        val date = StoreTimeZone.toLocalDate(periodEnd, timezone)
        return StoreTimeZone.startOfLocalDay(retreat(date, frequency), timezone)
    }

    private fun advance(date: LocalDate, frequency: ScheduleFrequency): LocalDate = when (frequency) {
        ScheduleFrequency.DAILY -> date.plusDays(1)
        ScheduleFrequency.WEEKLY -> date.plusDays(7)
        ScheduleFrequency.MONTHLY -> date.plusMonths(1)
    }

    private fun retreat(date: LocalDate, frequency: ScheduleFrequency): LocalDate = when (frequency) {
        ScheduleFrequency.DAILY -> date.minusDays(1)
        ScheduleFrequency.WEEKLY -> date.minusDays(7)
        ScheduleFrequency.MONTHLY -> date.minusMonths(1)
    }
}
