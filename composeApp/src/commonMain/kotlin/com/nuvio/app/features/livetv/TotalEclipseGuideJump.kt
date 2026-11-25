package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Total Eclipse: the Apple TVs' guide "Jump To…" (HomeTV IPTVLiveView): 2 Hours Later (from the
 * time the guide shows), Tonight 8 PM (11 PM after 8), Tomorrow Morning 8 AM, Tomorrow Evening
 * 8 PM, and Now once the guide has moved. Choices past the end of the guide data are left out.
 */
internal data class LiveTvGuideJump(val label: String, val atMillis: Long?)

internal fun liveTvGuideJumps(nowMillis: Long, shownMillis: Long, guideEndMillis: Long): List<LiveTvGuideJump> {
    val zone = TimeZone.currentSystemDefault()
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
    fun at(daysAhead: Int, hour: Int): Long =
        today.date.plus(daysAhead, DateTimeUnit.DAY).atTime(LocalTime(hour, 0)).toInstant(zone).toEpochMilliseconds()
    val later = ((shownMillis + 2 * 3_600_000L) / LIVE_TV_GUIDE_SLOT_MILLIS) * LIVE_TV_GUIDE_SLOT_MILLIS
    val tonight = if (today.hour >= 20) at(0, 23) else at(0, 20)
    val choices = buildList {
        if (shownMillis != nowMillis) add(LiveTvGuideJump("Now", null))
        add(LiveTvGuideJump("2 Hours Later · ${formatLiveTvEpgTime(later)}", later))
        if (tonight > nowMillis) add(LiveTvGuideJump("Tonight · ${formatLiveTvEpgTime(tonight)}", tonight))
        add(LiveTvGuideJump("Tomorrow Morning · ${formatLiveTvEpgTime(at(1, 8))}", at(1, 8)))
        add(LiveTvGuideJump("Tomorrow Evening · ${formatLiveTvEpgTime(at(1, 20))}", at(1, 20)))
    }
    return choices.filter { it.atMillis == null || it.atMillis < guideEndMillis }
}

/** "Now" / "Tonight" / "Tomorrow": what the guide is showing, for the button. */
internal fun liveTvGuideShownLabel(nowMillis: Long, shownMillis: Long): String {
    if (shownMillis == nowMillis) return "Now"
    val zone = TimeZone.currentSystemDefault()
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val shown = Instant.fromEpochMilliseconds(shownMillis).toLocalDateTime(zone)
    return when {
        shown.date == today && shown.hour >= 17 -> "Tonight"
        shown.date == today -> formatLiveTvEpgTime(shownMillis)
        shown.date == today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        else -> formatLiveTvEpgTime(shownMillis)
    }
}

@Composable
internal fun LiveTvGuideJumpButton(
    nowMillis: Long,
    shownMillis: Long,
    guideEndMillis: Long,
    onJump: (Long?) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            color = if (shownMillis != nowMillis) tokens.colors.overlaySelected else tokens.colors.surface,
            shape = RoundedCornerShape(99.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = "Jump To",
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(14.dp).padding(end = 2.dp),
                )
                Text(
                    text = liveTvGuideShownLabel(nowMillis, shownMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                text = "Jump To",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            liveTvGuideJumps(nowMillis, shownMillis, guideEndMillis).forEach { jump ->
                DropdownMenuItem(
                    text = { Text(jump.label) },
                    onClick = {
                        open = false
                        onJump(jump.atMillis)
                    },
                )
            }
        }
    }
}
