package com.nuvio.app.totaleclipse

import com.nuvio.app.features.livetv.LiveTvSportsEvent
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Total Eclipse: the teams this profile follows and its "Hide Scores" choice, kept on the phone.
 * A team is kept as "league:Team Name" (ESPN's display name), so the same name in two leagues
 * stays apart.
 */
internal object TotalEclipseSportsPrefs {
    private const val TEAMS = "totaleclipse.sports.teams."
    private const val HIDE_SCORES = "totaleclipse.sports.hideScores."

    private val _teams = MutableStateFlow<Set<String>>(emptySet())
    val teams: StateFlow<Set<String>> = _teams.asStateFlow()

    private val _hideScores = MutableStateFlow(false)
    val hideScores: StateFlow<Boolean> = _hideScores.asStateFlow()

    private var loadedFor: Int? = null

    fun load() {
        val profile = ProfileRepository.activeProfileId
        if (loadedFor == profile) return
        loadedFor = profile
        _teams.value = HouseholdCloudStore.loadLocal(TEAMS + profile)
            ?.split('\n')?.filter(String::isNotBlank)?.toSet().orEmpty()
        _hideScores.value = HouseholdCloudStore.loadLocal(HIDE_SCORES + profile) == "yes"
    }

    fun key(leagueId: String, teamName: String) = "$leagueId:$teamName"

    fun toggleTeam(leagueId: String, teamName: String) {
        load()
        val k = key(leagueId, teamName)
        val next = if (k in _teams.value) _teams.value - k else _teams.value + k
        _teams.value = next
        HouseholdCloudStore.saveLocal(TEAMS + ProfileRepository.activeProfileId, next.sorted().joinToString("\n"))
    }

    fun setHideScores(hide: Boolean) {
        load()
        _hideScores.value = hide
        HouseholdCloudStore.saveLocal(HIDE_SCORES + ProfileRepository.activeProfileId, if (hide) "yes" else "no")
    }

    fun involvesFollowed(event: LiveTvSportsEvent, teams: Set<String> = _teams.value): Boolean =
        teams.isNotEmpty() && event.competitors.any { key(event.league.id, it.name) in teams }

    /**
     * Whether a programme title ("Dolphins at Jets", "Coming up: Lakers at Suns") names a followed
     * team: the team's last word ("Miami Dolphins" → "dolphins"), as event slots use short names.
     */
    fun titleMentionsFollowed(title: String, teams: Set<String> = _teams.value): Boolean {
        if (teams.isEmpty()) return false
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return teams.any { team ->
            val nickname = team.substringAfter(':').trim().split(' ').lastOrNull()?.lowercase()
            !nickname.isNullOrBlank() && nickname.length > 2 && nickname in words
        }
    }
}
