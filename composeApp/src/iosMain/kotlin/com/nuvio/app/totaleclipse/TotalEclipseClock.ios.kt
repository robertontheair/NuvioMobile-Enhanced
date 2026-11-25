package com.nuvio.app.totaleclipse

import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale

internal actual object TotalEclipseClock {
    // "j" is the locale's preferred hour format; with an AM/PM marker ("a") it's a 12-hour clock.
    actual val uses24Hours: Boolean by lazy {
        NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale)?.contains('a') != true
    }
}
