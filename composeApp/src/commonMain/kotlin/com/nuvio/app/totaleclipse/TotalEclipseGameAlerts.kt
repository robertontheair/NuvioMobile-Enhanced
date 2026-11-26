package com.nuvio.app.totaleclipse

import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvSportsEvent
import com.nuvio.app.features.livetv.LiveTvSportsState
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Total Eclipse: a notification five minutes before a game with a team you follow starts (it
 * opens the channel showing it, when one is in your lineup). iOS doesn't let apps check scores in
 * the background, so there are no score or final-result alerts; start times are known ahead.
 * Scheduled whenever the sports list refreshes, for the next three days; on unless turned off.
 */
internal object TotalEclipseGameAlerts {
    private const val ENABLED = "totaleclipse.sports.gameAlerts."
    private const val SCHEDULED = "totaleclipse.sports.gameAlertIds"
    private const val LEAD_MS = 5 * 60 * 1000L
    private const val WINDOW_MS = 3 * 24 * 60 * 60 * 1000L

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun load() {
        _enabled.value = HouseholdCloudStore.loadLocal(ENABLED + ProfileRepository.activeProfileId) != "off"
    }

    fun setEnabled(on: Boolean) {
        HouseholdCloudStore.saveLocal(ENABLED + ProfileRepository.activeProfileId, if (on) "on" else "off")
        _enabled.value = on
    }

    suspend fun sync(
        events: List<LiveTvSportsEvent>,
        followed: Set<String>,
        channelsFor: (LiveTvSportsEvent) -> List<LiveTvChannel>,
    ) {
        load()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val wanted = if (!_enabled.value || followed.isEmpty()) emptyList() else events.filter { e ->
            e.state == LiveTvSportsState.Upcoming && e.startMillis in (now + 60_000)..(now + WINDOW_MS) &&
                TotalEclipseSportsPrefs.involvesFollowed(e, followed)
        }
        val wantedIds = wanted.associateBy { "te-game:${it.league.id}:${it.id}:${it.startMillis}" }
        val previous = HouseholdCloudStore.loadLocal(SCHEDULED)?.split('\n')?.filter(String::isNotBlank)?.toSet().orEmpty()
        (previous - wantedIds.keys).forEach(TotalEclipseReminderPlatform::cancel)
        val kept = (previous intersect wantedIds.keys).toMutableSet()
        for ((id, event) in wantedIds) {
            if (id in kept) continue
            val channel = channelsFor(event).firstOrNull()
            val names = event.competitors.joinToString(" vs ") { it.shortName.ifBlank { it.name } }.ifBlank { event.name }
            val ok = TotalEclipseReminderPlatform.schedule(
                id = id,
                title = "$names starts soon",
                body = channel?.let { "${event.league.label} · on ${it.name}" } ?: event.league.label,
                atMillis = maxOf(event.startMillis - LEAD_MS, now + 5_000),
                link = channel?.let { com.nuvio.app.core.deeplink.buildLiveChannelDeepLinkUrl(it.id) } ?: "",
            )
            if (ok) kept += id
        }
        HouseholdCloudStore.saveLocal(SCHEDULED, kept.sorted().joinToString("\n"))
    }
}
