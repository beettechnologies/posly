package com.beettechnologies.posly.format

import android.icu.text.DateFormat
import android.icu.util.TimeZone as IcuTimeZone
import java.text.NumberFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.TimeZone

actual fun formatCurrencyAmount(amount: Double, currencyCode: String, localeTag: String): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(localeTag))
    formatter.currency = Currency.getInstance(currencyCode)
    return formatter.format(amount)
}

actual fun formatInstant(isoInstant: String, localeTag: String, timeZoneId: String): String {
    val epochMillis = parseIsoInstantToEpochMillis(isoInstant)
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.forLanguageTag(localeTag))
    formatter.timeZone = IcuTimeZone.getTimeZone(timeZoneId)
    return formatter.format(Date(epochMillis))
}

private val ISO_INSTANT_PATTERN = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?Z""")

/** Parses an ISO-8601 UTC instant (e.g. "2026-01-01T00:00:00Z", as every timestamp crosses the wire from the server) without `java.time`, which needs API 26+ - this app's minSdk is 24. */
private fun parseIsoInstantToEpochMillis(isoInstant: String): Long {
    val groups = ISO_INSTANT_PATTERN.find(isoInstant)?.groupValues
        ?: error("Not a supported ISO-8601 UTC instant: $isoInstant")
    val millis = groups[7].let { fraction -> if (fraction.isNotEmpty()) (fraction.drop(1) + "000").take(3).toInt() else 0 }
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    calendar.clear()
    calendar.set(groups[1].toInt(), groups[2].toInt() - 1, groups[3].toInt(), groups[4].toInt(), groups[5].toInt(), groups[6].toInt())
    calendar.set(Calendar.MILLISECOND, millis)
    return calendar.timeInMillis
}
