package com.nuvio.app.features.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.totaleclipse.TotalEclipseReminders
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.plus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.live_tv_button_play
import nuvio.composeapp.generated.resources.live_tv_epg_guide_title
import nuvio.composeapp.generated.resources.live_tv_epg_minutes_left
import nuvio.composeapp.generated.resources.live_tv_epg_next
import nuvio.composeapp.generated.resources.live_tv_epg_no_programs
import nuvio.composeapp.generated.resources.live_tv_epg_now
import nuvio.composeapp.generated.resources.live_tv_epg_on_now
import nuvio.composeapp.generated.resources.live_tv_epg_time_range
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun rememberLiveTvEpgNowMillis(): Long {
    var nowMillis by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = Clock.System.now().toEpochMilliseconds()
            delay(30_000L - nowMillis % 30_000L)
        }
    }
    return nowMillis
}

fun formatLiveTvEpgTime(epochMillis: Long): String {
    val dateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val minute = dateTime.minute.toString().padStart(2, '0')
    // Total Eclipse: the phone's own clock setting ("9:00 PM" in the US, as on the Apple TVs).
    if (!com.nuvio.app.totaleclipse.TotalEclipseClock.uses24Hours) {
        val hour = (dateTime.hour % 12).let { if (it == 0) 12 else it }
        return "$hour:$minute ${if (dateTime.hour < 12) "AM" else "PM"}"
    }
    return "${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
}

@Composable
fun liveTvEpgTimeRange(program: LiveTvEpgProgram): String =
    stringResource(
        Res.string.live_tv_epg_time_range,
        formatLiveTvEpgTime(program.startMillis),
        formatLiveTvEpgTime(program.endMillis),
    )

@Composable
fun LiveTvEpgProgressBar(
    progress: Float,
    trackColor: Color,
    progressColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(3.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(99.dp))
                .background(progressColor),
        )
    }
}

