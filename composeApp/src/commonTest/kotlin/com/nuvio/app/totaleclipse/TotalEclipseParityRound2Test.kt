package com.nuvio.app.totaleclipse

import kotlin.test.Test
import kotlin.test.assertEquals

class TotalEclipseParityRound2Test {
    private val entries = listOf(
        TotalEclipseWatchlistSort.Entry(rank = 2, listedAt = "2026-01-02T00:00:00Z", title = "Bravo", year = 2020, rating = 7.0),
        TotalEclipseWatchlistSort.Entry(rank = 1, listedAt = "2026-03-01T00:00:00Z", title = "alpha", year = 2024, rating = null),
        TotalEclipseWatchlistSort.Entry(rank = 3, listedAt = null, title = "Charlie", year = null, rating = 8.5),
    )

    @Test
    fun watchlist_follows_trakt_sort_headers() {
        assertEquals(listOf(1, 0, 2), TotalEclipseWatchlistSort.order(entries, "rank", "asc"))
        assertEquals(listOf(1, 0, 2), TotalEclipseWatchlistSort.order(entries, "added", "desc"))
        assertEquals(listOf(1, 0, 2), TotalEclipseWatchlistSort.order(entries, "title", "asc"))
        assertEquals(listOf(2, 0, 1), TotalEclipseWatchlistSort.order(entries, "title", "desc"))
        assertEquals(listOf(2, 0, 1), TotalEclipseWatchlistSort.order(entries, "rating", "desc"))
        assertEquals(listOf(0, 1, 2), TotalEclipseWatchlistSort.order(entries, "popularity", "desc"))
        assertEquals(listOf(1, 0, 2), TotalEclipseWatchlistSort.order(entries, null, null))
    }

    @Test
    fun plain_error_wording() {
        fun title(raw: String, offline: Boolean = false) =
            TotalEclipsePlainError.text(raw, offline, hasOtherSources = true).title
        assertEquals("This source isn't working right now", title("HTTP error 403 Forbidden"))
        assertEquals("This source isn't working right now", title("Failed to open https://x/y.mkv"))
        assertEquals("No internet connection", title("anything", offline = true))
        assertEquals("This video can't play on this device", title("Unsupported codec hevc"))
        assertEquals("This link has expired", title("The link has expired"))
        assertEquals("Something went wrong playing this", title("mpv: ao init failed"))
    }
}
