package com.beettechnologies.posly.stores

import kotlinx.serialization.Serializable

@Serializable
data class TaxCalculateLineRequest(val id: String, val amount: Double, val taxCategory: String)

@Serializable
data class TaxCalculateRequest(val taxProfileId: String, val lines: List<TaxCalculateLineRequest>)

@Serializable
data class TaxCalculateResponse(
    val taxableAmount: Double,
    val exemptAmount: Double,
    val breakdown: List<TaxBreakdownItem>,
    val totalTax: Double,
    val total: Double
)
