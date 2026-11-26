package com.nuvio.app.totaleclipse

import com.nuvio.app.features.livetv.displayTitle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import kotlinx.datetime.toLocalDateTime

/**
 * Total Eclipse: Live TV channels in Search, as on the Apple TVs (a Live row above the
 * movie and show results). Matches the channel's name or category, ignoring case, spacing and
 * punctuation ("wplg" finds "ABC 10 WPLG", "miami" finds the Miami locals). Tapping plays it.
 */
@Composable
internal fun TotalEclipseLiveSearchRow(
    query: String,
    onChannelClick: (LiveTvChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { LiveTvRepository.ensureChannelsLoaded() }
    val state by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    // The guide too, for searching by show (as on the TVs): "voyager" finds where it's on.
    LaunchedEffect(state.channels.isNotEmpty()) {
        if (state.channels.isNotEmpty() && state.epgPrograms.isEmpty() && !state.isEpgLoading) {
            LiveTvRepository.refreshEpg(force = false)
        }
    }
    val needle = remember(query) { normalize(query) }
    val matches = remember(state.channels, state.epgPrograms, needle) {
        liveSearchMatches(state.channels, state.epgPrograms, needle)
    }
    if (matches.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Live TV",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(matches, key = { it.first.id }) { (channel, onNow) ->
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onChannelClick(channel) },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(74.dp)
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!channel.logoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = channel.logoUrl,
                                contentDescription = channel.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.padding(10.dp),
                            )
                        } else {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    )
                    if (onNow != null) {
                        Text(
                            text = onNow,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Channels whose name or category matches, then channels with a matching programme on now or in
 * the next 24 hours (with "Now · Title" or "9:00 PM · Title"). At most 30.
 */
private fun liveSearchMatches(
    channels: List<LiveTvChannel>,
    programs: Map<String, List<com.nuvio.app.features.livetv.LiveTvEpgProgram>>,
    needle: String,
): List<Pair<LiveTvChannel, String?>> {
    if (needle.length < 2) return emptyList()
    val byName = channels.asSequence()
        .filter { normalize(it.name).contains(needle) || normalize(it.group.orEmpty()).contains(needle) }
        .sortedBy { if (normalize(it.name).startsWith(needle)) 0 else 1 }
        .take(30)
        .map { it to null as String? }
        .toList()
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val until = now + 24L * 60 * 60 * 1000
    val taken = byName.map { it.first.id }.toSet()
    val byShow = channels.asSequence()
        .filter { it.id !in taken }
        .mapNotNull { channel ->
            programs[channel.id].orEmpty()
                .firstOrNull { it.endMillis > now && it.startMillis < until && normalize(it.title).contains(needle) }
                ?.let { program -> Triple(channel, program, program.startMillis) }
        }
        .sortedBy { it.third }
        .take((30 - byName.size).coerceAtLeast(0))
        .map { (channel, program) -> channel to "${whenLabel(program.startMillis, now)} · ${program.displayTitle}" }
        .toList()
    return byName + byShow
}

private fun whenLabel(startMillis: Long, now: Long): String {
    if (startMillis <= now) return "Now"
    val time = kotlin.time.Instant.fromEpochMilliseconds(startMillis)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    val hour12 = if (time.hour % 12 == 0) 12 else time.hour % 12
    val minute = time.minute.toString().padStart(2, '0')
    return "$hour12:$minute ${if (time.hour < 12) "AM" else "PM"}"
}

/** Whether the Live TV row has anything for [query] (the screen then skips "no results / failed"). */
internal fun totalEclipseLiveSearchHasMatches(query: String): Boolean {
    val state = LiveTvRepository.uiState.value
    return liveSearchMatches(state.channels, state.epgPrograms, normalize(query)).isNotEmpty()
}

private fun normalize(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }

/** Total Eclipse: the Live tab's search, by the same rules as Global Search. */
internal object TotalEclipseLiveTabSearch {
    fun needle(query: String): String = normalize(query.trim())

    /** 0 = the channel's name, category or playlist matches; 1 = a show on now or in the next 24 h; null = no match. */
    fun matchRank(
        channel: LiveTvChannel,
        category: String,
        programs: List<com.nuvio.app.features.livetv.LiveTvEpgProgram>?,
        needle: String,
        nowMillis: Long,
    ): Int? {
        if (needle.isEmpty()) return 0
        if (normalize(channel.name).contains(needle) || normalize(category).contains(needle) ||
            normalize(channel.playlistName.orEmpty()).contains(needle)
        ) return 0
        if (needle.length < 2) return null
        val until = nowMillis + 24L * 60 * 60 * 1000
        return if (programs.orEmpty().any { it.endMillis > nowMillis && it.startMillis < until && normalize(it.title).contains(needle) }) 1 else null
    }
}
