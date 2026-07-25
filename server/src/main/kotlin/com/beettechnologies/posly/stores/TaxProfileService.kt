package com.beettechnologies.posly.stores

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

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

    fun createProfile(name: String, rates: List<TaxRate>): TaxProfile {
        val profile = TaxProfile(name = name, rates = rates)
        profiles[profile.id] = profile
        return profile
    }

    fun getProfile(id: String): TaxProfile? = profiles[id]

    fun listProfiles(): List<TaxProfile> = profiles.values.toList()

    fun updateProfile(id: String, name: String?, rates: List<TaxRate>?): UpdateTaxProfileResult {
        val existing = profiles[id] ?: return UpdateTaxProfileResult.NotFound
        val updated = existing.copy(
            name = name ?: existing.name,
            rates = rates ?: existing.rates,
            updatedAt = System.currentTimeMillis()
        )
        profiles[id] = updated
        return UpdateTaxProfileResult.Updated(updated)
    }

    fun deleteProfile(id: String): Boolean = profiles.remove(id) != null

    fun calculateTax(taxProfileId: String, amount: Double): CalculateTaxResult {
        val profile = profiles[taxProfileId] ?: return CalculateTaxResult.ProfileNotFound
        val breakdown = profile.rates.map { rate ->
            TaxBreakdownItem(
                name = rate.name,
                ratePercent = rate.ratePercent,
                amount = roundCents(amount * rate.ratePercent / 100.0)
            )
        }
        val totalTax = roundCents(breakdown.sumOf { it.amount })
        return CalculateTaxResult.Success(
            subtotal = roundCents(amount),
            breakdown = breakdown,
            totalTax = totalTax,
            total = roundCents(amount + totalTax)
        )
    }

    private fun roundCents(value: Double): Double = round(value * 100.0) / 100.0
}
