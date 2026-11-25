package com.nuvio.app.totaleclipse

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Total Eclipse: tells a dead Live TV channel from other playback errors, as on the Apple TVs.
 * The provider answers a channel that's down with a web page (or an HTTP error) instead of
 * video; the player then fails with a technical message. Checked only after a failure, so
 * channels that play start no slower.
 */
internal object TotalEclipseLiveProbe {
    /** True when the link answers with a web page or an HTTP error; false when it looks like video or can't be told. */
    suspend fun looksDead(url: String, headers: Map<String, String>): Boolean {
        val response = withTimeoutOrNull(8_000) {
            runCatching {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = headers + ("Range" to "bytes=0-2047"),
                    body = "",
                    maxResponseBodyBytes = 4096,
                )
            }.getOrNull()
        } ?: return false
        val type = response.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
        val start = response.body.trimStart().take(64).lowercase()
        val dead = response.status >= 400 ||
            (response.status in 200..299 && response.bodyBytes.isEmpty()) ||
            type.contains("html", ignoreCase = true) ||
            start.startsWith("<!doctype") || start.startsWith("<html")
        InAppLogger.info("TotalEclipse/Live", "probe after error: HTTP ${response.status}, ${type.substringBefore(';').ifBlank { "no type" }}, dead=$dead")
        return dead
    }

    /**
     * What to show for a live failure. Some dead feeds open and send nothing (ABC 10 WPLG): the
     * player then says the stream ended at 0. Never the player's own technical text.
     */
    suspend fun explain(channelName: String, playerError: String, url: String, headers: Map<String, String>): String =
        if (playerError.contains("ends prematurely at 0", ignoreCase = true) || looksDead(url, headers)) message(channelName)
        else "$channelName couldn't be played right now. Try again, or pick another channel."

    fun message(channelName: String): String =
        "$channelName isn't broadcasting right now. The provider's feed for this channel is down. " +
            "Try again in a few minutes, or pick another channel."
}
