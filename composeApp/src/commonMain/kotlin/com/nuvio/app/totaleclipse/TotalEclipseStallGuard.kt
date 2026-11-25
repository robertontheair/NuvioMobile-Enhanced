package com.nuvio.app.totaleclipse

/**
 * Total Eclipse: notices a source that has become too slow part way through (2026-11-24: a TorBox
 * server fell from 30 to 7 Mbps and nothing reacted for 8 minutes). Buffering time is added up
 * over the last two minutes; the first seconds after a seek or a start don't count.
 */
internal class TotalEclipseStallGuard {
    private data class Tick(val atMs: Long, val buffering: Boolean)
    private val ticks = ArrayDeque<Tick>()
    private var ignoreUntilMs = 0L

    /** A start, a seek or a new source: buffering is expected for a while. */
    fun expectBuffering(nowMs: Long) {
        ticks.clear()
        ignoreUntilMs = nowMs + SETTLE_MS
    }

    /** Call once a second while playing is wanted; true when the source is too slow. */
    fun tick(nowMs: Long, buffering: Boolean): Boolean {
        if (nowMs < ignoreUntilMs) return false
        ticks.addLast(Tick(nowMs, buffering))
        while (ticks.isNotEmpty() && nowMs - ticks.first().atMs > WINDOW_MS) ticks.removeFirst()
        return ticks.count { it.buffering } >= LIMIT_SECONDS
    }

    companion object {
        const val SETTLE_MS = 15_000L
        const val WINDOW_MS = 120_000L
        const val LIMIT_SECONDS = 25
    }
}
