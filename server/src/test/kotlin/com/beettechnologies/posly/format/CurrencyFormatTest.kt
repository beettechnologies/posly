package com.beettechnologies.posly.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CurrencyFormatTest {

    @Test
    fun `formats USD per en-US conventions`() {
        assertEquals("$1,234.50", CurrencyFormat.format(1234.50, "USD", "en-US"))
    }

    @Test
    fun `formats EUR per de-DE conventions, with comma decimal and trailing symbol`() {
        assertEquals("1.234,50 €", CurrencyFormat.format(1234.50, "EUR", "de-DE"))
    }

    @Test
    fun `currency and locale are independent - a non-native currency still uses the locale's own grouping and decimal conventions`() {
        assertEquals("1.234,50 $", CurrencyFormat.format(1234.50, "USD", "de-DE"))
    }

    @Test
    fun `rounds to the currency's minor unit rather than truncating`() {
        assertEquals("$1.23", CurrencyFormat.format(1.234, "USD", "en-US"))
    }

    /**
     * Documents current behavior, not a spec: [CurrencyFormat.format] sets `currency` on a
     * formatter built from [localeTag]'s *own* default currency, so a zero-decimal currency like
     * JPY does not get its fraction digits reset to 0 - it inherits en-US's 2. A store selling in
     * JPY would show cents that don't exist. Flagged to the user rather than silently fixed, since
     * fixing it changes every receipt/report that formats a zero-decimal currency.
     */
    @Test
    fun `BUG- JPY still shows 2 fraction digits because setting currency after construction doesn't reset them`() {
        assertEquals("¥1,234.50", CurrencyFormat.format(1234.5, "JPY", "en-US"))
    }

    @Test
    fun `negative amounts are formatted, not just truncated to positive`() {
        assertEquals("-$5.00", CurrencyFormat.format(-5.0, "USD", "en-US"))
    }

    @Test
    fun `an unknown ISO 4217 currency code throws rather than silently formatting`() {
        assertFailsWith<IllegalArgumentException> { CurrencyFormat.format(1.0, "NOTREAL", "en-US") }
    }
}
