package com.nuvio.app.totaleclipse

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktExternalIds
import com.nuvio.app.features.trakt.TraktImagesDto
import com.nuvio.app.features.trakt.TraktLibraryRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.nuvio.app.features.trakt.normalizeTraktContentId
import com.nuvio.app.features.trakt.traktBestBackdropUrl
import com.nuvio.app.features.trakt.traktBestLogoUrl
import com.nuvio.app.features.trakt.traktBestPosterUrl
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Total Eclipse: HomeTV's personal rows on the Movies and Shows tabs (Apple TV: HomeTVMyList,
 * CalendarHomeToday.buildThisWeek, HomeTVTop10, HomeTVBecauseYouWatched, HomeTVTraktRecommended,
 * HomeTVComingSoonRow; order from HomeTVRowOrder).
 *
 * Pinned (after Continue Watching / Upcoming, which the phone already has): Watchlist, then
 * New This Week on Shows. Then: Top 10 This Week · (first catalog) · Because you watched (a
 * finished title) · Recommended for You · Because you watched (in progress) · Coming Soon.
 */
internal data class TotalEclipsePersonalRowsState(
    val myList: List<MetaPreview> = emptyList(),
    val newThisWeek: List<MetaPreview> = emptyList(),
    val top10: List<MetaPreview> = emptyList(),
    val becauseFinished: Pair<String, List<MetaPreview>>? = null,
    val recommended: List<MetaPreview> = emptyList(),
    val becauseWatching: Pair<String, List<MetaPreview>>? = null,
    val comingSoon: List<MetaPreview> = emptyList(),
) {
    companion object {
        val Empty = TotalEclipsePersonalRowsState()
    }
}

internal object TotalEclipsePersonalRows {
    private val log = Logger.withTag("TEPersonalRows")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val TRAKT_API = "https://api.trakt.tv"
    private const val TMDB_IMAGES = "https://image.tmdb.org/t/p/"
    /** TraktLibraryRepository's key for the watchlist (private there). */
    private const val TRAKT_WATCHLIST_KEY = "trakt:watchlist"

    // HomeTV's refresh intervals.
    private const val HOUR_MS = 60 * 60_000L
    private const val MY_LIST_REFRESH_MS = 15 * 60_000L // HomeTVMyList.refreshInterval
    private const val NEW_THIS_WEEK_MS = 3 * HOUR_MS // CalendarTodayHomeFeed.maxAge
    private const val TOP10_MS = 6 * HOUR_MS // HomeTVTop10.cacheSeconds
    private const val RECOMMENDED_MS = 4 * HOUR_MS // HomeTVTraktRecommended.cacheSeconds
    private const val BECAUSE_MS = 6 * HOUR_MS // HomeTVBecauseYouWatched.cacheSeconds
    private const val COMING_SOON_MS = 6 * HOUR_MS // HomeTVComingSoonRow.diskMaxAge

    // HomeTV's counts.
    private const val MY_LIST_MAX = 100
    private const val TOP10_COUNT = 10
    private const val RECOMMENDED_FETCH = 60
    private const val ROW_LIMIT = 20 // Recommended and Because rows
    private const val BECAUSE_ROTATION = 3
    private const val COMING_SOON_DAYS = 60
    private const val LOOKUP_CONCURRENCY = 4

    private val flows: Map<TotalEclipseBrowseKind, MutableStateFlow<TotalEclipsePersonalRowsState>> =
        TotalEclipseBrowseKind.entries.associateWith { MutableStateFlow(TotalEclipsePersonalRowsState.Empty) }

    private val jobsLock = Mutex()
    private val jobs = mutableMapOf<TotalEclipseBrowseKind, Job>()

    private val cacheLock = Mutex()
    private val cache = mutableMapOf<String, Timed>()
    private val myListRefreshedAt = mutableMapOf<String, Long>()
    private val shownProfile = mutableMapOf<TotalEclipseBrowseKind, Int>()

