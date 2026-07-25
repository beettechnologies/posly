package com.beettechnologies.posly.stores

import kotlinx.serialization.Serializable

@Serializable
data class TaxRateRequest(
    val name: String,
    val ratePercent: Double,
    val order: Int = 0,
    val compoundsOnPrior: Boolean = false
)

@Serializable
data class CreateTaxProfileRequest(
    val name: String,
    val rates: List<TaxRateRequest>,
    val pricingMode: String = "EXCLUSIVE",
    val roundingMode: String = "HALF_UP"
)

@Serializable
data class UpdateTaxProfileRequest(
    val name: String? = null,
    val rates: List<TaxRateRequest>? = null,
    val pricingMode: String? = null,
    val roundingMode: String? = null
)

@Serializable
data class TaxRateResponse(
    val name: String,
    val ratePercent: Double,
    val order: Int,
    val compoundsOnPrior: Boolean
)

@Serializable
data class TaxProfileResponse(
    val id: String,
    val name: String,
    val rates: List<TaxRateResponse>,
    val pricingMode: String,
    val roundingMode: String,
    val createdAt: Long,
    val updatedAt: Long
)

fun TaxProfile.toResponse() = TaxProfileResponse(
    id = id,
    name = name,
    rates = rates.map { TaxRateResponse(it.name, it.ratePercent, it.order, it.compoundsOnPrior) },
    pricingMode = pricingMode.name,
    roundingMode = roundingMode.name,
    createdAt = createdAt,
    updatedAt = updatedAt
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
