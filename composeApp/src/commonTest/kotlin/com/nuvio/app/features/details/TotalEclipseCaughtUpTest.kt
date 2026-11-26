package com.nuvio.app.features.details

import com.nuvio.app.features.livetv.totalEclipseTimeLeft
import com.nuvio.app.features.watched.watchedItemKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotalEclipseCaughtUpTest {
    private val today = "2026-03-30"
    private val show = MetaDetails(
        id = "show",
        type = "series",
        name = "Show",
        videos = listOf(
            MetaVideo(id = "e11", title = "One", season = 1, episode = 1, released = "2026-03-01"),
            MetaVideo(id = "e12", title = "Two", season = 1, episode = 2, released = "2026-03-08"),
            MetaVideo(id = "e13", title = "Three", season = 1, episode = 3, released = "2026-03-15"),
            MetaVideo(id = "e21", title = "Next season", season = 2, episode = 1, released = "2099-12-18"),
        ),
    )

    private fun watched(vararg episodes: Pair<Int, Int>): Set<String> =
        episodes.flatMapTo(mutableSetOf()) { (s, e) -> watchedItemKeys("series", "show", s, e) }

    @Test
    fun caught_up_waits_for_the_next_episode() {
        val play = show.totalEclipseCaughtUpPlay(watched(1 to 1, 1 to 2, 1 to 3), today)!!
        assertFalse(play.playable)
        assertEquals(2, play.action.seasonNumber)
        assertEquals("Airs Dec 18, 2099", play.action.label)
    }

    @Test
    fun skipped_episode_comes_before_upcoming() {
        val play = show.totalEclipseCaughtUpPlay(watched(1 to 1, 1 to 3), today)!!
        assertTrue(play.playable)
        assertEquals(1, play.action.seasonNumber)
        assertEquals(2, play.action.episodeNumber)
    }

    @Test
    fun usual_rules_when_next_is_out_or_nothing_watched() {
        assertNull(show.totalEclipseCaughtUpPlay(watched(1 to 1), today))
        assertNull(show.totalEclipseCaughtUpPlay(emptySet(), today))
    }

    @Test
    fun finished_show_falls_back_to_rewatch() {
        val ended = show.copy(videos = show.videos.dropLast(1))
        assertNull(ended.totalEclipseCaughtUpPlay(watched(1 to 1, 1 to 2, 1 to 3), today))
    }

    @Test
    fun time_left_reads_like_the_tv() {
        val minute = 60_000L
        assertEquals("1m left", totalEclipseTimeLeft(10_000L, 0L))
        assertEquals("42m left", totalEclipseTimeLeft(42 * minute, 0L))
        assertEquals("1h 5m left", totalEclipseTimeLeft(65 * minute, 0L))
        assertEquals("2h left", totalEclipseTimeLeft(120 * minute, 0L))
    }
}