    private class Timed(val atMs: Long, val items: List<MetaPreview>)

    fun state(kind: TotalEclipseBrowseKind): StateFlow<TotalEclipsePersonalRowsState> =
        flows.getValue(kind).asStateFlow()

    /**
     * Loads what's due for this tab. Cheap to call often: each part is fetched again only after
     * HomeTV's interval for it (or with [force]). A refresh already running for this tab is kept
     * unless [force].
     */
    fun refresh(kind: TotalEclipseBrowseKind, force: Boolean = false) {
        scope.launch {
            jobsLock.withLock {
                val running = jobs[kind]?.takeIf { it.isActive }
                if (running != null && !force) return@launch
                running?.cancel()
                jobs[kind] = scope.launch { load(kind, force) }
            }
        }
    }

    // region Loading

    private suspend fun load(kind: TotalEclipseBrowseKind, force: Boolean) {
        val profile = ProfileRepository.activeProfileId
        val flow = flows.getValue(kind)
        val profileChanged = cacheLock.withLock {
            val changed = shownProfile[kind] != profile
            shownProfile[kind] = profile
            changed
        }
        if (profileChanged) flow.value = TotalEclipsePersonalRowsState.Empty

        val headers = TraktAuthRepository.authorizedHeaders()
        if (headers == null) {
            flow.value = TotalEclipsePersonalRowsState.Empty
            return
        }
        fun publish(change: (TotalEclipsePersonalRowsState) -> TotalEclipsePersonalRowsState) {
            if (ProfileRepository.activeProfileId == profile) flow.update(change)
        }

        val continueWatching = continueWatchingMetas(kind)
        val myList = safely(emptyList()) {
            loadMyList(kind, profile, force, started = continueWatching.map { it.id }.toSet())
        }
        publish { it.copy(myList = myList) }

        // HomeTV leaves titles in Continue Watching or on the Watchlist out of the picks.
        val inProgressKeys = (continueWatching + myList).flatMapTo(mutableSetOf()) { it.titleKeys() }

        coroutineScope {
            if (kind == TotalEclipseBrowseKind.Shows) {
                launch {
                    val rows = safely(emptyList()) { loadNewThisWeek(headers, profile, force) }
                    publish { it.copy(newThisWeek = rows) }
                }
            }
            launch {
                val rows = safely(emptyList()) { loadTop10(headers, kind, force) }
                publish { it.copy(top10 = rows) }
            }
            launch {
                val rows = safely(emptyList()) { loadRecommended(headers, kind, profile, force, inProgressKeys) }
                publish { it.copy(recommended = rows) }
            }
            launch {
                val (finished, watching) = safely(null to null) {
                    loadBecause(kind, force, continueWatching, myList, inProgressKeys)
                }
                publish { it.copy(becauseFinished = finished, becauseWatching = watching) }
            }
            launch {
                val rows = safely(emptyList()) { loadComingSoon(kind, force) }
                publish { it.copy(comingSoon = rows) }
            }
        }
    }

    /** HomeTVMyList: the Trakt watchlist in Trakt's order; titles you've started after the rest. */
    private suspend fun loadMyList(
        kind: TotalEclipseBrowseKind,
        profile: Int,
        force: Boolean,
        started: Set<String>,
    ): List<MetaPreview> {
        val key = "mylist|$profile"
        val now = TraktPlatformClock.nowEpochMs()
        val due = cacheLock.withLock {
            val last = myListRefreshedAt[key]
            val isDue = force || last == null || now - last >= MY_LIST_REFRESH_MS
            if (isDue) myListRefreshedAt[key] = now
            isDue
        }
        if (due) {
            if (force) TraktLibraryRepository.refreshNow() else TraktLibraryRepository.ensureFresh()
        }
        val items = TraktLibraryRepository.uiState.value.entriesByList[TRAKT_WATCHLIST_KEY].orEmpty()
            .filter { kind.includesType(it.type) }
        val ordered = if (items.any { it.traktRank != null }) {
            items.filter { it.traktRank != null }.sortedBy { it.traktRank } // stable: ties keep order
        } else {
            items.sortedByDescending { it.savedAtEpochMs }
        }
        val metas = ordered.map { it.toMetaPreview() }.distinctBy { it.id }.take(MY_LIST_MAX)
        return metas.filter { it.id !in started } + metas.filter { it.id in started }
    }

