package com.nuvio.app.features.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TotalEclipseProgrammeFactsTest {
    @Test
    fun episodeNumbers() {
        assertEquals("S2 E5", TotalEclipseProgrammeKind.normalizedEpisode("S02E05"))
        assertEquals("S2 E5", TotalEclipseProgrammeKind.normalizedEpisode("s2 e5"))
        assertEquals("E148", TotalEclipseProgrammeKind.normalizedEpisode("E148"))
        assertNull(TotalEclipseProgrammeKind.normalizedEpisode("1.4.0/1"))
        assertNull(TotalEclipseProgrammeKind.normalizedEpisode("S2E5x"))
    }

    @Test
    fun episodeFromDescription() {
        assertEquals("S3 E7" to "The Long Night", TotalEclipseProgrammeKind.episodeFromDescription("S03 E07 The Long Night\nMore text"))
        assertNull(TotalEclipseProgrammeKind.episodeFromDescription("Season finale"))
    }

    @Test
    fun facts() {
        val f = TotalEclipseProgrammeKind.facts("S01E02", "Pilot, Part 2", null, "TV14", null)
        assertEquals("S1 E2", f.episode)
        assertEquals("Pilot, Part 2", f.title)
        assertEquals("TV-14", f.rating)
        val guests = TotalEclipseProgrammeKind.facts(null, "A; B; C", null, null, "1994")
        assertNull(guests.title)
        assertEquals(1994, guests.year)
        val fromDesc = TotalEclipseProgrammeKind.facts(null, null, "S02 E03 Homecoming", null, null)
        assertEquals("S2 E3", fromDesc.episode)
        assertEquals("Homecoming", fromDesc.title)
    }
}
