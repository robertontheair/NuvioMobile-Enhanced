package com.nuvio.app.features.library

import com.nuvio.app.features.watched.WatchedItem
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TotalEclipseLibraryHistoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_764_000_000_000L // Monday 2025-11-24, 16:00 UTC

    @Test
    fun groupsByTitleNewestFirst() {
        val items = listOf(
            WatchedItem(id = "tt1", type = "movie", name = "Film", markedAtEpochMs = now - 3 * day),
            WatchedItem(id = "tt2", type = "series", name = "Show", season = 1, episode = 1, poster = "p", markedAtEpochMs = now - 5 * day),
            WatchedItem(id = "tt2", type = "series", name = "Show", season = 1, episode = 2, markedAtEpochMs = now - day),
        )
        val history = totalEclipseHistory(items)
        assertEquals(listOf("tt2", "tt1"), history.map { it.item.id })
        assertEquals(2, history[0].item.episode)
        assertEquals("p", history[0].poster)
        assertEquals(2, history[0].episodesWatched)
    }

    @Test
    fun dayLabels() {
        val utc = TimeZone.UTC
        assertEquals("Today", totalEclipseHistoryDay(now, now, utc))
        assertEquals("Yesterday", totalEclipseHistoryDay(now - day, now, utc))
        assertEquals("Thursday", totalEclipseHistoryDay(now - 4 * day, now, utc))
        assertEquals("Oct 31", totalEclipseHistoryDay(now - 24 * day, now, utc))
        assertEquals("Nov 24, 2024", totalEclipseHistoryDay(now - 365 * day, now, utc))
    }
}
