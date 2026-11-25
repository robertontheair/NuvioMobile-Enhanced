package com.nuvio.app.totaleclipse

/** Total Eclipse: whether the phone shows a 24-hour clock. */
internal expect object TotalEclipseClock {
    val uses24Hours: Boolean
}
