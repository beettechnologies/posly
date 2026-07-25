package com.beettechnologies.posly.stores

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Timezone-aware helpers for bucketing events (e.g. for scheduled reports)
 * into a store's local business day. Built on java.time's ZoneId, which
 * correctly accounts for DST transitions and historical offset changes -
 * do not hand-roll offset arithmetic here.
 */
object StoreTimeZone {

    /** True if [timezoneId] is a valid IANA timezone identifier. */
    fun isValid(timezoneId: String): Boolean =
        runCatching { ZoneId.of(timezoneId) }.isSuccess

    /** The local calendar date, in the store's timezone, that [instant] falls on. */
    fun toLocalDate(instant: Instant, timezoneId: String): LocalDate =
        instant.atZone(ZoneId.of(timezoneId)).toLocalDate()

    /** The instant (UTC) at which [date] begins in the store's timezone. */
    fun startOfLocalDay(date: LocalDate, timezoneId: String): Instant =
        date.atStartOfDay(ZoneId.of(timezoneId)).toInstant()

    /** The instant (UTC) at which [date] ends (i.e. the next day begins) in the store's timezone. */
    fun endOfLocalDay(date: LocalDate, timezoneId: String): Instant =
        date.plusDays(1).atStartOfDay(ZoneId.of(timezoneId)).toInstant()

    /** Whether [instant] falls within [date]'s local business day for this store's timezone. */
    fun isWithinLocalDay(instant: Instant, date: LocalDate, timezoneId: String): Boolean {
        val start = startOfLocalDay(date, timezoneId)
        val end = endOfLocalDay(date, timezoneId)
        return !instant.isBefore(start) && instant.isBefore(end)
    }
}
