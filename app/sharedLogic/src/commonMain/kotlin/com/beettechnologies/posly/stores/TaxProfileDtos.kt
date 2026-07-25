package com.beettechnologies.posly.stores

import kotlinx.serialization.Serializable

@Serializable
data class TaxRateRequest(val name: String, val ratePercent: Double)

@Serializable
data class CreateTaxProfileRequest(
    val name: String,
    val rates: List<TaxRateRequest>
)

@Serializable
data class UpdateTaxProfileRequest(
    val name: String? = null,
    val rates: List<TaxRateRequest>? = null
)

@Serializable
data class TaxRateResponse(val name: String, val ratePercent: Double)

@Serializable
data class TaxProfileResponse(
    val id: String,
    val name: String,
    val rates: List<TaxRateResponse>,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class CalculateTaxRequest(val amount: Double)

@Serializable
data class TaxBreakdownItem(val name: String, val ratePercent: Double, val amount: Double)

@Serializable
data class CalculateTaxResponse(
    val subtotal: Double,
    val breakdown: List<TaxBreakdownItem>,
    val totalTax: Double,
    val total: Double
)
