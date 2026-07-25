package com.beettechnologies.posly.stores

import com.beettechnologies.posly.products.TaxCategory
import java.math.RoundingMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private fun profile(
    rates: List<TaxRate>,
    pricingMode: PricingMode = PricingMode.EXCLUSIVE,
    roundingMode: RoundingMode = RoundingMode.HALF_UP
) = TaxProfile(id = UUID.randomUUID().toString(), name = "Test Profile", rates = rates, pricingMode = pricingMode, roundingMode = roundingMode)

class TaxEngineTest {

    @Test
    fun `a single flat rate taxes the base directly`() {
        val result = TaxEngine.calculate(profile(listOf(TaxRate("Sales", 10.0))), 100.0)

        assertEquals(100.0, result.subtotal)
        assertEquals(10.0, result.totalTax)
        assertEquals(110.0, result.total)
    }

    @Test
    fun `composite non-compounding rates each tax the same base and sum`() {
        val result = TaxEngine.calculate(
            profile(listOf(TaxRate("State", 4.0), TaxRate("City", 4.5))),
            100.0
        )

        assertEquals(8.5, result.totalTax)
        assertEquals(108.5, result.total)
        assertEquals(2, result.breakdown.size)
    }

    @Test
    fun `a compounding rate taxes the running total after prior rates, not the original base`() {
        val result = TaxEngine.calculate(
            profile(
                listOf(
                    TaxRate("VAT", 20.0, order = 1, compoundsOnPrior = false),
                    TaxRate("Municipal", 5.0, order = 2, compoundsOnPrior = true)
                )
            ),
            100.0
        )

        assertEquals(20.0, result.breakdown[0].amount, "VAT taxes the original 100 base")
        assertEquals(6.0, result.breakdown[1].amount, "Municipal taxes 100 + 20 VAT = 120")
        assertEquals(26.0, result.totalTax)
        assertEquals(126.0, result.total)
    }

    @Test
    fun `rate order changes the compounding result, not just display order`() {
        // Scenario A: the non-compounding rate is applied first (order 1), the compounding rate second.
        val scenarioA = TaxEngine.calculate(
            profile(
                listOf(
                    TaxRate("A", 10.0, order = 1, compoundsOnPrior = false),
                    TaxRate("B", 10.0, order = 2, compoundsOnPrior = true)
                )
            ),
            100.0
        )
        // Scenario B: same two rates, but B (the compounding one) now comes first - since nothing
        // precedes it yet, it degenerates to taxing the plain base, changing the final total.
        val scenarioB = TaxEngine.calculate(
            profile(
                listOf(
                    TaxRate("A", 10.0, order = 2, compoundsOnPrior = false),
                    TaxRate("B", 10.0, order = 1, compoundsOnPrior = true)
                )
            ),
            100.0
        )

        assertEquals(21.0, scenarioA.totalTax)
        assertEquals(20.0, scenarioB.totalTax)
    }

    @Test
    fun `inclusive pricing backs out the exact pre-tax base for a single clean rate`() {
        val result = TaxEngine.calculate(
            profile(listOf(TaxRate("VAT", 20.0)), pricingMode = PricingMode.INCLUSIVE),
            120.0
        )

        assertEquals(100.0, result.subtotal)
        assertEquals(20.0, result.totalTax)
        assertEquals(120.0, result.total)
    }

    @Test
    fun `inclusive pricing round-trips exactly through composite compounding rates too`() {
        val result = TaxEngine.calculate(
            profile(
                listOf(
                    TaxRate("VAT", 20.0, order = 1, compoundsOnPrior = false),
                    TaxRate("Municipal", 5.0, order = 2, compoundsOnPrior = true)
                ),
                pricingMode = PricingMode.INCLUSIVE
            ),
            126.0
        )

        assertEquals(100.0, result.subtotal)
        assertEquals(26.0, result.totalTax)
        assertEquals(126.0, result.total)
    }

    @Test
    fun `inclusive pricing with a non-terminating decimal still resolves deterministically to the cent`() {
        val result = TaxEngine.calculate(
            profile(listOf(TaxRate("VAT", 15.0)), pricingMode = PricingMode.INCLUSIVE),
            100.0
        )

        assertEquals(86.96, result.subtotal)
        assertEquals(13.04, result.totalTax)
        assertEquals(100.0, result.total)
    }

