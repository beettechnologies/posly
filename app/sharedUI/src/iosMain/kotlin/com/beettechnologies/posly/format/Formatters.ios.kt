package com.beettechnologies.posly.format

import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterCurrencyStyle
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName

/** Written per this repo's established `.ios.kt` actual convention - not compiled/verified in this environment (no Xcode toolchain available this session). */
actual fun formatCurrencyAmount(amount: Double, currencyCode: String, localeTag: String): String {
    val formatter = NSNumberFormatter()
    formatter.numberStyle = NSNumberFormatterCurrencyStyle
    formatter.locale = NSLocale(localeTag)
    formatter.currencyCode = currencyCode
    return formatter.stringFromNumber(NSNumber(double = amount)) ?: "$amount $currencyCode"
}

actual fun formatInstant(isoInstant: String, localeTag: String, timeZoneId: String): String {
    val isoFormatter = NSISO8601DateFormatter()
    val date = isoFormatter.dateFromString(isoInstant) ?: return isoInstant
    val displayFormatter = NSDateFormatter()
    displayFormatter.locale = NSLocale(localeTag)
    displayFormatter.timeZone = NSTimeZone.timeZoneWithName(timeZoneId)
    displayFormatter.dateStyle = NSDateFormatterMediumStyle
    displayFormatter.timeStyle = NSDateFormatterMediumStyle
    return displayFormatter.stringFromDate(date)
}
