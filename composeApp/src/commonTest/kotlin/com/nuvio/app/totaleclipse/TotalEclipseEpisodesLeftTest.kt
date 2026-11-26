package com.nuvio.app.totaleclipse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TotalEclipseEpisodesLeftTest {
    private fun ep(s: Int, e: Int, r: String?) = TotalEclipseEpisodeGuideCache.Episode(s, e, r)
    private val guide = listOf(
        ep(1, 1, "2026-01-01"), ep(1, 2, "2026-01-08"), ep(1, 3, "2026-01-15"),
        ep(2, 1, "2026-03-01"), ep(2, 2, "2099-01-01"),
    )
    private val today = "2026-03-30"

    @Test
    fun counts_aired_episodes_left() {
        assertEquals(2, totalEclipseEpisodesLeft(guide, today) { s, e -> s == 1 && e <= 2 })
    }

    @Test
    fun all_aired_watched_is_zero_and_unaired_dont_count() {
        assertEquals(0, totalEclipseEpisodesLeft(guide, today) { s, _ -> s == 1 || s == 2 })
    }

    @Test
    fun nothing_watched_is_null() {
        assertNull(totalEclipseEpisodesLeft(guide, today) { _, _ -> false })
    }
}
