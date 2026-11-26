package com.nuvio.app.totaleclipse

/**
 * Total Eclipse: what the film/episode error screen says, as on the Apple TVs (HomeTV
 * HomeTVPlainPlaybackError, "idiot-proofing for Mark"): one plain title, one plain next step.
 * The player's own message (HTTP codes, ffmpeg text) stays in the debug log only.
 */
internal object TotalEclipsePlainError {
    data class Text(val title: String, val message: String)

    fun text(raw: String, offline: Boolean, hasOtherSources: Boolean): Text {
        val lower = raw.lowercase()
        val nextStep = if (hasOtherSources) {
            "Choose Other Sources to try a different one."
        } else {
            "Try again in a few minutes."
        }
        return when {
            "link has expired" in lower ->
                Text("This link has expired", "Close this and press Play again to get a fresh one.")
            "trailer" in lower ->
                Text("The trailer won't play", "Close this and press Play for the title itself.")
            offline || networkWords.any { it in lower } ->
                Text(
                    "No internet connection",
                    "This device isn't connected to the internet. Check Wi-Fi or mobile data, then try again.",
                )
            compatibilityWords.any { it in lower } ->
                Text(
                    "This video can't play on this device",
                    if (hasOtherSources) "Choose Other Sources to try a different one." else "Try another title.",
                )
            sourceWords.any { it in lower } || httpCode.containsMatchIn(lower) ->
                Text("This source isn't working right now", nextStep)
            else ->
                Text("Something went wrong playing this", nextStep)
        }
    }

    private val networkWords = listOf(
        "-1009", "not connected to the internet", "network is unreachable", "network connection was lost",
        "no internet",
    )
    private val compatibilityWords = listOf("unsupported", "not supported", "no decoder", "codec", "no video stream")
    private val sourceWords = listOf(
        "failed to open", "loading failed", "could not open", "forbidden", "not found", "timed out", "timeout",
        "could not resolve", "connection refused", "unreachable", "invalid data", "server", "http",
    )
    private val httpCode = Regex("""\b[45]\d\d\b""")
}
