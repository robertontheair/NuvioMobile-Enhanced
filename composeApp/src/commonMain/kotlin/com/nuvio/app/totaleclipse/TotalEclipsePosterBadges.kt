package com.nuvio.app.totaleclipse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.home.MetaPreview
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * Total Eclipse: the Apple TVs' poster badges (HomeTV HomeTVComingSoonBadge.swift and the Top 10
 * numbers): "COMING DEC 18" on titles not out yet, and #1–#10 on the Top 10 row.
 */
internal object TotalEclipsePosterBadges {
    private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** "COMING DEC 18" (this year), "COMING DEC 18, 2027", "COMING 2027", or null when it's out. */
    fun comingSoonLabel(item: MetaPreview): String? {
        val today = runCatching { kotlin.time.Clock.System.todayIn(TimeZone.currentSystemDefault()) }.getOrNull() ?: return null
        for (raw in listOf(item.rawReleaseDate, item.releaseInfo)) {
            val text = raw?.trim().orEmpty()
            if (text.length >= 10) {
                val date = runCatching { LocalDate.parse(text.take(10)) }.getOrNull() ?: continue
                if (date <= today) return null
                val day = "${months[date.month.ordinal]} ${date.day}"
                return if (date.year == today.year) "COMING $day" else "COMING $day, ${date.year}"
            }
        }
        val year = item.releaseInfo?.trim()?.take(4)?.toIntOrNull() ?: return null
        return if (year > today.year) "COMING $year" else null
    }

    /** "Dec 18" (or "Dec 18, 2027") for a date after today, else null. Only the day counts. */
    fun comingDay(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.length < 10) return null
        val today = runCatching { kotlin.time.Clock.System.todayIn(TimeZone.currentSystemDefault()) }.getOrNull() ?: return null
        val date = runCatching { LocalDate.parse(text.take(10)) }.getOrNull() ?: return null
        if (date <= today) return null
        val month = months[date.month.ordinal].lowercase().replaceFirstChar { it.uppercase() }
        return if (date.year == today.year) "$month ${date.day}" else "$month ${date.day}, ${date.year}"
    }

    const val TOP10_PREFIX = "totaleclipse_top10"
}

/** Wraps a poster with the TVs' badges; [rank] is set only on the Top 10 row. */
@Composable
internal fun TotalEclipsePosterBadgeBox(
    item: MetaPreview,
    rank: Int?,
    content: @Composable BoxScope.() -> Unit,
) {
    val coming = TotalEclipsePosterBadges.comingSoonLabel(item)
    Box {
        content()
        if (rank != null) {
            Text(
                text = "$rank",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (coming != null) {
            Text(
                text = coming,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(if (rank != null) Alignment.TopEnd else Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}
