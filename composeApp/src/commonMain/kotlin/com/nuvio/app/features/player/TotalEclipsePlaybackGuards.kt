package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.totaleclipse.TotalEclipseStallGuard
import kotlinx.coroutines.delay
import kotlin.time.Clock

private const val TAG = "TotalEclipse/Playback"
private const val MAX_MID_PLAYBACK_SWITCHES = 3
private const val LIVE_STALL_SECONDS = 20
private const val LIVE_RETRIES = 3
private const val LIVE_RETRY_WINDOW_MS = 180_000L

private fun nowMs() = Clock.System.now().toEpochMilliseconds()

/**
 * Total Eclipse:
 *  - Movies and episodes: a source that keeps buffering is replaced by the next one at the same
 *    position when the app chose it; when you chose it, a message says it's slow.
 *  - Live TV: a feed that ends or stops sending is opened again, at most three times in three
 *    minutes, then left to the error screen or the spinner.
 */
@Composable
internal fun PlayerScreenRuntime.BindTotalEclipsePlaybackGuards() {
    val liveRetries = remember { ArrayDeque<Long>() }
    LaunchedEffect(activePlaybackKey, isLiveTvPlayback) {
        if (isLiveTvPlayback) {
            var stalled = 0
            while (true) {
                delay(1_000)
                val ended = playbackSnapshot.isEnded
                val waiting = shouldPlay && initialLoadCompleted && playbackSnapshot.isLoading && errorMessage == null
                stalled = if (waiting) stalled + 1 else 0
                if (!ended && stalled < LIVE_STALL_SECONDS) continue
                stalled = 0
                val now = nowMs()
                while (liveRetries.isNotEmpty() && now - liveRetries.first() > LIVE_RETRY_WINDOW_MS) liveRetries.removeFirst()
                if (errorMessage != null) continue
                if (liveRetries.size >= LIVE_RETRIES) {
                    if (ended) errorMessage = "live feed ended" // shown as a plain message with Try Again
                    continue
                }
                liveRetries.addLast(now)
                InAppLogger.info(TAG, "live feed ${if (ended) "ended" else "stalled"}; opening it again (${liveRetries.size}/$LIVE_RETRIES)")
                playbackSnapshot = playbackSnapshot.copy(isEnded = false, isLoading = true)
                shouldPlay = true
                initialLoadCompleted = false
                liveRetryCount += 1
                delay(3_000L * liveRetries.size)
            }
        }
        val guard = TotalEclipseStallGuard().apply { expectBuffering(nowMs()) }
        var lastPosition = 0L
        var toldUser = false
        var tries = 0
        while (true) {
            delay(1_000)
            val snapshot = playbackSnapshot
            val active = shouldPlay && initialLoadCompleted && errorMessage == null && !snapshot.isEnded &&
                !isScrubbingTimeline && sourceFallbackJob?.isActive != true
            val jumped = kotlin.math.abs(snapshot.positionMs - lastPosition) > 8_000L
            lastPosition = snapshot.positionMs
            if (!active || jumped) {
                guard.expectBuffering(nowMs())
                continue
            }
            if (!guard.tick(nowMs(), snapshot.isLoading)) continue
            guard.expectBuffering(nowMs())
            InAppLogger.warn(TAG, "the source is too slow (${activeProviderName}); position ${snapshot.positionMs / 1000}s")
            if (tries++ == 0 && midPlaybackSwitches < MAX_MID_PLAYBACK_SWITCHES &&
                tryFallbackToNextSourceAfterError(message = null, midPlayback = true)
            ) {
                NuvioToastController.show("This source is too slow. Trying another…")
            } else if (!toldUser) {
                toldUser = true
                NuvioToastController.show("This source is too slow. Try another one from Sources.")
            }
        }
    }

    // A rating, once, when a movie or a season's last episode ends (never over an automatic next episode).
    LaunchedEffect(activePlaybackKey, playbackSnapshot.isEnded) {
        if (!playbackSnapshot.isEnded || isLiveTvPlayback) return@LaunchedEffect
        val next = nextEpisodeInfo
        val finale = activeSeasonNumber != null && (next == null || next.season != activeSeasonNumber)
        if (isMoviePlayback || finale) totalEclipseRatingDue = true
    }
}
