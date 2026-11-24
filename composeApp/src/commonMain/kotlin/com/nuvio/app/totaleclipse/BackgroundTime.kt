package com.nuvio.app.totaleclipse

/**
 * Total Eclipse: runs [block] with the extra time iOS gives an app that's just been sent to the
 * background (about 30 s), so a Trakt scrobble sent as you leave (pause / stop) still arrives.
 * Elsewhere it just runs [block].
 */
internal expect suspend fun <T> withBackgroundTime(name: String, block: suspend () -> T): T
