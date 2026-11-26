package com.nuvio.app.totaleclipse

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvEpgProgram
import com.nuvio.app.features.livetv.liveSportsNow
import kotlin.test.Test
import kotlin.test.assertEquals

class TotalEclipseParityRound3Test {
    private val hour = 60L * 60 * 1000

    private fun slot(id: String) = LiveTvChannel(id = id, name = "Slot $id", streamUrl = "http://x/$id", group = TotalEclipseLiveGroups.LIVE_EVENTS)
    private fun prog(title: String, start: Long, end: Long) = LiveTvEpgProgram(title = title, startMillis = start, endMillis = end)

    @Test
    fun live_sports_now_orders_live_then_soon_and_drops_empty_slots() {
        val now = 10 * hour
        val channels = listOf(slot("a"), slot("b"), slot("c"), slot("d"), slot("e"), slot("f"))
        val guide = mapOf(
            "a" to listOf(prog("No event scheduled", 0, 20 * hour)),
            "b" to listOf(prog("Dolphins at Jets", now - 2 * hour, now + hour)),
            "c" to listOf(prog("Coming up: Lakers at Suns", now - hour, now + hour)),
            "d" to listOf(prog("Coming up: Late game", now - hour, now + 5 * hour)),
            "e" to listOf(prog("Bruins at Rangers", now - hour, now + 2 * hour)),
            "f" to listOf(prog("Dolphins at Jets", now - hour, now + hour)),
        )
        val ids = liveSportsNow(channels, guide, now).map { it.id }
        assertEquals(listOf("b", "e", "c"), ids)
    }

    @Test
    fun search_rows_merge_by_id_and_by_title_year() {
        val a = MetaPreview(id = "tt0903747", type = "series", name = "Breaking Bad", releaseInfo = "2008")
        val b = MetaPreview(id = "tmdb:1396", type = "series", name = "Breaking Bad", releaseInfo = "2008–2013")
        val c = MetaPreview(id = "imdb:tt0903747", type = "tv", name = "Breaking Bad")
        val d = MetaPreview(id = "tt1234567", type = "movie", name = "Breaking Bad", releaseInfo = "2008")
        val e = MetaPreview(id = "tt7654321", type = "series", name = "Breaking Bad", releaseInfo = "2008")
        val merged = TotalEclipseSearchMerge.dedupe(listOf(a, b, c, d, e))
        // b matches a by title + year (no conflicting id); c by id; d is a movie; e has its own IMDb id.
        assertEquals(listOf(a, d, e), merged)
    }
}
