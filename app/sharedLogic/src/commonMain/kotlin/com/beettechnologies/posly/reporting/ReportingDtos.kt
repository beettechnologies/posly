package com.beettechnologies.posly.reporting

import kotlinx.serialization.Serializable

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

@Serializable
data class ProductSalesSummaryResponse(val productId: String, val productName: String, val quantitySold: Int, val revenue: Double)

@Serializable
data class CashOnHandResponse(val storeId: String, val openShiftCount: Int, val totalExpectedCash: Double, val asOf: String)
