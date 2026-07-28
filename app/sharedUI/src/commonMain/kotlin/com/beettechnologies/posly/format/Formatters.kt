package com.beettechnologies.posly.format

/** Formats [amount] per [localeTag]'s conventions (decimal separator, digit grouping, symbol placement) using the ISO 4217 [currencyCode] - the two are independent settings on a store, so a locale's own default currency is never assumed. */
expect fun formatCurrencyAmount(amount: Double, currencyCode: String, localeTag: String): String

/** Formats [isoInstant] (an ISO-8601 instant string, as every timestamp crosses the wire from the server) as a locale/timezone-aware date+time string. */
expect fun formatInstant(isoInstant: String, localeTag: String, timeZoneId: String): String
