package com.nuvio.app.features.details

import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.domain.upNextLabel
import com.nuvio.app.totaleclipse.TotalEclipsePosterBadges

/**
 * Total Eclipse: the Apple TVs' Play button for a show you're caught up on. When the episode
 * after the furthest one you've watched hasn't aired, an earlier episode you skipped that has
 * aired comes first; otherwise the button says "Airs Dec 18" (or "Upcoming S3 E1" with no
 * date) and can't be pressed. Null means the usual rules apply (nothing watched yet, the next
 * episode is out, or the show has nothing left to air, where a rewatch is offered).
 */
internal data class CaughtUpPlay(val action: SeriesPrimaryAction, val playable: Boolean)

internal fun MetaDetails.totalEclipseCaughtUpPlay(
    watchedKeys: Set<String>,
    todayIsoDate: String,
): CaughtUpPlay? {
    val regular = videos
        .filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
        .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
    if (regular.isEmpty()) return null
    val isWatched = { video: MetaVideo -> WatchingState.isEpisodeWatched(watchedKeys, type, id, video) }
    val furthestIndex = regular.indexOfLast(isWatched)
    if (furthestIndex < 0) return null
    val next = regular.drop(furthestIndex + 1).firstOrNull { !isWatched(it) } ?: return null
    if (next.isReleasedBy(todayIsoDate)) return null

    val gap = regular.firstOrNull { !isWatched(it) && it.isReleasedBy(todayIsoDate) }
    if (gap != null) return CaughtUpPlay(gap.toAction(upNextLabel(gap.season, gap.episode)), playable = true)

    val label = TotalEclipsePosterBadges.comingDay(next.released)?.let { "Airs $it" }
        ?: "Upcoming S${next.season} E${next.episode}"
    return CaughtUpPlay(next.toAction(label), playable = false)
}

private fun MetaVideo.toAction(label: String): SeriesPrimaryAction = SeriesPrimaryAction(
    label = label,
    videoId = id,
    seasonNumber = season,
    episodeNumber = episode,
    episodeTitle = title,
    episodeThumbnail = thumbnail,
    resumePositionMs = null,
)
