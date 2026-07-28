package com.beettechnologies.posly.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormattersTest {

    @Test
    fun `formats USD per en-US conventions`() {
        assertEquals("$1,234.50", formatCurrencyAmount(1234.50, "USD", "en-US"))
    }

    @Test
    fun `formats EUR per de-DE conventions, with comma decimal and trailing symbol`() {
        assertEquals("1.234,50 €", formatCurrencyAmount(1234.50, "EUR", "de-DE"))
    }

    @Test
    fun `currency and locale are independent - a non-native currency still uses the locale's own grouping and decimal conventions`() {
        assertEquals("1.234,50 $", formatCurrencyAmount(1234.50, "USD", "de-DE"))
    }

    @Test
    fun `negative amounts are formatted, not just truncated to positive`() {
        assertEquals("-$5.00", formatCurrencyAmount(-5.0, "USD", "en-US"))
    }

    @Test
    fun `an unknown ISO 4217 currency code throws rather than silently formatting`() {
        assertFailsWith<IllegalArgumentException> { formatCurrencyAmount(1.0, "NOTREAL", "en-US") }
    }

    @Test
    fun `formats an instant per en-US MEDIUM date-time style in the given timezone`() {
        val result = formatInstant("2026-01-15T12:30:00Z", "en-US", "America/New_York")
        assertTrue(result.contains("2026") && result.contains("Jan") && result.contains("7:30"),
            "expected an en-US MEDIUM date-time for 2026-01-15T12:30:00Z in America/New_York: $result")
    }

    @Test
    fun `the same instant renders different clock times in different timezones`() {
        val utc = formatInstant("2026-06-15T00:00:00Z", "en-US", "UTC")
        val tokyo = formatInstant("2026-06-15T00:00:00Z", "en-US", "Asia/Tokyo")
        assertTrue(utc != tokyo, "expected UTC and Asia/Tokyo renderings of the same instant to differ: utc=$utc tokyo=$tokyo")
    }

    @Test
    fun `date formatting is locale-sensitive`() {
        val en = formatInstant("2026-01-15T12:00:00Z", "en-US", "UTC")
        val de = formatInstant("2026-01-15T12:00:00Z", "de-DE", "UTC")
        assertTrue(en != de, "expected en-US and de-DE renderings to differ: en=$en de=$de")
    }
}
