package com.beettechnologies.posly.reporting

import com.beettechnologies.posly.audit.AuditEvent
import com.beettechnologies.posly.audit.AuditService
import com.beettechnologies.posly.cart.Order
import com.beettechnologies.posly.cart.OrderEventListener
import com.beettechnologies.posly.cart.OrderEventType
import com.beettechnologies.posly.cart.OrderService
import com.beettechnologies.posly.cart.OrderStatus
import com.beettechnologies.posly.inventory.StockCountService
import com.beettechnologies.posly.inventory.VarianceCause
import com.beettechnologies.posly.shifts.ShiftService
import com.beettechnologies.posly.shifts.ShiftStatus
import com.beettechnologies.posly.stores.StoreService
import io.micrometer.core.instrument.MeterRegistry
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Batch + realtime reporting over sales, inventory variance, and staff/shift data - all computed
 * on demand from the existing services (there is no separate data warehouse in this project; each
 * pipeline run is a synchronous, cheap in-memory aggregation, stored so it can be queried again
 * without recomputation). [pipelineScope] mirrors [com.beettechnologies.posly.webhooks.WebhookService]'s
 * `deliveryScope`: when set (the live application), a nightly DAILY run self-schedules in the
 * background; when null (tests), nothing runs until [runPipeline] is called directly.
 *
 * Implements [OrderEventListener] purely to invalidate the realtime cache the moment a store has
 * new activity, so the cached value refreshes eagerly rather than only after its TTL lapses - the
 * "incremental" half of the ticket's "batch + incremental" ask, without needing a real message
 * queue: the realtime aggregate is still always recomputed from the current order state, just
 * triggered early.
 */
