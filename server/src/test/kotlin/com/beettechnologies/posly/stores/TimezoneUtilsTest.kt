package com.beettechnologies.posly.stores

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimezoneUtilsTest {

    @Test
    fun `isValid accepts a real IANA zone and rejects garbage`() {
        assertTrue(StoreTimeZone.isValid("America/New_York"))
        assertTrue(StoreTimeZone.isValid("Asia/Tokyo"))
        assertFalse(StoreTimeZone.isValid("Not/AZone"))
        assertFalse(StoreTimeZone.isValid(""))
    }

    @Test
    fun `toLocalDate crosses the date line correctly ahead of UTC`() {
        // 16:00 UTC + 9h (Tokyo, no DST) rolls into the next calendar day.
        val instant = Instant.parse("2026-01-15T16:00:00Z")
        assertEquals(LocalDate.of(2026, 1, 16), StoreTimeZone.toLocalDate(instant, "Asia/Tokyo"))
    }

    @Test
    fun `toLocalDate stays on the same day behind UTC`() {
        // Same instant, but New York (UTC-5 in January) is still on the 15th.
        val instant = Instant.parse("2026-01-15T16:00:00Z")
        assertEquals(LocalDate.of(2026, 1, 15), StoreTimeZone.toLocalDate(instant, "America/New_York"))
    }

    @Test
    fun `start of local day reflects the DST offset in effect, not a fixed offset`() {
        // January is always outside US DST (EST, UTC-5); July is always inside it (EDT, UTC-4).
        // A hand-rolled fixed-offset implementation would get one of these wrong.
        val winterStart = StoreTimeZone.startOfLocalDay(LocalDate.of(2026, 1, 15), "America/New_York")
        val summerStart = StoreTimeZone.startOfLocalDay(LocalDate.of(2026, 7, 15), "America/New_York")

        assertEquals(Instant.parse("2026-01-15T05:00:00Z"), winterStart)
        assertEquals(Instant.parse("2026-07-15T04:00:00Z"), summerStart)
    }

    @Test
    fun `end of local day is exactly 24h of wall-clock time later, respecting DST`() {
        val date = LocalDate.of(2026, 1, 15)
        val start = StoreTimeZone.startOfLocalDay(date, "America/New_York")
        val end = StoreTimeZone.endOfLocalDay(date, "America/New_York")
        assertEquals(Instant.parse("2026-01-16T05:00:00Z"), end)
        assertTrue(end.isAfter(start))
    }

    @Test
    fun `isWithinLocalDay treats the window as start-inclusive end-exclusive`() {
        val date = LocalDate.of(2026, 1, 15)
        val timezone = "America/New_York"
        val start = StoreTimeZone.startOfLocalDay(date, timezone)
        val end = StoreTimeZone.endOfLocalDay(date, timezone)

        assertTrue(StoreTimeZone.isWithinLocalDay(start, date, timezone))
        assertTrue(StoreTimeZone.isWithinLocalDay(start.plusSeconds(1), date, timezone))
        assertFalse(StoreTimeZone.isWithinLocalDay(start.minusSeconds(1), date, timezone))
        assertFalse(StoreTimeZone.isWithinLocalDay(end, date, timezone))
    }
}