    /**
     * CalendarHomeToday.buildThisWeek: episodes of your shows from the last 7 days (today
     * included), one card per show for its newest episode, newest first. HomeTV reads its own
     * calendar of library shows; here Trakt's calendar of your shows.
     */
    private suspend fun loadNewThisWeek(
        headers: Map<String, String>,
        profile: Int,
        force: Boolean,
    ): List<MetaPreview> {
        val start = today().plus(-6, DateTimeUnit.DAY).toString()
        return cached("newweek|$profile|$start", NEW_THIS_WEEK_MS, force) {
            val body = traktGet(headers, "calendars/my/shows/$start/7?extended=full,images", "calendar")
                ?: return@cached null
            val entries = json.decodeFromString<List<CalendarShowDto>>(body)
                .sortedByDescending { it.firstAired.orEmpty() }
            val previews = entries.mapNotNull { e -> e.show?.toPreview("series")?.let { it to e } }
                .distinctBy { it.first.id }
            // Total Eclipse: as on the TVs, each card says when its newest episode aired and
            // which one it is ("TODAY · S2 E5", "MONDAY · S1 E8").
            previews.forEach { (preview, e) ->
                val day = newThisWeekDay(e.firstAired) ?: return@forEach
                val ep = e.episode?.let { ep -> if (ep.season != null && ep.number != null) " · S${ep.season} E${ep.number}" else "" }.orEmpty()
                TotalEclipsePosterBadges.newThisWeekLabels[preview.id] = day + ep
            }
            previews.map { it.first }
        }
    }

