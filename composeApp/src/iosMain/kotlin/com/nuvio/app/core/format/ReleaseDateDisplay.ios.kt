package com.nuvio.app.core.format

import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT

internal actual fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean, shortMonth: Boolean): String {
    val epochMs = parseEpisodeReleaseEpochMs(isoDate) ?: return isoDate
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1_000.0)
    return NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = localeTag)
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
        val month = if (shortMonth) "MMM" else "MMMM"
        setLocalizedDateFormatFromTemplate(if (includeYear) "d${month}y" else "d$month")
    }.stringFromDate(date)
}
