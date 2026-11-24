package com.nuvio.app.totaleclipse

import com.nuvio.app.core.logging.InAppLogger
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Total Eclipse: one Trakt-wide back-off, as on the Apple TVs (HomeTV HomeTVProgressGuard): when
 * api.trakt.tv answers 429, every Trakt request waits out its Retry-After before going out again.
 * A wait of 30 seconds or less is slept through; a longer one (the daily limit) makes requests
 * fail fast with an error (callers already treat failed Trakt requests as such), without reaching
 * Trakt, until it's over.
 * Installed on the add-on HTTP client, which every Trakt call uses.
 */
internal object TraktRateLimitGate {
    private const val HOST = "api.trakt.tv"
    private const val SLEEP_LIMIT_MS = 30_000L
    private const val DEFAULT_RETRY_SECONDS = 60L

    @kotlin.concurrent.Volatile private var blockedUntilMs = 0L

    class Blocked(val retryInSeconds: Long) :
        IllegalStateException("Trakt asked to wait ${retryInSeconds}s (rate limit)")

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    val plugin = createClientPlugin("TotalEclipseTraktRateLimit") {
        onRequest { request, _ ->
            if (request.url.host != HOST) return@onRequest
            val waitMs = blockedUntilMs - now()
            if (waitMs <= 0) return@onRequest
            if (waitMs <= SLEEP_LIMIT_MS) delay(waitMs) else throw Blocked((waitMs + 999) / 1000)
        }
        onResponse { response ->
            if (response.call.request.url.host != HOST) return@onResponse
            if (response.status != HttpStatusCode.TooManyRequests) return@onResponse
            val seconds = response.headers["Retry-After"]?.trim()?.toLongOrNull()?.coerceIn(1, 24 * 3600)
                ?: DEFAULT_RETRY_SECONDS
            blockedUntilMs = maxOf(blockedUntilMs, now() + seconds * 1000)
            InAppLogger.warn("TotalEclipse/Trakt", "Trakt rate limit: waiting ${seconds}s before the next request")
        }
    }
}