    private fun newThisWeekDay(firstAired: String?): String? {
        val instant = firstAired?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() } ?: return null
        val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
        val day = instant.toLocalDateTime(zone).date
        val now = today()
        return when (day) {
            now -> "TODAY"
            now.plus(-1, DateTimeUnit.DAY) -> "YESTERDAY"
            else -> day.dayOfWeek.name
        }
    }

    /** HomeTVTop10: Trakt's most watched this week, at most 10, no repeats. */
    private suspend fun loadTop10(
        headers: Map<String, String>,
        kind: TotalEclipseBrowseKind,
        force: Boolean,
    ): List<MetaPreview> {
        val path = kind.traktPath
        // Not personal: shared by every profile, as on HomeTV.
        return cached("top10|$path", TOP10_MS, force) {
            val body = traktGet(headers, "$path/watched/weekly?limit=$TOP10_COUNT&extended=full,images", "top10")
                ?: return@cached null
            json.decodeFromString<List<WeeklyDto>>(body)
                .mapNotNull { row -> (row.movie ?: row.show)?.toPreview(kind.contentType) }
                .distinctBy { it.id }
                .take(TOP10_COUNT)
                .takeIf { it.isNotEmpty() }
        }
    }

    /**
     * HomeTVTraktRecommended: Trakt's recommendations for the account (watchlisted and collected
     * left out by Trakt), minus watched / in progress / watchlist, at most 20 in Trakt's order.
     */
    private suspend fun loadRecommended(
        headers: Map<String, String>,
        kind: TotalEclipseBrowseKind,
        profile: Int,
        force: Boolean,
        excluding: Set<String>,
    ): List<MetaPreview> {
        val path = kind.traktPath
        val picks = cached("recs|$profile|$path", RECOMMENDED_MS, force) {
            val body = traktGet(
                headers,
                "recommendations/$path?limit=$RECOMMENDED_FETCH&ignore_collected=true&ignore_watchlisted=true&extended=full,images",
                "recommended",
            ) ?: return@cached null
            json.decodeFromString<List<TraktMediaDto>>(body).mapNotNull { it.toPreview(kind.contentType) }
        }
        return filterPicks(picks, excluding)
    }

    /**
     * HomeTVBecauseYouWatched: TMDB's recommendations for two seeds of this tab's type:
     * - watching: the latest title in Continue Watching, else the first Watchlist title ("added");
     * - finished: from watch history; the newest if watched in the last day, otherwise a daily
     *   rotation among the last 3 finished titles.
     * The second row doesn't repeat the first one's titles. Returns (finished, watching).
     */
    private suspend fun loadBecause(
        kind: TotalEclipseBrowseKind,
        force: Boolean,
        continueWatching: List<MetaPreview>,
        myList: List<MetaPreview>,
        inProgress: Set<String>,
    ): Pair<Pair<String, List<MetaPreview>>?, Pair<String, List<MetaPreview>>?> {
        val watchingSeed = continueWatching.firstOrNull() ?: myList.firstOrNull()
        val watchingIsListed = watchingSeed != null && continueWatching.none { it.id == watchingSeed.id }
        val finishedSeed = finishedSeed(kind, excluding = watchingSeed?.titleKeys().orEmpty())

        // HomeTV loads the watching row first, then the finished one without its titles.
        val shown = mutableSetOf<String>()
        var watching: Pair<String, List<MetaPreview>>? = null
        var finished: Pair<String, List<MetaPreview>>? = null
        if (watchingSeed != null) {
            val items = filterBecause(becauseRecommendations(watchingSeed, force), watchingSeed, inProgress + shown)
            shown += watchingSeed.titleKeys()
            items.forEach { shown += it.titleKeys() }
            val title = if (watchingIsListed) {
                "Because you added ${watchingSeed.name} to your Watchlist"
            } else {
                "Because you watched ${watchingSeed.name}"
            }
            if (items.isNotEmpty()) watching = title to items
        }
        if (finishedSeed != null) {
            val items = filterBecause(becauseRecommendations(finishedSeed, force), finishedSeed, inProgress + shown)
            if (items.isNotEmpty()) finished = "Because you watched ${finishedSeed.name}" to items
        }
        return finished to watching
    }

    private fun finishedSeed(kind: TotalEclipseBrowseKind, excluding: Set<String>): MetaPreview? {
        WatchedRepository.ensureLoaded()
        val seen = mutableSetOf<String>()
        val recent = mutableListOf<Pair<MetaPreview, Long>>()
        for (item in WatchedRepository.uiState.value.items.sortedByDescending { it.markedAtEpochMs }) {
            if (recent.size >= BECAUSE_ROTATION) break
            if (!kind.includesType(item.type)) continue
            val meta = MetaPreview(
                id = item.id,
                type = kind.contentType,
                name = item.name,
                poster = item.poster,
                // An episode's releaseInfo isn't the show's year; leave it out of the keys.
                releaseInfo = item.releaseInfo.takeIf { item.season == null },
            )
            val keys = meta.titleKeys()
            if (keys.any { it in excluding || it in seen }) continue
            seen += keys
            recent += meta to item.markedAtEpochMs
        }
        val newest = recent.firstOrNull() ?: return null
        if (TraktPlatformClock.nowEpochMs() - newest.second < 24 * HOUR_MS) return newest.first
        val day = today().toEpochDays().toLong()
        return recent[(day % recent.size).toInt()].first
    }

    private suspend fun becauseRecommendations(seed: MetaPreview, force: Boolean): List<MetaPreview> =
        cached("because|${seed.type}|${seed.id}", BECAUSE_MS, force) {
            if (TmdbSettingsRepository.effectiveApiKey().isBlank()) return@cached null
            // HomeTV needs TMDB turned on; the phone has a built-in key, so this works either way.
            val settings = TmdbSettingsRepository.snapshot().copy(enabled = true, useMoreLikeThis = true)
            val page = TmdbMetadataService.fetchMoreLikeThisPage(
                itemId = seed.id,
                itemType = seed.type,
                page = 1,
                settings = settings,
            )
            withImdbIds(page.items.filter { it.type == seed.type }).takeIf { it.isNotEmpty() }
        }

    /**
     * HomeTVComingSoonRow: what comes out in the next 60 days, soonest first. Movies: popular
     * English-language films by their first US digital (watch-at-home) date; shows: series
     * premieres. TMDB, as on HomeTV.
     */
    private suspend fun loadComingSoon(kind: TotalEclipseBrowseKind, force: Boolean): List<MetaPreview> {
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) return emptyList()
        val day0 = today()
        val from = day0.plus(1, DateTimeUnit.DAY).toString()
        val until = day0.plus(COMING_SOON_DAYS, DateTimeUnit.DAY).toString()
        return cached("coming|${kind.contentType}|$from", COMING_SOON_MS, force) {
            val found = if (kind == TotalEclipseBrowseKind.Movies) comingSoonMovies(from, until) else comingSoonShows(from, until)
            found.sortedBy { it.rawReleaseDate.orEmpty() } // stable: same day keeps popularity order
                .distinctBy { it.id }
                .takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun comingSoonMovies(from: String, until: String): List<MetaPreview> {
        val items = mutableListOf<TmdbListItemDto>()
        for (page in 1..3) {
            val results = tmdbGet<TmdbPagedDto>(
                "discover/movie",
                mapOf(
                    "region" to "US",
                    "with_release_type" to "4",
                    "release_date.gte" to from,
                    "release_date.lte" to until,
                    "sort_by" to "popularity.desc",
                    "with_original_language" to "en",
                    "page" to page.toString(),
                ),
            )?.results.orEmpty()
            if (results.isEmpty()) break
            items += results
        }
        val gate = Semaphore(LOOKUP_CONCURRENCY)
        return coroutineScope {
            items.filter { !it.posterPath.isNullOrBlank() }.distinctBy { it.id }.map { item ->
                async {
                    gate.withPermit {
                        val details = tmdbGet<TmdbMovieDetailsDto>(
                            "movie/${item.id}",
                            mapOf("append_to_response" to "release_dates"),
                        ) ?: return@withPermit null
                        val day = digitalDay(details.releaseDates?.results.orEmpty(), from, until)
                            ?: return@withPermit null
                        item.toPreview("movie", imdbId = details.imdbId, day = day)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun comingSoonShows(from: String, until: String): List<MetaPreview> {
        val found = mutableListOf<Pair<TmdbListItemDto, String>>()
        for (page in 1..2) {
            val results = tmdbGet<TmdbPagedDto>(
                "discover/tv",
                mapOf(
                    "first_air_date.gte" to from,
                    "first_air_date.lte" to until,
                    "sort_by" to "popularity.desc",
                    "with_original_language" to "en",
                    "page" to page.toString(),
                ),
            )?.results.orEmpty()
            if (results.isEmpty()) break
            for (item in results) {
                val day = item.firstAirDate?.takeIf { it.length >= 10 }?.take(10) ?: continue
                if (item.posterPath.isNullOrBlank()) continue
                found += item to day
            }
        }
        val gate = Semaphore(LOOKUP_CONCURRENCY)
        return coroutineScope {
            found.distinctBy { it.first.id }.map { (item, day) ->
                async {
                    gate.withPermit {
                        val imdb = runCatching { TmdbService.tmdbToImdb(item.id, "tv") }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        item.toPreview("series", imdbId = imdb, day = day)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** The film's first US digital release (TMDB type 4) day, when it falls within from…until. */
    private fun digitalDay(countries: List<TmdbReleaseCountryDto>, from: String, until: String): String? {
        val first = countries.filter { it.country == "US" }
            .flatMap { it.releaseDates.orEmpty() }
            .filter { it.type == 4 }
            .mapNotNull { it.releaseDate?.take(10) }
            .minOrNull() ?: return null
        return first.takeIf { it >= from && it <= until }
    }

    // endregion

    // region Filtering (HomeTVTraktRecommended.filtered / HomeTVBecauseYouWatched.filtered)

    private fun filterPicks(items: List<MetaPreview>, excluding: Set<String>): List<MetaPreview> {
        val seen = excluding.toMutableSet()
        val out = mutableListOf<MetaPreview>()
        for (meta in items) {
            if (out.size >= ROW_LIMIT) break
            val keys = meta.titleKeys()
            if (keys.any { it in seen } || isWatched(meta)) continue
            seen += keys
            out += meta
        }
        return out
    }

    private fun filterBecause(items: List<MetaPreview>, seed: MetaPreview, excluding: Set<String>): List<MetaPreview> {
        val seen = (excluding + seed.titleKeys()).toMutableSet()
        val seedName = seed.name.lowercase()
        val out = mutableListOf<MetaPreview>()
        for (meta in items) {
            if (out.size >= ROW_LIMIT) break
            val keys = meta.titleKeys()
            if (meta.name.lowercase() == seedName || keys.any { it in seen } || isWatched(meta)) continue
            seen += keys
            out += meta
        }
        return out
    }

    /** Watched as a whole title: a movie with a play, a show with every episode watched. */
    private fun isWatched(meta: MetaPreview): Boolean =
        if (meta.type == "movie") {
            WatchedRepository.isWatched(id = meta.id, type = "movie")
        } else {
            WatchedRepository.isFullyWatchedSeries(id = meta.id, type = "series")
        }

    /** HomeTVBecauseYouWatched.keys: the id and "name|year" (one title can come as tt… or tmdb:…). */
    private fun MetaPreview.titleKeys(): Set<String> =
        setOf(id, "${name.lowercase()}|${releaseInfo?.take(4).orEmpty()}")

    // endregion

    // region Helpers

    private fun continueWatchingMetas(kind: TotalEclipseBrowseKind): List<MetaPreview> =
        WatchProgressRepository.continueWatching()
            .filter { kind.includesType(it.parentMetaType) }
            .map { entry ->
                MetaPreview(
                    id = entry.parentMetaId,
                    type = kind.contentType,
                    name = entry.title,
                    poster = entry.poster,
                )
            }
            .distinctBy { it.id }

    /** tmdb:N ids swapped for IMDb ids where TMDB knows one (Details pages prefer tt ids). */
    private suspend fun withImdbIds(items: List<MetaPreview>): List<MetaPreview> {
        val gate = Semaphore(LOOKUP_CONCURRENCY)
        return coroutineScope {
            items.map { meta ->
                async {
                    val tmdbId = meta.id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull()
                        ?: return@async meta
                    val imdb = gate.withPermit {
                        runCatching { TmdbService.tmdbToImdb(tmdbId, if (meta.type == "movie") "movie" else "tv") }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                    }
                    if (imdb != null && imdb.startsWith("tt")) meta.copy(id = imdb) else meta
                }
            }.awaitAll()
        }
    }

    /**
     * A fresh cached copy, else [fetch] (null = no answer). With no answer the last copy, however
     * old, beats an empty row (HomeTV keeps the stale disk copy the same way).
     */
    private suspend fun cached(
        key: String,
        ttlMs: Long,
        force: Boolean,
        fetch: suspend () -> List<MetaPreview>?,
    ): List<MetaPreview> {
        val now = TraktPlatformClock.nowEpochMs()
        val hit = cacheLock.withLock { cache[key] }
        if (!force && hit != null && now - hit.atMs < ttlMs) return hit.items
        val fresh = safely(null) { fetch() }
        if (fresh != null) {
            cacheLock.withLock { cache[key] = Timed(TraktPlatformClock.nowEpochMs(), fresh) }
            return fresh
        }
        return hit?.items.orEmpty()
    }

    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The type only: messages can carry URLs.
            log.w { "personal row failed: ${error::class.simpleName}" }
            fallback
        }

    /**
     * GET from Trakt with the account's headers (token, client id, API version). On 429 waits
     * Retry-After (at most 10 s) and tries once more. Logs only [label] and the status.
     */
    private suspend fun traktGet(headers: Map<String, String>, pathAndQuery: String, label: String): String? {
        val url = "$TRAKT_API/$pathAndQuery"
        repeat(2) { attempt ->
            val response = httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "application/json") + headers,
                body = "",
            )
            when {
                response.status in 200..299 -> return response.body.takeIf { it.isNotBlank() }
                response.status == 429 && attempt == 0 -> {
                    val waitSeconds = (response.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1L, 10L)
                    delay(waitSeconds * 1000)
                }
                else -> {
                    log.w { "trakt $label: HTTP ${response.status}" }
                    return null
                }
            }
        }
        return null
    }

    /** GET from TMDB with the app's effective key. Logs only the status, never the URL (it has the key). */
    private suspend inline fun <reified T> tmdbGet(endpoint: String, query: Map<String, String>): T? {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf { it.isNotBlank() } ?: return null
        val response = httpRequestRaw(
            method = "GET",
            url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query),
            headers = mapOf("Accept" to "application/json"),
            body = "",
        )
        if (response.status !in 200..299) {
            log.w { "tmdb ${endpoint.substringBefore('/')}: HTTP ${response.status}" }
            return null
        }
        return runCatching { json.decodeFromString<T>(response.body) }.getOrNull()
    }

    private fun RawHttpResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.substringBefore(',')?.trim()

    private fun today(): LocalDate =
        Instant.fromEpochMilliseconds(TraktPlatformClock.nowEpochMs())
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date

    private val TotalEclipseBrowseKind.contentType: String
        get() = if (this == TotalEclipseBrowseKind.Movies) "movie" else "series"

    private val TotalEclipseBrowseKind.traktPath: String
        get() = if (this == TotalEclipseBrowseKind.Movies) "movies" else "shows"

    private fun TraktMediaDto.toPreview(type: String): MetaPreview? {
        val label = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val contentId = normalizeTraktContentId(ids).takeIf { it.isNotBlank() } ?: return null
        val date = released ?: firstAired
        return MetaPreview(
            id = contentId,
            type = type,
            name = label,
            poster = images.traktBestPosterUrl(),
            banner = images.traktBestBackdropUrl(),
            logo = images.traktBestLogoUrl(),
            posterShape = PosterShape.Poster,
            description = overview?.trim()?.takeIf { it.isNotEmpty() },
            releaseInfo = year?.toString() ?: date?.take(4),
            rawReleaseDate = date,
            imdbRating = rating?.let { ((it * 10).roundToInt() / 10.0).toString() },
            genres = genres.orEmpty(),
        )
    }

    private fun TmdbListItemDto.toPreview(type: String, imdbId: String?, day: String): MetaPreview? {
        val label = (title ?: name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return MetaPreview(
            id = imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: "tmdb:$id",
            type = type,
            name = label,
            poster = posterPath?.let { "${TMDB_IMAGES}w500$it" },
            banner = backdropPath?.let { "${TMDB_IMAGES}w1280$it" },
            posterShape = PosterShape.Poster,
            description = overview?.trim()?.takeIf { it.isNotEmpty() },
            // The watch-at-home day (HomeTV's "COMING DEC 5" badge reads it).
            releaseInfo = day.take(4),
            rawReleaseDate = day,
            popularity = popularity,
        )
    }

    // endregion

    // region DTOs

    @Serializable
    private data class TraktMediaDto(
        val title: String? = null,
        val year: Int? = null,
        val ids: TraktExternalIds? = null,
        val overview: String? = null,
        val released: String? = null,
        @SerialName("first_aired") val firstAired: String? = null,
        val rating: Double? = null,
        val genres: List<String>? = null,
        val images: TraktImagesDto? = null,
    )

    @Serializable
    private data class WeeklyDto(
        val movie: TraktMediaDto? = null,
        val show: TraktMediaDto? = null,
    )

    @Serializable
    private data class CalendarShowDto(
        @SerialName("first_aired") val firstAired: String? = null,
        val episode: CalendarEpisodeDto? = null,
        val show: TraktMediaDto? = null,
    )

    @Serializable
    private data class CalendarEpisodeDto(
        val season: Int? = null,
        val number: Int? = null,
    )

    @Serializable
    private data class TmdbPagedDto(
        val results: List<TmdbListItemDto> = emptyList(),
    )

    @Serializable
    private data class TmdbListItemDto(
        val id: Int = 0,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("backdrop_path") val backdropPath: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        val popularity: Double? = null,
    )

    @Serializable
    private data class TmdbMovieDetailsDto(
        @SerialName("imdb_id") val imdbId: String? = null,
        @SerialName("release_dates") val releaseDates: TmdbReleaseDatesDto? = null,
    )

    @Serializable
    private data class TmdbReleaseDatesDto(
        val results: List<TmdbReleaseCountryDto> = emptyList(),
    )

    @Serializable
    private data class TmdbReleaseCountryDto(
        @SerialName("iso_3166_1") val country: String? = null,
        @SerialName("release_dates") val releaseDates: List<TmdbReleaseDateDto>? = null,
    )

    @Serializable
    private data class TmdbReleaseDateDto(
        val type: Int? = null,
        @SerialName("release_date") val releaseDate: String? = null,
    )

    // endregion
}

/**
 * The rows as Home sections: (pinned, personal). Pinned: Watchlist, then New This Week on Shows
 * (after the phone's own Continue Watching / Upcoming). Personal, in HomeTVRowOrder's order:
 * Top 10, Because you finished, Recommended, Because you're watching, Coming Soon — the first
 * catalog goes between Top 10 and the rest.
 *
 * These aren't catalogs: hasMore is false and availableItemCount is the row's size. The target is
 * CatalogTarget.Library (never paginates or hits the network; not saved in Home snapshots).
 */
internal fun TotalEclipsePersonalRowsState.toSections(
    kind: TotalEclipseBrowseKind,
): Pair<List<HomeCatalogSection>, List<HomeCatalogSection>> {
    val contentType = if (kind == TotalEclipseBrowseKind.Movies) "movie" else "series"
    val suffix = kind.name.lowercase()

    fun row(id: String, title: String, items: List<MetaPreview>, librarySection: String? = null): HomeCatalogSection? {
        val shown = items.filter { kind.includesType(it.type) }
        if (shown.isEmpty()) return null
        val key = "totaleclipse_${id}_$suffix"
        return HomeCatalogSection(
            key = key,
            title = title,
            subtitle = "",
            addonName = "",
            target = CatalogTarget.Library(contentType = contentType, sectionType = librarySection ?: key),
            items = shown,
            availableItemCount = shown.size,
            hasMore = false,
        )
    }

    val pinned = listOfNotNull(
        // HomeTVMyList.title. "trakt:watchlist" so a View all (rows over Home's preview limit)
        // lands on the Library's watchlist rather than nothing — a guess at the section type.
        row("my_list", "Watchlist", myList, librarySection = "trakt:watchlist"),
        if (kind == TotalEclipseBrowseKind.Shows) row("new_this_week", "New This Week", newThisWeek) else null,
    )
    val personal = listOfNotNull(
        row("top10", "Top 10 This Week", top10),
        becauseFinished?.let { (title, items) -> row("because_finished", title, items) },
        row("recommended", "Recommended for You", recommended),
        becauseWatching?.let { (title, items) -> row("because_watching", title, items) },
        row("coming_soon", "Coming Soon", comingSoon),
    )
    return pinned to personal
}
