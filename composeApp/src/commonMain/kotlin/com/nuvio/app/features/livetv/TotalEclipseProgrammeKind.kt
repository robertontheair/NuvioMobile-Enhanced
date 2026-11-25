package com.nuvio.app.features.livetv

/**
 * Total Eclipse: what kind of programme this is, with the Apple TVs' rules (HomeTV
 * HomeTVProgrammeInfo.make), for the guide's "On Now" filters. Worked out once while the guide
 * is read and kept in [LiveTvEpgProgram.flags].
 */
internal object TotalEclipseProgrammeKind {
    const val NEW = 1
    const val MOVIE = 2
    const val SPORTS = 4
    const val NEWS = 8
    const val KIDS = 16

    private val sportCategories = setOf(
        "sports", "sports event", "football", "college football", "soccer", "basketball", "3x3 basketball",
        "baseball", "hockey", "golf", "tennis", "auto racing", "motorsports", "motorcycle racing", "boxing",
        "mixed martial arts", "martial arts", "judo", "pro wrestling", "wrestling", "rugby", "rugby league",
        "volleyball", "action sports", "surfing", "diving", "triathlon", "running", "archery", "rodeo",
        "olympics", "bowling", "mountain biking", "playoff sports", "multi-sport event", "classic sport event",
        "cricket", "lacrosse", "softball", "track/field", "skiing", "snowboarding", "cycling", "horse racing",
    )
    private val newsCategories = setOf("news", "newsmagazine")
    private val kidsRatings = setOf("TV-Y", "TV-Y7", "TV-G", "G")

    fun classify(
        categories: List<String>,
        premiere: Boolean,
        previouslyShown: Boolean,
        rating: String?,
        episodeNum: String?,
        subtitle: String?,
        descriptionHead: String?,
        title: String?,
        minutes: Double?,
    ): Int {
        var flags = 0
        val cats = categories.map { it.trim().lowercase() }.toSet()
        val normalizedRating = rating?.let(::normalizedRating)
        val adult = "adults only" in cats || "erotic" in cats
        if ("movie" in cats && !adult) flags = flags or MOVIE
        if (cats.any { it in sportCategories }) flags = flags or SPORTS
        if (cats.any { it in newsCategories }) flags = flags or NEWS
        if ("children" in cats || ("animated" in cats && normalizedRating in kidsRatings)) flags = flags or KIDS
        if (premiere && !previouslyShown) flags = flags or NEW
        // HBO, TCM, Showtime, Starz, Cinemax… carry no categories in this guide.
        if (categories.isEmpty() && looksLikeMovie(title, minutes, episodeNum, subtitle, descriptionHead)) flags = flags or MOVIE
        return flags
    }

    /** "TVPG" → "TV-PG"; other scales as given. */
    fun normalizedRating(raw: String): String? {
        val r = raw.trim().uppercase()
        if (r.isEmpty() || r.length > 8) return null
        if (r.startsWith("TV") && !r.startsWith("TV-") && r.length > 2) return "TV-" + r.drop(2)
        return r
    }

    /**
     * An uncategorized programme that's a film: 75 min or longer, no episode, and a description
     * naming actors in brackets ("…leads his country (Brendan Gleeson)"); games are excluded.
     */
    fun looksLikeMovie(title: String?, minutes: Double?, episodeNum: String?, subtitle: String?, descriptionHead: String?): Boolean {
        if (minutes == null || minutes < 75 || descriptionHead == null || episodeNum != null || !subtitle.isNullOrEmpty()) return false
        if (startsWithEpisode(descriptionHead)) return false
        title?.lowercase()?.let { if (" vs. " in it || " vs " in it || " at #" in it) return false }
        val head = descriptionHead.take(300)
        if ('(' !in head) return false
        return actorList.containsMatchIn(head)
    }

    /** "(Brendan Gleeson)", "(Judy Garland, Gene Kelly)". */
    private val actorList = Regex("""\((?:[A-Z][\w.'-]+(?: [A-Z][\w.'-]+)+)(?:, [A-Z][\w.'-]+(?: [A-Z][\w.'-]+)+)*\)""")

    /** "S02 E03 …" at the very start of a description. */
    private fun startsWithEpisode(head: String): Boolean {
        if (head.length < 2 || head[0] != 'S' || !head[1].isAsciiDigit()) return false
        var i = 1
        while (i < head.length && head[i].isAsciiDigit()) i++
        while (i < head.length && head[i].isWhitespace()) i++
        if (i >= head.length || head[i] != 'E') return false
        return i + 1 < head.length && head[i + 1].isAsciiDigit()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

/** Total Eclipse: the guide's "On Now" filters, as on the Apple TVs. */
internal enum class LiveTvOnNowFilter(val label: String, val flag: Int) {
    Sports("Sports On Now", TotalEclipseProgrammeKind.SPORTS),
    Movies("Movies On Now", TotalEclipseProgrammeKind.MOVIE),
    News("News On Now", TotalEclipseProgrammeKind.NEWS),
    Kids("Kids On Now", TotalEclipseProgrammeKind.KIDS),
    New("New Episodes", TotalEclipseProgrammeKind.NEW),
}

/** Channels with a matching programme on at [atMillis], A to Z by name. */
internal fun liveTvOnNow(
    channels: List<LiveTvChannel>,
    programs: Map<String, List<LiveTvEpgProgram>>,
    filter: LiveTvOnNowFilter,
    atMillis: Long,
): List<LiveTvChannel> =
    channels.filter { channel ->
        programs[channel.id]?.any { it.isAiringAt(atMillis) && it.flags and filter.flag != 0 } == true
    }.sortedBy { it.name.lowercase() }
