package com.nuvio.app.totaleclipse

import com.nuvio.app.features.watchprogress.WatchProgressEntry

/** Total Eclipse: " · 18 min left" for a title in progress, or "" when its length isn't known. */
internal fun timeLeftSuffix(entry: WatchProgressEntry?): String {
    val left = ((entry?.durationMs ?: 0L) - (entry?.lastPositionMs ?: 0L)) / 60_000L
    if (entry == null || entry.durationMs <= 0L || entry.lastPositionMs <= 0L || left < 1) return ""
    return if (left >= 60) " · ${left / 60}h ${left % 60}m left" else " · ${left}m left"
}
