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
    /** Total Eclipse: event slots with a game on now, then starting within 2 h (HomeTV sportsNow). */
    LiveSportsNow("Live Sports Now", 0),
    Sports("Sports On Now", TotalEclipseProgrammeKind.SPORTS),
    Movies("Movies On Now", TotalEclipseProgrammeKind.MOVIE),
    News("News On Now", TotalEclipseProgrammeKind.NEWS),
    Kids("Kids On Now", TotalEclipseProgrammeKind.KIDS),
    New("New Episodes", TotalEclipseProgrammeKind.NEW),
}

/**
 * Channels with a matching programme on at [atMillis], A to Z by name, with the Apple TVs'
 * placement (HomeTV HomeTVLiveGroups.filterPlacement): Kids & Family channels' films are left out
 * of Movies; other cities' locals and international channels follow the rest in Sports, and in the
 * other filters show only when you watch them (favourite or Recently Watched), after the rest.
 */
internal fun liveTvOnNow(
    channels: List<LiveTvChannel>,
    programs: Map<String, List<LiveTvEpgProgram>>,
    filter: LiveTvOnNowFilter,
    atMillis: Long,
    watched: Set<String> = emptySet(),
): List<LiveTvChannel> {
    if (filter == LiveTvOnNowFilter.LiveSportsNow) return liveSportsNow(channels, programs, atMillis)
    val keys = com.nuvio.app.totaleclipse.TotalEclipseLiveLayout.categoryKeyByChannel
    val first = mutableListOf<LiveTvChannel>()
    val later = mutableListOf<LiveTvChannel>()
    for (channel in channels) {
        if (programs[channel.id]?.any { it.isAiringAt(atMillis) && it.flags and filter.flag != 0 } != true) continue
        val key = keys[channel.id]
        if (filter == LiveTvOnNowFilter.Movies && key == com.nuvio.app.totaleclipse.TotalEclipseLiveGroups.KIDS_KEY) continue
        val elsewhere = key != null && (
            key.startsWith("\u0001smart.region.") ||
                (key.startsWith("\u0001smart.local.") && key != com.nuvio.app.totaleclipse.TotalEclipseLiveGroups.HOME_MARKET_KEY)
            )
        when {
            !elsewhere -> first += channel
            filter == LiveTvOnNowFilter.Sports || channel.id in watched -> later += channel
        }
    }
    val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { channel: LiveTvChannel -> channel.name }
    return first.sortedWith(byName) + later.sortedWith(byName)
}

/**
 * Total Eclipse: as on the Apple TVs (HomeTV HomeTVLiveGroups.sportsNow): the event slots with a
 * game on at [atMillis] (earliest start first), then the ones starting within two hours (soonest
 * first). epgfix's guide for a slot reads "Coming up: <game>" until it starts, the game, then
 * "Event ended"; an empty slot is "No event scheduled". The same game in two slots shows once.
 */
internal fun liveSportsNow(
    channels: List<LiveTvChannel>,
    programs: Map<String, List<LiveTvEpgProgram>>,
    atMillis: Long,
    comingUpMillis: Long = 2L * 60 * 60 * 1000,
): List<LiveTvChannel> {
    val keys = com.nuvio.app.totaleclipse.TotalEclipseLiveLayout.categoryKeyByChannel
    val eventKey = com.nuvio.app.totaleclipse.TotalEclipseLiveGroups.tvKey(com.nuvio.app.totaleclipse.TotalEclipseLiveGroups.LIVE_EVENTS)
    val live = mutableListOf<Triple<LiveTvChannel, Long, String>>()
    val soon = mutableListOf<Triple<LiveTvChannel, Long, String>>()
    for (channel in channels) {
        val isEvent = keys[channel.id]?.let { it == eventKey } ?: (channel.group == com.nuvio.app.totaleclipse.TotalEclipseLiveGroups.LIVE_EVENTS)
        if (!isEvent) continue
        val now = programs[channel.id]?.firstOrNull { it.isAiringAt(atMillis) } ?: continue
        val title = now.title.trim()
        when {
            title.startsWith("Coming up") -> if (now.endMillis <= atMillis + comingUpMillis) soon += Triple(channel, now.endMillis, title)
            title != "No event scheduled" && title != "Event ended" -> live += Triple(channel, now.startMillis, title)
        }
    }
    // Total Eclipse: games with a team you follow first, live then coming up.
    com.nuvio.app.totaleclipse.TotalEclipseSportsPrefs.load()
    val teams = com.nuvio.app.totaleclipse.TotalEclipseSportsPrefs.teams.value
    fun mine(t: Triple<LiveTvChannel, Long, String>) =
        if (com.nuvio.app.totaleclipse.TotalEclipseSportsPrefs.titleMentionsFollowed(t.third, teams)) 0 else 1
    val seen = HashSet<String>()
    return (live.sortedWith(compareBy({ mine(it) }, { it.second })) + soon.sortedWith(compareBy({ mine(it) }, { it.second })))
        .filter { seen.add(it.third.lowercase()) }
        .map { it.first }
}
