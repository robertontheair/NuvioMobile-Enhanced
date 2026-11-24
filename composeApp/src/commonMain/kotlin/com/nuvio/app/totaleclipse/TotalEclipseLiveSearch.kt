package com.nuvio.app.totaleclipse

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
    val needle = remember(query) { normalize(query) }
    val matches = remember(state.channels, needle) {
        if (needle.length < 2) emptyList()
        else state.channels.asSequence()
            .filter { normalize(it.name).contains(needle) || normalize(it.group.orEmpty()).contains(needle) }
            .sortedBy { if (normalize(it.name).startsWith(needle)) 0 else 1 }
            .take(30)
            .toList()
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
            items(matches, key = { it.id }) { channel ->
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
                }
            }
        }
    }
}

private fun normalize(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }
