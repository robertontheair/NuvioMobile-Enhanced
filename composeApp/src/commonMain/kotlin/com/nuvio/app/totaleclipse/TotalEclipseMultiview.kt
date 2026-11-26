package com.nuvio.app.totaleclipse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Total Eclipse: Multiview (iPad first) — up to four live channels at once. The sound follows the
 * pane you tap; a pane that drops reconnects by itself. Each pane is a stream from your provider.
 */
internal object TotalEclipseMultiview {
    const val MAX_PANES = 4

    private val _channels = MutableStateFlow<List<LiveTvChannel>>(emptyList())
    val channels: StateFlow<List<LiveTvChannel>> = _channels.asStateFlow()

    private val _soundIndex = MutableStateFlow(0)
    val soundIndex: StateFlow<Int> = _soundIndex.asStateFlow()

    /** A channel picked from Multiview to watch on its own. */
    private val _openFull = MutableStateFlow<LiveTvChannel?>(null)
    val openFull: StateFlow<LiveTvChannel?> = _openFull.asStateFlow()

    fun open(start: List<LiveTvChannel>) {
        _channels.value = start.distinctBy { it.id }.take(MAX_PANES)
        _soundIndex.value = 0
    }

    fun add(channel: LiveTvChannel) {
        val list = _channels.value
        if (list.any { it.id == channel.id } || list.size >= MAX_PANES) return
        _channels.value = list + channel
    }

    fun remove(index: Int) {
        val list = _channels.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _channels.value = list
        val sound = _soundIndex.value
        _soundIndex.value = when {
            list.isEmpty() -> 0
            index < sound -> sound - 1
            sound >= list.size -> list.size - 1
            else -> sound
        }
    }

    fun setSound(index: Int) { _soundIndex.value = index }

    fun close() { _channels.value = emptyList() }

    fun watchFull(index: Int) {
        val channel = _channels.value.getOrNull(index) ?: return
        close()
        _openFull.value = channel
    }

    fun consumeOpenFull(): LiveTvChannel? = _openFull.value.also { _openFull.value = null }
}

/** One live channel, playing (muted unless it has the sound). */
@Composable
internal expect fun TotalEclipseMultiviewPane(
    channel: LiveTvChannel,
    muted: Boolean,
    slot: Int,
    count: Int,
    modifier: Modifier,
)

/** Full-screen Multiview over the app while it has channels. */
@Composable
internal fun TotalEclipseMultiviewHost() {
    val channels by TotalEclipseMultiview.channels.collectAsStateWithLifecycle()
    if (channels.isEmpty()) return
    val sound by TotalEclipseMultiview.soundIndex.collectAsStateWithLifecycle()
    // Started with one channel: offer the next one straight away.
    var picking by remember { mutableStateOf(channels.size == 1) }
    var chromeVisible by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    LaunchedEffect(activity, chromeVisible, picking) {
        if (chromeVisible && !picking) {
            delay(4_000)
            chromeVisible = false
        }
    }
    Dialog(
        onDismissRequest = { TotalEclipseMultiview.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            MultiviewGrid(
                channels = channels,
                sound = sound,
                chromeVisible = chromeVisible,
                onPaneTap = { index ->
                    TotalEclipseMultiview.setSound(index)
                    chromeVisible = true
                    activity++
                },
            )
            AnimatedVisibility(
                visible = chromeVisible || picking,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (channels.size < TotalEclipseMultiview.MAX_PANES) {
                        MultiviewBarButton("Add channel", Icons.Rounded.Add) { picking = true }
                    }
                    Text(
                        text = "Multiview",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    MultiviewBarButton("Close Multiview", Icons.Rounded.Close) { TotalEclipseMultiview.close() }
                }
            }
            if (picking) {
                MultiviewChannelPicker(
                    taken = channels.map { it.id }.toSet(),
                    onPick = { channel ->
                        TotalEclipseMultiview.add(channel)
                        picking = false
                        chromeVisible = true
                        activity++
                    },
                    onDismiss = { picking = false },
                )
            }
        }
    }
}

