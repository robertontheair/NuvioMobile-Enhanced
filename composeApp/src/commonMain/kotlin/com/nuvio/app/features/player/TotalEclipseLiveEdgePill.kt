package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Behind the live edge by more than this, the pill offers Back to Live. */
internal const val TOTAL_ECLIPSE_LIVE_EDGE_SECONDS = 15.0

/**
 * Total Eclipse: live TV's place in its rewind buffer, top centre while the controls show.
 * "● LIVE" at the live edge; behind it, "−5:23 · Back to Live", which jumps back to live.
 */
@Composable
internal fun TotalEclipseLiveEdgePill(
    secondsBehind: Double?,
    onBackToLive: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        val behind = secondsBehind ?: 0.0
        val atLive = behind < TOTAL_ECLIPSE_LIVE_EDGE_SECONDS
        Row(
            modifier = Modifier
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .then(if (atLive) Modifier else Modifier.clickable(onClick = onBackToLive))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (atLive) Color(0xFFDB262E) else Color.White.copy(alpha = 0.5f)),
            )
            Text(
                text = if (atLive) "LIVE" else "−${formatBehind(behind)} · Back to Live",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

internal fun formatBehind(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}
