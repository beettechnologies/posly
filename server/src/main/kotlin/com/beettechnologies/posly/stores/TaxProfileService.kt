package com.beettechnologies.posly.stores

import com.beettechnologies.posly.db.TaxProfilesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.RoundingMode

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

private fun rowToTaxProfile(row: ResultRow) = TaxProfile(
    id = row[TaxProfilesTable.id],
    name = row[TaxProfilesTable.name],
    rates = row[TaxProfilesTable.rates],
    pricingMode = PricingMode.valueOf(row[TaxProfilesTable.pricingMode]),
    roundingMode = RoundingMode.valueOf(row[TaxProfilesTable.roundingMode]),
    createdAt = row[TaxProfilesTable.createdAt],
    updatedAt = row[TaxProfilesTable.updatedAt]
)

class TaxProfileService {

    fun createProfile(
        name: String,
        rates: List<TaxRate>,
        pricingMode: PricingMode = PricingMode.EXCLUSIVE,
        roundingMode: RoundingMode = RoundingMode.HALF_UP
    ): TaxProfile = transaction {
        val profile = TaxProfile(name = name, rates = rates, pricingMode = pricingMode, roundingMode = roundingMode)
        TaxProfilesTable.insert {
            it[id] = profile.id
            it[TaxProfilesTable.name] = profile.name
            it[TaxProfilesTable.rates] = profile.rates
            it[TaxProfilesTable.pricingMode] = profile.pricingMode.name
            it[TaxProfilesTable.roundingMode] = profile.roundingMode.name
            it[createdAt] = profile.createdAt
            it[updatedAt] = profile.updatedAt
        }
        profile
    }

    fun getProfile(id: String): TaxProfile? = transaction {
        TaxProfilesTable.selectAll().where { TaxProfilesTable.id eq id }.singleOrNull()?.let { rowToTaxProfile(it) }
    }

    fun listProfiles(): List<TaxProfile> = transaction {
        TaxProfilesTable.selectAll().map { rowToTaxProfile(it) }
    }

    fun updateProfile(
        id: String,
        name: String?,
        rates: List<TaxRate>?,
        pricingMode: PricingMode? = null,
        roundingMode: RoundingMode? = null
    ): UpdateTaxProfileResult = transaction {
        val existing = TaxProfilesTable.selectAll().where { TaxProfilesTable.id eq id }.singleOrNull()?.let { rowToTaxProfile(it) }
            ?: return@transaction UpdateTaxProfileResult.NotFound
        val updated = existing.copy(
            name = name ?: existing.name,
            rates = rates ?: existing.rates,
            pricingMode = pricingMode ?: existing.pricingMode,
            roundingMode = roundingMode ?: existing.roundingMode,
            updatedAt = System.currentTimeMillis()
        )
        TaxProfilesTable.update({ TaxProfilesTable.id eq id }) {
            it[TaxProfilesTable.name] = updated.name
            it[TaxProfilesTable.rates] = updated.rates
            it[TaxProfilesTable.pricingMode] = updated.pricingMode.name
            it[TaxProfilesTable.roundingMode] = updated.roundingMode.name
            it[updatedAt] = updated.updatedAt
        }
        UpdateTaxProfileResult.Updated(updated)
    }

    fun deleteProfile(id: String): Boolean = transaction {
        TaxProfilesTable.deleteWhere { TaxProfilesTable.id eq id } > 0
    }

    fun calculateTax(taxProfileId: String, amount: Double): CalculateTaxResult {
        val profile = getProfile(taxProfileId) ?: return CalculateTaxResult.ProfileNotFound
        val result = TaxEngine.calculate(profile, amount)
        return CalculateTaxResult.Success(
            subtotal = result.subtotal,
            breakdown = result.breakdown.map { TaxBreakdownItem(it.name, it.ratePercent, it.amount) },
            totalTax = result.totalTax,
            total = result.total
        )
    }
}