@Composable
private fun MultiviewGrid(
    channels: List<LiveTvChannel>,
    sound: Int,
    chromeVisible: Boolean,
    onPaneTap: (Int) -> Unit,
) {
    @Composable
    fun Pane(index: Int, modifier: Modifier) {
        val channel = channels[index]
        Box(
            modifier = modifier
                .padding(2.dp)
                .border(
                    BorderStroke(2.dp, if (index == sound && channels.size > 1) Color.White.copy(alpha = 0.85f) else Color.Transparent),
                    RoundedCornerShape(6.dp),
                )
                .clip(RoundedCornerShape(6.dp)),
        ) {
            // Each pane keyed by channel, so removing one doesn't restart the others.
            androidx.compose.runtime.key(channel.id) {
                TotalEclipseMultiviewPane(
                    channel = channel,
                    muted = index != sound,
                    slot = index,
                    count = channels.size,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(modifier = Modifier.fillMaxSize().clickable { onPaneTap(index) })
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (index == sound) {
                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Has the sound", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = channel.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MultiviewBarButton("Watch ${channel.name} full screen", Icons.Rounded.OpenInFull) {
                        TotalEclipseMultiview.watchFull(index)
                    }
                    MultiviewBarButton("Remove ${channel.name}", Icons.Rounded.Close) {
                        TotalEclipseMultiview.remove(index)
                    }
                }
            }
        }
    }

    // One layout for every count, panes placed by index: a pane keeps playing when another is
    // added or removed (separate layout branches would rebuild it, and its stream with it).
    androidx.compose.ui.layout.Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            channels.forEachIndexed { index, channel ->
                androidx.compose.runtime.key(channel.id) { Pane(index, Modifier) }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val rects: List<IntArray> = when (measurables.size) {
            1 -> listOf(intArrayOf(0, 0, w, h))
            2 -> if (w >= h) listOf(intArrayOf(0, 0, w / 2, h), intArrayOf(w / 2, 0, w - w / 2, h))
                 else listOf(intArrayOf(0, 0, w, h / 2), intArrayOf(0, h / 2, w, h - h / 2))
            else -> listOf(
                intArrayOf(0, 0, w / 2, h / 2), intArrayOf(w / 2, 0, w - w / 2, h / 2),
                intArrayOf(0, h / 2, w / 2, h - h / 2), intArrayOf(w / 2, h / 2, w - w / 2, h - h / 2),
            )
        }
        val placeables = measurables.mapIndexed { i, m ->
            val r = rects[i]
            m.measure(androidx.compose.ui.unit.Constraints.fixed(r[2], r[3]))
        }
        layout(w, h) {
            placeables.forEachIndexed { i, pl -> pl.place(rects[i][0], rects[i][1]) }
        }
    }
}

@Composable
private fun MultiviewBarButton(
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp)
            .size(20.dp),
    )
}

/** Recently Watched and favourites first, then live games, then everything, with a search. */
@Composable
private fun MultiviewChannelPicker(
    taken: Set<String>,
    onPick: (LiveTvChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val sections = remember(state.channels, state.recentChannelIds, state.favoriteChannelIds, state.epgPrograms, query, taken) {
        val byId = state.channels.associateBy { it.id }
        val available = state.channels.filter { it.id !in taken }
        val needle = TotalEclipseLiveTabSearch.needle(query)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (needle.isNotEmpty()) {
            listOf("Results" to available.filter {
                TotalEclipseLiveTabSearch.matchRank(it, it.group.orEmpty(), state.epgPrograms[it.id], needle, now) != null
            }.take(60))
        } else {
            val recents = state.recentChannelIds.mapNotNull(byId::get).filter { it.id !in taken }
            val favourites = state.channels.filter { it.id in state.favoriteChannelIds && it.id !in taken }
            val games = com.nuvio.app.features.livetv.liveSportsNow(available, state.epgPrograms, now)
            listOf("Recently Watched" to recents, "Favorites" to favourites, "Live Sports Now" to games)
                .filter { it.second.isNotEmpty() }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add a channel", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Each pane is a stream from your provider.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search channels or shows", color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
                    inner()
                },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                sections.forEach { (title, list) ->
                    item(key = "h:$title") {
                        Text(
                            title,
                            color = Color.White.copy(alpha = 0.6f),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { "$title:${it.id}" }) { channel ->
                        Text(
                            text = channel.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(channel) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                }
                if (sections.isEmpty()) {
                    item { Text("Search to find a channel.", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) }
                }
            }
        }
    }
}

/** Multiview is for screens wide enough to share (iPad, or a phone on its side at 700 dp+). */
@Composable
internal fun totalEclipseMultiviewAvailable(): Boolean {
    val info = androidx.compose.ui.platform.LocalWindowInfo.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shortSide = with(density) { minOf(info.containerSize.width, info.containerSize.height).toDp() }
    return shortSide >= 600.dp
}
