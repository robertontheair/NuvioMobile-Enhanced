package com.nuvio.app.totaleclipse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.livetv.LiveTvChannel

/** Total Eclipse: Multiview is iOS-only for now. */
@Composable
internal actual fun TotalEclipseMultiviewPane(
    channel: LiveTvChannel,
    muted: Boolean,
    slot: Int,
    count: Int,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black))
}