    @Test
    fun `HALF_UP, HALF_DOWN and HALF_EVEN differ on an exact rounding tie`() {
        val ratesFor125 = listOf(TaxRate("Rate", 12.5))
        assertEquals(0.13, TaxEngine.calculate(profile(ratesFor125, roundingMode = RoundingMode.HALF_UP), 1.0).totalTax)
        assertEquals(0.12, TaxEngine.calculate(profile(ratesFor125, roundingMode = RoundingMode.HALF_DOWN), 1.0).totalTax)
        assertEquals(0.12, TaxEngine.calculate(profile(ratesFor125, roundingMode = RoundingMode.HALF_EVEN), 1.0).totalTax)

        val ratesFor135 = listOf(TaxRate("Rate", 13.5))
        assertEquals(0.14, TaxEngine.calculate(profile(ratesFor135, roundingMode = RoundingMode.HALF_UP), 1.0).totalTax)
        assertEquals(0.13, TaxEngine.calculate(profile(ratesFor135, roundingMode = RoundingMode.HALF_DOWN), 1.0).totalTax)
        assertEquals(0.14, TaxEngine.calculate(profile(ratesFor135, roundingMode = RoundingMode.HALF_EVEN), 1.0).totalTax, "0.135 rounds to the nearest even cent, 0.14")
    }

    @Test
    fun `UP always rounds away from zero and DOWN always truncates`() {
        val rates = listOf(TaxRate("Rate", 12.1))
        assertEquals(0.13, TaxEngine.calculate(profile(rates, roundingMode = RoundingMode.UP), 1.0).totalTax)
        assertEquals(0.12, TaxEngine.calculate(profile(rates, roundingMode = RoundingMode.DOWN), 1.0).totalTax)
    }

    @Test
    fun `an empty rate list produces zero tax`() {
        val result = TaxEngine.calculate(profile(emptyList()), 42.0)

        assertEquals(0.0, result.totalTax)
        assertEquals(42.0, result.total)
        assertEquals(emptyList(), result.breakdown)
    }

    @Test
    fun `calculateForLines partitions taxable from exempt lines and surfaces the exempt amount`() {
        val result = TaxEngine.calculateForLines(
            profile(listOf(TaxRate("Sales", 10.0))),
            listOf(
                TaxCalculateLine("1", 50.0, TaxCategory.STANDARD),
                TaxCalculateLine("2", 30.0, TaxCategory.EXEMPT),
                TaxCalculateLine("3", 20.0, TaxCategory.REDUCED),
                TaxCalculateLine("4", 10.0, TaxCategory.ZERO)
            )
        )

        assertEquals(70.0, result.taxableAmount)
        assertEquals(40.0, result.exemptAmount)
        assertEquals(7.0, result.totalTax)
        assertEquals(117.0, result.total)
    }

    @Test
    fun `all-exempt lines produce zero tax with the full amount visible as exempt`() {
        val result = TaxEngine.calculateForLines(
            profile(listOf(TaxRate("Sales", 10.0))),
            listOf(TaxCalculateLine("1", 25.0, TaxCategory.EXEMPT))
        )

        assertEquals(0.0, result.taxableAmount)
        assertEquals(25.0, result.exemptAmount)
        assertEquals(0.0, result.totalTax)
        assertEquals(25.0, result.total)
    }

    @Test
    fun `jurisdiction matrix - US state plus city, non-compounding`() {
        val result = TaxEngine.calculate(profile(listOf(TaxRate("State", 4.0), TaxRate("City", 4.5))), 100.0)
        assertEquals(8.5, result.totalTax)
        assertEquals(108.5, result.total)
    }

    @Test
    fun `jurisdiction matrix - GST then PST compounding on the GST-inclusive amount`() {
        val result = TaxEngine.calculate(
            profile(
                listOf(
                    TaxRate("GST", 5.0, order = 1, compoundsOnPrior = false),
                    TaxRate("PST", 7.0, order = 2, compoundsOnPrior = true)
                )
            ),
            200.0
        )
        assertEquals(10.0, result.breakdown[0].amount)
        assertEquals(14.7, result.breakdown[1].amount)
        assertEquals(24.7, result.totalTax)
        assertEquals(224.7, result.total)
    }

    @Test
    fun `jurisdiction matrix - EU-style VAT-inclusive retail pricing`() {
        val result = TaxEngine.calculate(profile(listOf(TaxRate("VAT", 19.0)), pricingMode = PricingMode.INCLUSIVE), 119.0)
        assertEquals(100.0, result.subtotal)
        assertEquals(19.0, result.totalTax)
        assertEquals(119.0, result.total)
    }
}
