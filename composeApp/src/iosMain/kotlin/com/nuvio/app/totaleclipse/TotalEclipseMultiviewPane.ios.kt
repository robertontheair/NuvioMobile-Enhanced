package com.nuvio.app.totaleclipse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitViewController
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.livetv.LiveTvChannel
import com.nuvio.app.features.livetv.LiveTvRepository
import com.nuvio.app.features.player.NuvioPlayerBridgeFactory
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "TotalEclipse/Multiview"

/**
 * Total Eclipse: one Multiview pane — an embedded mpv player (it doesn't stop the others) with
 * sound when it has the focus. A pane that errors or ends opens its channel again, waiting a bit
 * longer each time (5 tries, then "Can't play this channel").
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun TotalEclipseMultiviewPane(
    channel: LiveTvChannel,
    muted: Boolean,
    slot: Int,
    count: Int,
    modifier: Modifier,
) {
    val bridge = remember(channel.id) { NuvioPlayerBridgeFactory.create() }
    var failed by remember(channel.id) { mutableStateOf(false) }
    if (bridge == null) {
        Box(modifier.background(Color.Black))
        return
    }

    LaunchedEffect(bridge, channel.id) {
        val playable = runCatching { LiveTvRepository.prepareForPlayback(channel) }.getOrDefault(channel)
        val headers = sanitizePlaybackHeaders(playable.headers).takeIf { it.isNotEmpty() }
            ?.let { runCatching { Json.encodeToString(it) }.getOrNull() }
        fun load() {
            bridge.setMultiviewPane(true)
            bridge.setResizeMode(0)
            bridge.loadFileWithAudio(videoUrl = playable.streamUrl, audioUrl = null, headersJson = headers, subtitlesJson = null)
            bridge.play()
        }
        load()
        InAppLogger.info(TAG, "pane opened ${channel.name}")
        var tries = 0
        while (isActive) {
            delay(2_000)
            val broken = runCatching { bridge.getErrorMessage().isNotBlank() || bridge.getIsEnded() }.getOrDefault(false)
            if (!broken) continue
            if (tries >= 5) { failed = true; return@LaunchedEffect }
            tries++
            InAppLogger.info(TAG, "pane ${channel.name} dropped; reopening ($tries/5)")
            delay(2_000L * tries)
            load()
        }
    }

    LaunchedEffect(bridge, muted) { runCatching { bridge.setMuted(muted) } }
    // Total Eclipse: on a connected TV, each pane takes its place of the grid there.
    LaunchedEffect(bridge, slot, count) { runCatching { bridge.setMultiviewSlot(slot, count) } }

    DisposableEffect(bridge) {
        onDispose { runCatching { bridge.pause(); bridge.destroy() } }
    }

    Box(modifier = modifier.background(Color.Black)) {
        UIKitViewController(
            factory = { bridge.createPlayerViewController() },
            modifier = Modifier.fillMaxSize(),
            interactive = false,
        )
        if (failed) {
            Text(
                "Can't play this channel",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
