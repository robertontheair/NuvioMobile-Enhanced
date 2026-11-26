package com.nuvio.app.totaleclipse

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TotalEclipseSportsPrefsTest {
    private val teams = setOf("nfl:Miami Dolphins", "nba:Miami Heat")

    @Test
    fun slot_titles_match_by_nickname() {
        assertTrue(TotalEclipseSportsPrefs.titleMentionsFollowed("Dolphins at Jets", teams))
        assertTrue(TotalEclipseSportsPrefs.titleMentionsFollowed("Coming up: Celtics vs. Heat", teams))
        assertFalse(TotalEclipseSportsPrefs.titleMentionsFollowed("Bills at Patriots", teams))
        assertFalse(TotalEclipseSportsPrefs.titleMentionsFollowed("Heatwave special", teams))
        assertFalse(TotalEclipseSportsPrefs.titleMentionsFollowed("Dolphins at Jets", emptySet()))
    }
}
