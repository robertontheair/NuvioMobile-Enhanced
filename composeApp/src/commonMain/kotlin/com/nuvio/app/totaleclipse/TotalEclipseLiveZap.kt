package com.nuvio.app.totaleclipse

import androidx.compose.runtime.compositionLocalOf
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Total Eclipse: channel up/down and Last Channel in the Live TV player, as on the Apple TVs.
 * Up/down follow the list you picked the channel from (the category or filter on screen);
 * from Search or a reminder they follow the whole lineup. Last Channel goes back to the one
 * before, and again flips between the two.
 */
internal object TotalEclipseLiveZap {
    private var lineup: List<LiveTvChannel> = emptyList()
    private var currentId: String? = null
    private var last: LiveTvChannel? = null

    private val _requests = MutableSharedFlow<LiveTvChannel>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Channels to play; MainAppContent plays them as a channel tap. */
    val requests: SharedFlow<LiveTvChannel> = _requests.asSharedFlow()

    /** Called by the Live TV list with the channels on screen. */
    fun setLineup(channels: List<LiveTvChannel>) {
        lineup = channels
    }

    /** Called whenever a live channel starts. */
    fun played(channel: LiveTvChannel) {
        val previous = currentId
        if (previous != null && previous != channel.id) {
            last = findChannel(previous) ?: last
        }
        currentId = channel.id
        if (lineup.none { it.id == channel.id }) lineup = LiveTvRepository.uiState.value.channels
    }

    private fun findChannel(id: String): LiveTvChannel? =
        lineup.firstOrNull { it.id == id } ?: LiveTvRepository.uiState.value.channels.firstOrNull { it.id == id }

    private fun step(by: Int): LiveTvChannel? {
        val list = lineup.ifEmpty { LiveTvRepository.uiState.value.channels }
        if (list.size < 2) return null
        val index = list.indexOfFirst { it.id == currentId }
        if (index < 0) return null
        return list[(index + by + list.size) % list.size]
    }

    fun next(): LiveTvChannel? = step(1)
    fun previous(): LiveTvChannel? = step(-1)
    fun lastChannel(): LiveTvChannel? = last?.takeIf { it.id != currentId }

    fun request(channel: LiveTvChannel) {
        _requests.tryEmit(channel)
    }
}

/** Set by the player for a live channel: switches to another channel (closes this player first). */
internal val LocalTotalEclipseLiveZap = compositionLocalOf<((LiveTvChannel) -> Unit)?> { null }
