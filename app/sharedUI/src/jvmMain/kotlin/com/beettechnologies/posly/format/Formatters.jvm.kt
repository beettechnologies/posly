package com.beettechnologies.posly.format

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

actual fun formatCurrencyAmount(amount: Double, currencyCode: String, localeTag: String): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag(localeTag))
    formatter.currency = Currency.getInstance(currencyCode)
    return formatter.format(amount)
}

actual fun formatInstant(isoInstant: String, localeTag: String, timeZoneId: String): String {
    val instant = Instant.parse(isoInstant)
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.forLanguageTag(localeTag))
        .withZone(ZoneId.of(timeZoneId))
        .format(instant)
}