@Composable
internal fun LiveTvEpgNowNext(
    programs: List<LiveTvEpgProgram>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val current = programs.currentProgram(nowMillis)
    val next = programs.nextProgram(nowMillis)
    if (current == null && next == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (current != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.live_tv_epg_now),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = current.displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                current.guideTags.take(2).forEach { LiveTvGuideTagPill(it) }
            }
            LiveTvEpgProgressBar(
                progress = current.progressAt(nowMillis),
                trackColor = tokens.colors.borderSubtle,
                progressColor = tokens.colors.accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (next != null) {
            Text(
                text = "${stringResource(Res.string.live_tv_epg_next)} ${formatLiveTvEpgTime(next.startMillis)} · ${next.displayTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTvEpgGuideSheet(
    channel: LiveTvChannel,
    programs: List<LiveTvEpgProgram>,
    nowMillis: Long,
    onPlayClick: () -> Unit,
    onDismiss: () -> Unit,
    /** Total Eclipse: the programme tapped in the guide; the list opens at it. */
    focusStartMillis: Long? = null,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val upcoming = remember(programs, nowMillis) { programs.upcomingPrograms(nowMillis) }
    val dismiss: () -> Unit = {
        coroutineScope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.colors.surfaceCard),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.live_tv_epg_guide_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = onPlayClick,
                color = tokens.colors.overlaySelected,
                shape = tokens.shapes.chip,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = tokens.colors.accent,
                    )
                    Text(
                        text = stringResource(Res.string.live_tv_button_play),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        NuvioBottomSheetDivider()
        if (upcoming.isEmpty()) {
            Text(
                text = stringResource(Res.string.live_tv_epg_no_programs),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            )
        } else {
            LazyColumn(
                state = androidx.compose.foundation.lazy.rememberLazyListState(
                    initialFirstVisibleItemIndex = remember {
                        upcoming.indexOfFirst { it.startMillis == focusStartMillis }.coerceAtLeast(0)
                    },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 28.dp,
                ),
            ) {
                items(
                    items = upcoming,
                    key = { program -> "${program.startMillis}:${program.title}" },
                ) { program ->
                    LiveTvEpgProgramRow(
                        program = program,
                        nowMillis = nowMillis,
                        channelId = channel.id,
                        channelName = channel.name,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvEpgProgramRow(
    program: LiveTvEpgProgram,
    nowMillis: Long,
    channelId: String,
    channelName: String,
) {
    val tokens = MaterialTheme.nuvio
    // Total Eclipse: "Remind me" (bell) on programmes that haven't started.
    val reminders by TotalEclipseReminders.scheduled.collectAsState()
    LaunchedEffect(Unit) { TotalEclipseReminders.ensureLoaded() }
    val reminderScope = rememberCoroutineScope()
    val canRemind = program.startMillis > nowMillis
    val reminded = TotalEclipseReminders.id(channelId, program.startMillis) in reminders
    val isLive = program.isAiringAt(nowMillis)
    var expanded by rememberSaveable(program.startMillis, program.title) { mutableStateOf(false) }
    val hasDescription = !program.description.isNullOrBlank()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isLive) tokens.colors.overlaySelected else tokens.colors.surface,
        shape = tokens.shapes.compactCard,
        border = if (isLive) null else BorderStroke(NuvioTokens.Border.thin, tokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = liveTvEpgTimeRange(program),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLive) tokens.colors.accent else tokens.colors.textMuted,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (isLive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(tokens.colors.accent.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.live_tv_epg_on_now),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.accent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                program.guideTags.forEach { LiveTvGuideTagPill(it) }
                if (!program.category.isNullOrBlank()) {
                    Text(
                        text = program.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(modifier = Modifier.weight(1f))
                }
                if (canRemind) {
                    Box(contentAlignment = Alignment.CenterEnd) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (reminded) tokens.colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    reminderScope.launch {
                                        TotalEclipseReminders.toggle(channelId, channelName, program.displayTitle, program.startMillis)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (reminded) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone,
                                contentDescription = if (reminded) "Cancel reminder" else "Remind me",
                                tint = if (reminded) tokens.colors.accent else tokens.colors.textMuted,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = if (reminded) "Reminder set" else "Remind me",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (reminded) tokens.colors.accent else tokens.colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Text(
                text = program.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isLive) {
                val minutesLeft = ((program.endMillis - nowMillis) / 60_000L).coerceAtLeast(1L).toInt()
                LiveTvEpgProgressBar(
                    progress = program.progressAt(nowMillis),
                    trackColor = tokens.colors.borderSubtle,
                    progressColor = tokens.colors.accent,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.live_tv_epg_minutes_left, minutesLeft),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                )
            }
            if (hasDescription) {
                Text(
                    text = program.description.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Total Eclipse: Find Other Airings, as on the Apple TVs: the same show on other
            // channels or later times (tap the programme to see them), each with its own reminder.
            if (expanded) {
                val others = remember(program.title, program.startMillis, channelId) {
                    totalEclipseOtherAirings(program, channelId, nowMillis)
                }
                if (others.isNotEmpty()) {
                    Text(
                        text = "Other airings",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.colors.textMuted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    others.forEach { (channel, airing) ->
                        val airingReminded = TotalEclipseReminders.id(channel.id, airing.startMillis) in reminders
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = airing.startMillis > nowMillis) {
                                    reminderScope.launch {
                                        TotalEclipseReminders.toggle(channel.id, channel.name, airing.displayTitle, airing.startMillis)
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${totalEclipseAiringDay(airing.startMillis, nowMillis)} ${formatLiveTvEpgTime(airing.startMillis)} · ${channel.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (airing.startMillis > nowMillis) {
                                Icon(
                                    imageVector = if (airingReminded) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone,
                                    contentDescription = if (airingReminded) "Cancel reminder" else "Remind me",
                                    tint = if (airingReminded) tokens.colors.accent else tokens.colors.textMuted,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Total Eclipse: the same programme title elsewhere in the guide, from now on (at most eight). */
internal fun totalEclipseOtherAirings(
    program: LiveTvEpgProgram,
    channelId: String,
    nowMillis: Long,
): List<Pair<LiveTvChannel, LiveTvEpgProgram>> {
    val state = LiveTvRepository.uiState.value
    val wanted = program.displayTitle.lowercase().filter { it.isLetterOrDigit() }
    if (wanted.isEmpty()) return emptyList()
    val channels = state.channels.associateBy { it.id }
    return state.epgPrograms.asSequence()
        .flatMap { (id, list) -> list.asSequence().map { id to it } }
        .filter { (id, p) ->
            p.endMillis > nowMillis && !(id == channelId && p.startMillis == program.startMillis) &&
                p.displayTitle.lowercase().filter { it.isLetterOrDigit() } == wanted
        }
        .mapNotNull { (id, p) -> channels[id]?.let { it to p } }
        .sortedBy { it.second.startMillis }
        .take(8)
        .toList()
}

/** "Today", "Tomorrow" or the weekday ("Thu"). */
private fun totalEclipseAiringDay(startMillis: Long, nowMillis: Long): String {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val day = kotlin.time.Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date
    val today = kotlin.time.Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    return when (day) {
        today -> "Today"
        today.plus(1, kotlinx.datetime.DateTimeUnit.DAY) -> "Tomorrow"
        else -> day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    }
}
