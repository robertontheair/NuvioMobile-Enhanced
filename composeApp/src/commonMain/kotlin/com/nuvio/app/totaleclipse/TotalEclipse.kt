package com.nuvio.app.totaleclipse

/**
 * Total Eclipse: Rob's household build of Nuvio Enhanced, the iPhone/iPad companion to the
 * Total Eclipse (HomeTV) Apple TV app. Fork-only switches live here so upstream merges stay
 * easy to review; every changed upstream line is marked `Total Eclipse:`.
 */
object TotalEclipse {
    /** Nuvio's alternate icons are Nuvio-branded, so the picker is hidden. */
    const val SHOW_APP_ICON_PICKER = false

    /** No Nuvio account: start in a local (anonymous) account, as the Apple TVs do. */
    const val SKIP_NUVIO_SIGN_IN = true

    /** Read the household's profiles, add-ons, keys and collections from the TVs' iCloud store. */
    const val HOUSEHOLD_SYNC_ENABLED = true

    /**
     * On cellular, auto-play uses the smart ranking (with "Save mobile data": ≤1080p, a few Mbps)
     * instead of the add-on's first stream, which is usually a 4K remux (Rob buffering, 2026-11-24).
     */
    const val CELLULAR_USES_SMART_AUTOPLAY = true

    /** Watch Party is off on the Apple TVs too (HomeTV `WatchPartyPolicy.swift`). */
    const val WATCH_PARTY_ENABLED = false
}
