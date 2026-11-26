package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Total Eclipse: the provider writes markers like "ᴺᵉʷ" and "ᴸᶦᵛᵉ" (superscript and small-capital
 * letters) after programme titles. As on the Apple TVs (HomeTV HomeTVGuideTitle), words made only
 * of such letters become tags ("Jeopardy! ᴺᵉʷ" → "Jeopardy!" + NEW), shown as coloured pills.
 * Worked out when drawn, so the saved guide is unchanged; plain titles take a fast path.
 */
internal object TotalEclipseGuideTitle {
    private val styled: Map<Char, Char> = buildMap {
        // Modifier (superscript) capitals and small letters.
        "ᴬAᴮBᴰDᴱEᴳGᴴHᴵIᴶJᴷKᴸLᴹMᴺNᴼOᴾPᴿRᵀTᵁUⱽVᵂW".chunked(2).forEach { put(it[0], it[1].lowercaseChar()) }
        "ᵃaᵇbᶜcᵈdᵉeᶠfᵍgʰhⁱiʲjᵏkˡlᵐmⁿnᵒoᵖpʳrˢsᵗtᵘuᵛvʷwˣxʸyᶻz".chunked(2).forEach { put(it[0], it[1]) }
        // Small capitals, and modifier small capitals.
        "ᴀaʙbᴄcᴅdᴇeꜰfɢgʜhɪiᴊjᴋkʟlᴍmɴnᴏoᴘpʀrꜱsᴛtᴜuᴠvᴡwʏyᴢzᶦiᶫlᶰnᶸu".chunked(2).forEach { put(it[0], it[1]) }
    }

    fun parse(title: String): Pair<String, List<String>> {
        if (title.all { it.code < 128 }) return title to emptyList()
        val out = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) { tags += run.toString().uppercase(); run.clear() }
        }
        for (word in title.split(' ')) {
            val letters = word.map { styled[it] }
            if (word.isNotEmpty() && letters.all { it != null }) {
                // Spaced-out letters ("ᴸ ᶦ ᵛ ᵉ") join into one word; whole words ("ᴺᵉʷ ᴸᶦᵛᵉ") stay apart.
                if (word.length > 1) flush()
                run.append(letters.joinToString(""))
                if (word.length > 1) flush()
            } else {
                flush()
                out += word
            }
        }
        flush()
        val text = out.joinToString(" ").trim()
        return if (text.isEmpty()) tags.joinToString(" ") to emptyList() else text to tags.distinct()
    }

    fun color(tag: String): Color = when {
        tag == "LIVE" -> Color(0xFFDB262E)
        tag == "NEW" -> Color(0xFF1F78F2)
        tag == "PREMIERE" || tag == "FINALE" -> Color(0xFF8C4DD9)
        tag.startsWith("MOVIE") -> Color(0xFFD98C1A)
        else -> Color.White.copy(alpha = 0.25f)
    }
}

/** The title to show, without the provider's markers. */
internal val LiveTvEpgProgram.displayTitle: String get() = TotalEclipseGuideTitle.parse(title).first

/** LIVE, NEW, … from the provider's markers. */
internal val LiveTvEpgProgram.guideTags: List<String> get() {
    val tags = TotalEclipseGuideTitle.parse(title).second
    // Total Eclipse: as on the Apple TVs, the guide's own premiere and movie flags give the NEW
    // and MOVIE pills too, not only the provider's markers in the title.
    val fromFlags = buildList {
        if (flags and TotalEclipseProgrammeKind.NEW != 0 && "NEW" !in tags) add("NEW")
        if (flags and TotalEclipseProgrammeKind.MOVIE != 0 && "MOVIE" !in tags) add(year?.let { "MOVIE $it" } ?: "MOVIE")
    }
    return if (fromFlags.isEmpty()) tags else tags + fromFlags
}

/** "S2 E5 · Episode title", or null when the guide has neither. */
internal val LiveTvEpgProgram.episodeLine: String? get() =
    listOfNotNull(episode, episodeTitle).joinToString(" · ").ifEmpty { null }

/** Total Eclipse: the TVs' facts line — "S2 E5 · Episode title" and the rating, outlined. */
@Composable
internal fun LiveTvProgrammeFacts(program: LiveTvEpgProgram, color: Color, modifier: Modifier = Modifier) {
    val line = program.episodeLine
    val rating = program.rating
    if (line == null && rating == null) return
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (line != null) {
            Text(
                text = line,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (rating != null) {
            Text(
                text = rating,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
internal fun LiveTvGuideTagPill(tag: String, modifier: Modifier = Modifier) {
    Text(
        text = tag,
        color = Color.White,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(TotalEclipseGuideTitle.color(tag), RoundedCornerShape(99.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Total Eclipse: "42m left", "1h 5m left" or "2h left", as on the Apple TVs. */
internal fun totalEclipseTimeLeft(endMillis: Long, nowMillis: Long): String {
    val minutes = maxOf(1L, (endMillis - nowMillis + 59_999L) / 60_000L)
    if (minutes < 60) return "${minutes}m left"
    val rest = minutes % 60
    return if (rest == 0L) "${minutes / 60}h left" else "${minutes / 60}h ${rest}m left"
}
