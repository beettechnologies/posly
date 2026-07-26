package com.beettechnologies.posly.reporting

import java.time.Instant
import java.util.UUID

enum class ReportPeriod { DAILY, WEEKLY, MONTHLY }

data class SalesAggregate(
    val storeId: String,
    val period: ReportPeriod,
    val periodStart: Instant,
    val periodEnd: Instant,
    val orderCount: Int,
    val itemsSold: Int,
    val grossSales: Double,
    val discountTotal: Double,
    val taxCollected: Double,
    val refundsTotal: Double,
    val netSales: Double,
    val generatedAt: Instant
)

data class InventoryVarianceAggregate(
    val storeId: String,
    val period: ReportPeriod,
    val periodStart: Instant,
    val periodEnd: Instant,
    val stockCountsPerformed: Int,
    val totalVarianceUnits: Int,
    val overageCount: Int,
    val shortageCount: Int,
    val generatedAt: Instant
)

data class StaffAggregate(
    val storeId: String,
    val period: ReportPeriod,
    val periodStart: Instant,
    val periodEnd: Instant,
    val shiftsWorked: Int,
    val distinctCashiers: Int,
    val totalCashVariance: Double,
    val shiftsWithNote: Int,
    val generatedAt: Instant
)

enum class PipelineRunStatus { RUNNING, SUCCESS, FAILED }

/** One execution of the batch pipeline - the durable record backfill/monitoring/alerting consult. */
data class PipelineRunRecord(
    val id: String = UUID.randomUUID().toString(),
    val period: ReportPeriod,
    val periodStart: Instant,
    val periodEnd: Instant,
    val storeIds: List<String>,
    val status: PipelineRunStatus,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val error: String? = null
)
