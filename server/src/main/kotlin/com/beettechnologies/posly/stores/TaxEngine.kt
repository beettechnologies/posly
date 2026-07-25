package com.beettechnologies.posly.stores

import com.beettechnologies.posly.products.TaxCategory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private val TAXABLE_CATEGORIES = setOf(TaxCategory.STANDARD, TaxCategory.REDUCED)
private val CALCULATION_PRECISION = MathContext(20)

data class TaxLineAmount(val name: String, val ratePercent: Double, val amount: Double)

data class TaxCalculationResult(
    val subtotal: Double,
    val breakdown: List<TaxLineAmount>,
    val totalTax: Double,
    val total: Double
)

data class TaxCalculateLine(val id: String, val amount: Double, val taxCategory: TaxCategory)

data class TaxLinesCalculationResult(
    val taxableAmount: Double,
    val exemptAmount: Double,
    val breakdown: List<TaxLineAmount>,
    val totalTax: Double,
    val total: Double
)

/**
 * Pure, stateless calculator behind every [TaxProfile]. Rates are applied in ascending
 * [TaxRate.order]; a rate either taxes the original base independently (the common "VAT +
 * municipal, both on the same subtotal" case) or, if [TaxRate.compoundsOnPrior], taxes the
 * running total after every lower-order rate's already-rounded tax - true tax-on-tax stacking.
 * [TaxProfile.pricingMode] INCLUSIVE treats the given amount as already containing tax and backs
 * out the pre-tax base (via a symbolically-computed combined multiplier, so the same forward
 * calculation - and the same [TaxProfile.roundingMode] - drives both directions). All internal
 * arithmetic uses BigDecimal; only the public boundary is Double, since a configurable rounding
 * mode isn't reliably expressible in binary floating point.
 */
object TaxEngine {

    fun calculate(profile: TaxProfile, taxableAmount: Double): TaxCalculationResult {
        val orderedRates = profile.rates.sortedBy { it.order }
        val base = if (profile.pricingMode == PricingMode.INCLUSIVE) {
            val multiplier = combinedMultiplier(orderedRates)
            BigDecimal.valueOf(taxableAmount).divide(multiplier, CALCULATION_PRECISION)
        } else {
            BigDecimal.valueOf(taxableAmount)
        }
        return forwardCalculate(orderedRates, base, profile.roundingMode)
    }

    fun calculateForLines(profile: TaxProfile, lines: List<TaxCalculateLine>): TaxLinesCalculationResult {
        val taxableSum = lines.filter { it.taxCategory in TAXABLE_CATEGORIES }.sumOf { it.amount }
        val exemptSum = lines.filterNot { it.taxCategory in TAXABLE_CATEGORIES }.sumOf { it.amount }
        val calc = calculate(profile, taxableSum)
        val exemptRounded = BigDecimal.valueOf(exemptSum).setScale(2, profile.roundingMode)
        return TaxLinesCalculationResult(
            taxableAmount = calc.subtotal,
            exemptAmount = exemptRounded.toDouble(),
            breakdown = calc.breakdown,
            totalTax = calc.totalTax,
            total = BigDecimal.valueOf(calc.total).add(exemptRounded).toDouble()
        )
    }

    private fun forwardCalculate(orderedRates: List<TaxRate>, base: BigDecimal, roundingMode: RoundingMode): TaxCalculationResult {
        var runningTax = BigDecimal.ZERO
        val breakdown = orderedRates.map { rate ->
            val rateBase = if (rate.compoundsOnPrior) base.add(runningTax) else base
            val raw = rateBase.multiply(BigDecimal.valueOf(rate.ratePercent), CALCULATION_PRECISION)
                .divide(BigDecimal(100), CALCULATION_PRECISION)
            val rounded = raw.setScale(2, roundingMode)
            runningTax = runningTax.add(rounded)
            TaxLineAmount(rate.name, rate.ratePercent, rounded.toDouble())
        }
        val subtotal = base.setScale(2, roundingMode)
        val total = subtotal.add(runningTax)
        return TaxCalculationResult(
            subtotal = subtotal.toDouble(),
            breakdown = breakdown,
            totalTax = runningTax.toDouble(),
            total = total.toDouble()
        )
    }

    /** 1 + the tax that a base of exactly 1 would generate, computed at full precision (no rounding) - dividing the inclusive amount by this recovers the pre-tax base. */
    private fun combinedMultiplier(orderedRates: List<TaxRate>): BigDecimal {
        var runningTax = BigDecimal.ZERO
        for (rate in orderedRates) {
            val rateBase = if (rate.compoundsOnPrior) BigDecimal.ONE.add(runningTax) else BigDecimal.ONE
            val tax = rateBase.multiply(BigDecimal.valueOf(rate.ratePercent), CALCULATION_PRECISION)
                .divide(BigDecimal(100), CALCULATION_PRECISION)
            runningTax = runningTax.add(tax)
        }
        return BigDecimal.ONE.add(runningTax)
    }
}
