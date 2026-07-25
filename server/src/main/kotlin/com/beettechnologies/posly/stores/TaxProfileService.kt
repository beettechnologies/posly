package com.beettechnologies.posly.stores

import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

sealed class UpdateTaxProfileResult {
    data class Updated(val profile: TaxProfile) : UpdateTaxProfileResult()
    data object NotFound : UpdateTaxProfileResult()
}

sealed class CalculateTaxResult {
    data class Success(
        val subtotal: Double,
        val breakdown: List<TaxBreakdownItem>,
        val totalTax: Double,
        val total: Double
    ) : CalculateTaxResult()
    data object ProfileNotFound : CalculateTaxResult()
}

class TaxProfileService {

    private val profiles = ConcurrentHashMap<String, TaxProfile>()

    fun createProfile(
        name: String,
        rates: List<TaxRate>,
        pricingMode: PricingMode = PricingMode.EXCLUSIVE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP
    ): TaxProfile {
        val profile = TaxProfile(name = name, rates = rates, pricingMode = pricingMode, roundingMode = roundingMode)
        profiles[profile.id] = profile
        return profile
    }

    fun getProfile(id: String): TaxProfile? = profiles[id]

    fun listProfiles(): List<TaxProfile> = profiles.values.toList()

    fun updateProfile(
        id: String,
        name: String?,
        rates: List<TaxRate>?,
        pricingMode: PricingMode? = null,
        roundingMode: RoundingMode? = null
    ): UpdateTaxProfileResult {
        val existing = profiles[id] ?: return UpdateTaxProfileResult.NotFound
        val updated = existing.copy(
            name = name ?: existing.name,
            rates = rates ?: existing.rates,
            pricingMode = pricingMode ?: existing.pricingMode,
            roundingMode = roundingMode ?: existing.roundingMode,
            updatedAt = System.currentTimeMillis()
        )
        profiles[id] = updated
        return UpdateTaxProfileResult.Updated(updated)
    }

    fun deleteProfile(id: String): Boolean = profiles.remove(id) != null

    fun calculateTax(taxProfileId: String, amount: Double): CalculateTaxResult {
        val profile = profiles[taxProfileId] ?: return CalculateTaxResult.ProfileNotFound
        val result = TaxEngine.calculate(profile, amount)
        return CalculateTaxResult.Success(
            subtotal = result.subtotal,
            breakdown = result.breakdown.map { TaxBreakdownItem(it.name, it.ratePercent, it.amount) },
            totalTax = result.totalTax,
            total = result.total
        )
    }
}
