package com.beettechnologies.posly.reporting

import kotlinx.serialization.Serializable

@Serializable
data class RunPipelineRequest(val period: String, val asOf: String? = null, val storeIds: List<String>? = null)

@Serializable
data class BackfillRequest(val period: String, val from: String, val to: String, val storeIds: List<String>? = null)

@Serializable
data class PipelineRunResponse(
    val id: String,
    val period: String,
    val periodStart: String,
    val periodEnd: String,
    val storeIds: List<String>,
    val status: String,
    val startedAt: String,
    val completedAt: String?,
    val error: String? = null
)

fun PipelineRunRecord.toResponse() = PipelineRunResponse(
    id = id, period = period.name, periodStart = periodStart.toString(), periodEnd = periodEnd.toString(),
    storeIds = storeIds, status = status.name, startedAt = startedAt.toString(), completedAt = completedAt?.toString(), error = error
)

@Serializable
data class SalesAggregateResponse(
    val storeId: String,
    val period: String,
    val periodStart: String,
    val periodEnd: String,
    val orderCount: Int,
    val itemsSold: Int,
    val grossSales: Double,
    val discountTotal: Double,
    val taxCollected: Double,
    val refundsTotal: Double,
    val netSales: Double,
    val generatedAt: String
)

fun SalesAggregate.toResponse() = SalesAggregateResponse(
    storeId = storeId, period = period.name, periodStart = periodStart.toString(), periodEnd = periodEnd.toString(),
    orderCount = orderCount, itemsSold = itemsSold, grossSales = grossSales, discountTotal = discountTotal,
    taxCollected = taxCollected, refundsTotal = refundsTotal, netSales = netSales, generatedAt = generatedAt.toString()
)

@Serializable
data class InventoryAggregateResponse(
    val storeId: String,
    val period: String,
    val periodStart: String,
    val periodEnd: String,
    val stockCountsPerformed: Int,
    val totalVarianceUnits: Int,
    val overageCount: Int,
    val shortageCount: Int,
    val generatedAt: String
)

fun InventoryVarianceAggregate.toResponse() = InventoryAggregateResponse(
    storeId = storeId, period = period.name, periodStart = periodStart.toString(), periodEnd = periodEnd.toString(),
    stockCountsPerformed = stockCountsPerformed, totalVarianceUnits = totalVarianceUnits,
    overageCount = overageCount, shortageCount = shortageCount, generatedAt = generatedAt.toString()
)

@Serializable
data class StaffAggregateResponse(
    val storeId: String,
    val period: String,
    val periodStart: String,
    val periodEnd: String,
    val shiftsWorked: Int,
    val distinctCashiers: Int,
    val totalCashVariance: Double,
    val shiftsWithNote: Int,
    val generatedAt: String
)

fun StaffAggregate.toResponse() = StaffAggregateResponse(
    storeId = storeId, period = period.name, periodStart = periodStart.toString(), periodEnd = periodEnd.toString(),
    shiftsWorked = shiftsWorked, distinctCashiers = distinctCashiers, totalCashVariance = totalCashVariance,
    shiftsWithNote = shiftsWithNote, generatedAt = generatedAt.toString()
)

@Serializable
data class ProductSalesSummaryResponse(val productId: String, val productName: String, val quantitySold: Int, val revenue: Double)

fun ProductSalesSummary.toResponse() = ProductSalesSummaryResponse(productId, productName, quantitySold, revenue)

@Serializable
data class CashOnHandResponse(val storeId: String, val openShiftCount: Int, val totalExpectedCash: Double, val asOf: String)

fun CashOnHandSummary.toResponse() = CashOnHandResponse(storeId, openShiftCount, totalExpectedCash, asOf.toString())