class ReportingService(
    private val orderService: OrderService,
    private val storeService: StoreService,
    private val stockCountService: StockCountService,
    private val shiftService: ShiftService,
    private val pipelineScope: CoroutineScope? = null,
    private val meterRegistry: MeterRegistry? = null,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val realtimeCacheTtlMillis: Long = 5_000L,
    private val nightlyIntervalMillis: Long = 24 * 60 * 60 * 1000L
) : OrderEventListener {

    private val salesAggregates = ConcurrentHashMap<String, SalesAggregate>()
    private val inventoryAggregates = ConcurrentHashMap<String, InventoryVarianceAggregate>()
    private val staffAggregates = ConcurrentHashMap<String, StaffAggregate>()
    private val pipelineRuns = ConcurrentHashMap<String, PipelineRunRecord>()
    private val realtimeCache = ConcurrentHashMap<String, RealtimeCacheEntry>()

    private data class RealtimeCacheEntry(val aggregate: SalesAggregate, val computedAt: Instant)

    init {
        pipelineScope?.launch {
            while (isActive) {
                delay(nightlyIntervalMillis)
                runPipeline(ReportPeriod.DAILY)
            }
        }
    }

    override fun onEvent(order: Order, type: OrderEventType) {
        realtimeCache.remove(order.storeId)
    }

    /** Runs the batch pipeline for the period containing [asOf] (defaults to now) across [storeIds] (defaults to every store). */
    fun runPipeline(period: ReportPeriod, asOf: Instant = nowProvider(), storeIds: List<String>? = null): PipelineRunRecord {
        val stores = storeIds ?: storeService.listStores().map { it.id }
        val (start, end) = periodBounds(period, asOf)
        val run = PipelineRunRecord(
            period = period, periodStart = start, periodEnd = end, storeIds = stores,
            status = PipelineRunStatus.RUNNING, startedAt = nowProvider()
        )
        pipelineRuns[run.id] = run
        AuditService.record(AuditEvent.REPORT_PIPELINE_STARTED, detail = "runId=${run.id} period=$period start=$start end=$end stores=${stores.size}")

        return try {
            for (storeId in stores) {
                salesAggregates[aggregateKey(storeId, period, start)] = computeSales(storeId, period, start, end)
                inventoryAggregates[aggregateKey(storeId, period, start)] = computeInventory(storeId, period, start, end)
                staffAggregates[aggregateKey(storeId, period, start)] = computeStaff(storeId, period, start, end)
            }
            val completed = run.copy(status = PipelineRunStatus.SUCCESS, completedAt = nowProvider())
            pipelineRuns[run.id] = completed
            AuditService.record(AuditEvent.REPORT_PIPELINE_COMPLETED, detail = "runId=${run.id} stores=${stores.size}")
            meterRegistry?.counter("reports_pipeline_runs", "status", "SUCCESS")?.increment()
            completed
        } catch (e: Exception) {
            val failed = run.copy(status = PipelineRunStatus.FAILED, completedAt = nowProvider(), error = e.message)
            pipelineRuns[run.id] = failed
            AuditService.record(AuditEvent.REPORT_PIPELINE_FAILED, detail = "runId=${run.id} error=${e.message}")
            meterRegistry?.counter("reports_pipeline_runs", "status", "FAILED")?.increment()
            failed
        }
    }

    /** Runs [period] once per period boundary from [from] up to (but not including) [to] - for populating history retroactively. */
    fun backfill(period: ReportPeriod, from: Instant, to: Instant, storeIds: List<String>? = null): List<PipelineRunRecord> {
        val runs = mutableListOf<PipelineRunRecord>()
        var cursor = periodBounds(period, from).first
        while (cursor.isBefore(to)) {
            runs += runPipeline(period, cursor, storeIds)
            cursor = periodBounds(period, cursor).second
        }
        return runs
    }

    fun getPipelineRun(id: String): PipelineRunRecord? = pipelineRuns[id]

    fun listPipelineRuns(): List<PipelineRunRecord> = pipelineRuns.values.sortedByDescending { it.startedAt }

    fun getSalesAggregate(storeId: String, period: ReportPeriod, periodStart: Instant): SalesAggregate? =
        salesAggregates[aggregateKey(storeId, period, periodStart)]

    fun listSalesAggregates(storeId: String? = null, period: ReportPeriod? = null): List<SalesAggregate> =
        salesAggregates.values
            .filter { (storeId == null || it.storeId == storeId) && (period == null || it.period == period) }
            .sortedByDescending { it.periodStart }

    fun getInventoryAggregate(storeId: String, period: ReportPeriod, periodStart: Instant): InventoryVarianceAggregate? =
        inventoryAggregates[aggregateKey(storeId, period, periodStart)]

    fun listInventoryAggregates(storeId: String? = null, period: ReportPeriod? = null): List<InventoryVarianceAggregate> =
        inventoryAggregates.values
            .filter { (storeId == null || it.storeId == storeId) && (period == null || it.period == period) }
            .sortedByDescending { it.periodStart }

    fun getStaffAggregate(storeId: String, period: ReportPeriod, periodStart: Instant): StaffAggregate? =
        staffAggregates[aggregateKey(storeId, period, periodStart)]

    fun listStaffAggregates(storeId: String? = null, period: ReportPeriod? = null): List<StaffAggregate> =
        staffAggregates.values
            .filter { (storeId == null || it.storeId == storeId) && (period == null || it.period == period) }
            .sortedByDescending { it.periodStart }

    /**
     * "Today so far" sales for [storeId], cached for [realtimeCacheTtlMillis] (or until an order
     * event for this store invalidates it early) - the near-realtime aggregate the ticket's SLO
     * describes, without recomputing from scratch on every single request.
     */
    fun getRealtimeSales(storeId: String): SalesAggregate {
        val now = nowProvider()
        val cached = realtimeCache[storeId]
        if (cached != null && Duration.between(cached.computedAt, now).toMillis() < realtimeCacheTtlMillis) {
            return cached.aggregate
        }
        val start = periodBounds(ReportPeriod.DAILY, now).first
        val aggregate = computeSales(storeId, ReportPeriod.DAILY, start, now)
        realtimeCache[storeId] = RealtimeCacheEntry(aggregate, now)
        return aggregate
    }

    private fun computeSales(storeId: String, period: ReportPeriod, from: Instant, to: Instant): SalesAggregate {
        val completed = orderService.listOrders(storeId, from, to)
            .filter { it.status == OrderStatus.PAID || it.status == OrderStatus.PARTIALLY_REFUNDED || it.status == OrderStatus.REFUNDED }
        val grossSales = completed.sumOf { it.totals.total }
        val refundsTotal = completed.sumOf { it.amountRefunded }
        return SalesAggregate(
            storeId = storeId,
            period = period,
            periodStart = from,
            periodEnd = to,
            orderCount = completed.size,
            itemsSold = completed.sumOf { order -> order.items.sumOf { it.quantity } },
            grossSales = grossSales,
            discountTotal = completed.sumOf { it.totals.itemDiscountTotal + it.totals.cartDiscountAmount },
            taxCollected = completed.sumOf { it.totals.totalTax },
            refundsTotal = refundsTotal,
            netSales = grossSales - refundsTotal,
            generatedAt = nowProvider()
        )
    }

    private fun computeInventory(storeId: String, period: ReportPeriod, from: Instant, to: Instant): InventoryVarianceAggregate {
        val counts = stockCountService.listStockCounts(storeId).filter {
            val countedAt = Instant.ofEpochMilli(it.countedAt)
            !countedAt.isBefore(from) && countedAt.isBefore(to)
        }
        val allVariances = counts.flatMap { it.variances }
        return InventoryVarianceAggregate(
            storeId = storeId,
            period = period,
            periodStart = from,
            periodEnd = to,
            stockCountsPerformed = counts.size,
            totalVarianceUnits = counts.sumOf { it.totalVarianceUnits },
            overageCount = allVariances.count { it.cause == VarianceCause.OVERAGE },
            shortageCount = allVariances.count { it.cause == VarianceCause.SHORTAGE },
            generatedAt = nowProvider()
        )
    }

    private fun computeStaff(storeId: String, period: ReportPeriod, from: Instant, to: Instant): StaffAggregate {
        val shifts = shiftService.listShifts(storeId).filter { !it.openedAt.isBefore(from) && it.openedAt.isBefore(to) }
        val closed = shifts.filter { it.status == ShiftStatus.CLOSED }
        return StaffAggregate(
            storeId = storeId,
            period = period,
            periodStart = from,
            periodEnd = to,
            shiftsWorked = shifts.size,
            distinctCashiers = shifts.mapNotNull { it.cashierId }.distinct().size,
            totalCashVariance = closed.sumOf { it.variance ?: 0.0 },
            shiftsWithNote = closed.count { it.note != null },
            generatedAt = nowProvider()
        )
    }

    private fun aggregateKey(storeId: String, period: ReportPeriod, periodStart: Instant) = "$storeId|$period|$periodStart"

    /** [start, end) boundaries (UTC) for the [period] containing [instant]. WEEKLY starts Monday. */
    private fun periodBounds(period: ReportPeriod, instant: Instant): Pair<Instant, Instant> {
        val zone = ZoneOffset.UTC
        val date = instant.atZone(zone).toLocalDate()
        return when (period) {
            ReportPeriod.DAILY -> {
                val start = date.atStartOfDay(zone).toInstant()
                start to start.plus(1, ChronoUnit.DAYS)
            }
            ReportPeriod.WEEKLY -> {
                val monday = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
                val start = monday.atStartOfDay(zone).toInstant()
                start to start.plus(7, ChronoUnit.DAYS)
            }
            ReportPeriod.MONTHLY -> {
                val firstOfMonth = date.withDayOfMonth(1)
                val start = firstOfMonth.atStartOfDay(zone).toInstant()
                start to firstOfMonth.plusMonths(1).atStartOfDay(zone).toInstant()
            }
        }
    }
}
