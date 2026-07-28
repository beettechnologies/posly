package com.beettechnologies.posly.format

import java.util.Currency
import java.util.Locale

/**
 * Formats a money amount using an arbitrary locale's formatting conventions (decimal separator,
 * digit grouping, symbol placement) combined with an arbitrary ISO 4217 currency code - the two
 * are independent settings on a [com.beettechnologies.posly.stores.Store], so
 * `NumberFormat.getCurrencyInstance(locale)` alone isn't enough (it infers the currency FROM the
 * locale). Shared by [com.beettechnologies.posly.receipts.ReceiptRenderer] and
 * [com.beettechnologies.posly.finance.FinanceReportBuilder].
 */
object CurrencyFormat {
    fun format(amount: Double, currencyCode: String, localeTag: String): String {
        val formatter = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag(localeTag))
        formatter.currency = Currency.getInstance(currencyCode)
        return formatter.format(amount)
    }
}
