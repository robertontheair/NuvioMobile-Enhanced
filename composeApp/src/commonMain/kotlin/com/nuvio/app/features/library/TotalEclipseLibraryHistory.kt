package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Total Eclipse: one History row — a title and the latest thing watched from it. */
internal data class TotalEclipseHistoryEntry(
    val item: WatchedItem,
    val poster: String?,
    val episodesWatched: Int,
)

private const val HISTORY_LIMIT = 200

/**
 * What you've watched, newest first, one row per title (as the TVs' History): a show shows its
 * latest episode and how many of its episodes you've seen.
 */
internal fun totalEclipseHistory(items: List<WatchedItem>): List<TotalEclipseHistoryEntry> =
    items.groupBy { it.type.lowercase() + ":" + it.id }
        .values
        .map { group ->
            val latest = group.maxBy { it.markedAtEpochMs }
            TotalEclipseHistoryEntry(
                item = latest,
                poster = latest.poster ?: group.firstNotNullOfOrNull { it.poster },
                episodesWatched = group.count { it.season != null && it.episode != null },
            )
        }
        .sortedByDescending { it.item.markedAtEpochMs }
        .take(HISTORY_LIMIT)

internal fun totalEclipseHistoryDay(epochMs: Long, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val day = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    return when {
        day == today -> "Today"
        day == today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        day > today.minus(7, DateTimeUnit.DAY) -> day.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)
        day.year == today.year -> "${monthShort(day)} ${day.day}"
        else -> "${monthShort(day)} ${day.day}, ${day.year}"
    }
}

private fun monthShort(day: LocalDate): String =
    day.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)

internal fun LazyListScope.totalEclipseHistoryContent(
    items: List<WatchedItem>,
    onOpen: (LibraryItem) -> Unit,
) {
    val entries = totalEclipseHistory(items)
    if (entries.isEmpty()) {
        item(key = "te-history-empty") {
            Text(
                text = "Nothing watched yet. What you finish shows up here, newest first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
            )
        }
        return
    }
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    items(entries, key = { "te-history:" + it.item.type + ":" + it.item.id }) { entry ->
        val item = entry.item
        val detail = buildList {
            if (item.season != null && item.episode != null) add("S${item.season} E${item.episode}")
            if (entry.episodesWatched > 1) add("${entry.episodesWatched} episodes")
            add(totalEclipseHistoryDay(item.markedAtEpochMs, now))
        }.joinToString(" · ")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onOpen(LibraryItem(id = item.id, type = item.type, name = item.name, poster = entry.poster, releaseInfo = item.releaseInfo, savedAtEpochMs = item.markedAtEpochMs))
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (entry.poster != null) {
                    AsyncImage(
                        model = entry.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
