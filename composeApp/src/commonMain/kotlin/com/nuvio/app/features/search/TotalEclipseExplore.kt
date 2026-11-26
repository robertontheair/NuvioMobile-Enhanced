package com.nuvio.app.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cyclone
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.PosterGridSkeletonRow
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Total Eclipse: the Apple TVs' Explore tiles on Search (HomeTVDiscoveryTiles.swift) — themed
 * lists from TMDB. A tile opens its titles in place; Collections opens a franchise's films.
 */
internal data class TotalEclipseExploreTile(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val colors: List<Color>,
    val source: Source,
) {
    sealed interface Source {
        /** TMDB discover; [keywords] are names (any of them). */
        data class Discover(val media: String, val params: Map<String, String>, val keywords: List<String> = emptyList()) : Source
        data object BestPicture : Source
        data object Collections : Source
    }
}

private fun rgb(r: Double, g: Double, b: Double) = Color(r.toFloat(), g.toFloat(), b.toFloat())

/** The tiles for [todayIso] ("yyyy-MM-dd"); Holiday Movies only in November and December. */
internal fun totalEclipseExploreTiles(todayIso: String, recentIso: String): List<TotalEclipseExploreTile> = buildList {
    val month = todayIso.substring(5, 7).toIntOrNull()
    if (month == 11 || month == 12) {
        add(TotalEclipseExploreTile("holiday", "Holiday Movies", Icons.Rounded.AcUnit, listOf(rgb(0.70, 0.10, 0.15), rgb(0.10, 0.40, 0.20)),
            TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "100", "without_genres" to "27"), listOf("christmas"))))
    }
    add(TotalEclipseExploreTile("boxoffice", "Top Box Office", Icons.Rounded.ConfirmationNumber, listOf(rgb(0.85, 0.55, 0.10), rgb(0.55, 0.15, 0.10)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "revenue.desc", "primary_release_date.gte" to recentIso,
            "primary_release_date.lte" to todayIso, "with_release_type" to "2|3"))))
    add(TotalEclipseExploreTile("bestpicture", "Best Picture Winners", Icons.Rounded.EmojiEvents, listOf(rgb(0.80, 0.65, 0.25), rgb(0.35, 0.25, 0.05)),
        TotalEclipseExploreTile.Source.BestPicture))
    add(TotalEclipseExploreTile("collections", "Collections", Icons.Rounded.Layers, listOf(rgb(0.20, 0.35, 0.55), rgb(0.08, 0.10, 0.20)),
        TotalEclipseExploreTile.Source.Collections))
    add(TotalEclipseExploreTile("superheroes", "Superheroes", Icons.Rounded.Bolt, listOf(rgb(0.15, 0.30, 0.80), rgb(0.60, 0.10, 0.20)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "300"), listOf("superhero"))))
    add(TotalEclipseExploreTile("fantasy", "Epic Fantasy", Icons.Rounded.AutoAwesome, listOf(rgb(0.35, 0.15, 0.60), rgb(0.10, 0.35, 0.45)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("with_genres" to "14", "sort_by" to "popularity.desc", "vote_count.gte" to "1500"))))
    add(TotalEclipseExploreTile("twists", "Mind Twists", Icons.Rounded.Cyclone, listOf(rgb(0.20, 0.20, 0.25), rgb(0.45, 0.10, 0.45)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "300"), listOf("twist ending", "plot twist"))))
    add(TotalEclipseExploreTile("heist", "Heists", Icons.Rounded.LockOpen, listOf(rgb(0.10, 0.45, 0.35), rgb(0.05, 0.15, 0.20)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "300"), listOf("heist"))))
    add(TotalEclipseExploreTile("timetravel", "Time Travel", Icons.Rounded.History, listOf(rgb(0.10, 0.50, 0.70), rgb(0.20, 0.10, 0.40)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "300"), listOf("time travel"))))
    add(TotalEclipseExploreTile("truestory", "True Stories", Icons.Rounded.LibraryBooks, listOf(rgb(0.55, 0.35, 0.20), rgb(0.20, 0.15, 0.10)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "popularity.desc", "vote_count.gte" to "500"), listOf("based on true story"))))
    add(TotalEclipseExploreTile("romcom", "Rom-Com", Icons.Rounded.Favorite, listOf(rgb(0.90, 0.35, 0.55), rgb(0.55, 0.15, 0.35)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("with_genres" to "10749,35", "sort_by" to "popularity.desc", "vote_count.gte" to "300"))))
    add(TotalEclipseExploreTile("horror", "Horror", Icons.Rounded.Nightlight, listOf(rgb(0.35, 0.02, 0.05), rgb(0.05, 0.05, 0.05)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("with_genres" to "27", "sort_by" to "popularity.desc", "vote_count.gte" to "500"))))
    add(TotalEclipseExploreTile("classics", "All-Time Classics", Icons.Rounded.Movie, listOf(rgb(0.45, 0.45, 0.45), rgb(0.12, 0.12, 0.12)),
        TotalEclipseExploreTile.Source.Discover("movie", mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "2000", "primary_release_date.lte" to "1979-12-31"))))
    add(TotalEclipseExploreTile("acclaimedtv", "Acclaimed TV", Icons.Rounded.Tv, listOf(rgb(0.15, 0.50, 0.30), rgb(0.05, 0.20, 0.35)),
        TotalEclipseExploreTile.Source.Discover("tv", mapOf("sort_by" to "vote_average.desc", "vote_count.gte" to "2000"))))
}

/** What's open under Explore: a tile's titles, or one collection's films. */
internal data class TotalEclipseExploreState(
    val tile: TotalEclipseExploreTile? = null,
    val collection: MetaPreview? = null,
    val items: List<MetaPreview> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val failed: Boolean = false,
)

internal object TotalEclipseExplore {
    const val COLLECTION_PREFIX = "te-collection:"
    private const val PAGES_PER_BATCH = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(TotalEclipseExploreState())
    val state: StateFlow<TotalEclipseExploreState> = _state.asStateFlow()
    private var job: Job? = null
    private var batch = 0
    private var tileItems: List<MetaPreview> = emptyList()
    private val keywordIds = HashMap<String, String?>()
    private val fixedLists = HashMap<String, List<MetaPreview>>()

    fun open(tile: TotalEclipseExploreTile) {
        job?.cancel()
        batch = 0
        tileItems = emptyList()
        _state.value = TotalEclipseExploreState(tile = tile, loading = true)
        job = scope.launch { loadMore(tile) }
    }

    fun loadMore() {
        val s = _state.value
        val tile = s.tile ?: return
        if (s.loading || !s.hasMore || s.collection != null) return
        _state.value = s.copy(loading = true)
        job = scope.launch { loadMore(tile) }
    }

    fun openCollection(card: MetaPreview) {
        val id = card.id.removePrefix(COLLECTION_PREFIX).toIntOrNull() ?: return
        job?.cancel()
        _state.value = _state.value.copy(collection = card, items = emptyList(), loading = true, hasMore = false, failed = false)
        job = scope.launch {
            val films = runCatching { collectionFilms(id) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            if (_state.value.collection == card) {
                _state.value = _state.value.copy(items = films, loading = false, failed = films.isEmpty())
            }
        }
    }

    /** Back: from a collection to the Collections tile, else out of the tile. */
    fun back() {
        job?.cancel()
        val s = _state.value
        _state.value = if (s.collection != null) {
            s.copy(collection = null, items = tileItems, loading = false, failed = false)
        } else {
            TotalEclipseExploreState()
        }
    }

    private suspend fun loadMore(tile: TotalEclipseExploreTile) {
        val next = batch + 1
        val (found, more) = try {
            load(tile, next)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList<MetaPreview>() to false
        }
        if (_state.value.tile != tile || _state.value.collection != null) return
        val shown = tileItems.map { it.id }.toSet()
        tileItems = tileItems + found.filter { it.id !in shown }
        batch = next
        _state.value = _state.value.copy(items = tileItems, loading = false, hasMore = more, failed = tileItems.isEmpty())
    }

    private suspend fun load(tile: TotalEclipseExploreTile, batch: Int): Pair<List<MetaPreview>, Boolean> =
        when (val source = tile.source) {
            is TotalEclipseExploreTile.Source.Discover -> {
                val params = source.params.toMutableMap()
                if (source.keywords.isNotEmpty()) {
                    val ids = source.keywords.mapNotNull { keywordId(it) }
                    if (ids.isEmpty()) return emptyList<MetaPreview>() to false
                    params["with_keywords"] = ids.joinToString("|")
                }
                val first = (batch - 1) * PAGES_PER_BATCH + 1
                val pages = (first until first + PAGES_PER_BATCH).map { page ->
                    get<DiscoverPage>("discover/${source.media}", params + ("page" to page.toString()))
                }
                val items = pages.flatMap { it?.results.orEmpty() }.mapNotNull { it.toPreview(source.media) }
                val total = pages.firstNotNullOfOrNull { it?.totalPages } ?: 0
                items to (first + PAGES_PER_BATCH - 1 < total)
            }
            TotalEclipseExploreTile.Source.BestPicture ->
                (if (batch == 1) fixedList("bestpicture") { bestPicture() } else emptyList()) to false
            TotalEclipseExploreTile.Source.Collections ->
                (if (batch == 1) fixedList("collections") { collections() } else emptyList()) to false
        }

    private suspend fun fixedList(key: String, load: suspend () -> List<MetaPreview>): List<MetaPreview> =
        fixedLists[key] ?: load().also { if (it.isNotEmpty()) fixedLists[key] = it }

    private suspend fun keywordId(name: String): String? {
        if (keywordIds.containsKey(name)) return keywordIds[name]
        val page = get<NamedPage>("search/keyword", mapOf("query" to name.encodeQuery())) ?: return null
        val id = (page.results.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: page.results.firstOrNull())?.id?.toString()
        keywordIds[name] = id
        return id
    }

    private suspend fun bestPicture(): List<MetaPreview> = lookUp(BEST_PICTURE) { (title, year) ->
        get<DiscoverPage>("search/movie", mapOf("query" to title.encodeQuery(), "year" to year.toString()))
            ?.results?.firstOrNull()?.toPreview("movie")
    }

    private suspend fun collections(): List<MetaPreview> = lookUp(COLLECTION_NAMES) { name ->
        val hit = get<NamedPage>("search/collection", mapOf("query" to name.encodeQuery()))?.results?.firstOrNull()
            ?: return@lookUp null
        MetaPreview(
            id = COLLECTION_PREFIX + hit.id,
            type = "collection",
            name = hit.name ?: name,
            poster = hit.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: return@lookUp null,
        )
    }.distinctBy { it.id }

    private suspend fun collectionFilms(id: Int): List<MetaPreview> =
        get<CollectionDetails>("collection/$id", emptyMap())?.parts.orEmpty()
            .sortedBy { it.releaseDate?.takeIf(String::isNotBlank) ?: "9999" }
            .mapNotNull { it.toPreview("movie") }

    /** Looks each one up, a few at a time, keeping the list's order. */
    private suspend fun <T> lookUp(list: List<T>, find: suspend (T) -> MetaPreview?): List<MetaPreview> = coroutineScope {
        val limiter = Semaphore(6)
        list.map { entry -> async { limiter.withPermit { runCatching { find(entry) }.getOrNull() } } }.awaitAll().filterNotNull()
    }

    private suspend inline fun <reified T> get(endpoint: String, query: Map<String, String>): T? {
        val key = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return null
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = key, query = (query + ("language" to "en-US")).mapValues { it.value.replace("|", "%7C") })
        return runCatching { json.decodeFromString<T>(httpGetText(url)) }.getOrNull()
    }

    private fun String.encodeQuery(): String = buildString {
        for (byte in this@encodeQuery.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && byte >= 0 || c in "-_.~") append(c)
            else append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }

    @Serializable
    private data class DiscoverPage(
        val results: List<Result> = emptyList(),
        @SerialName("total_pages") val totalPages: Int? = null,
    )

    @Serializable
    private data class Result(
        val id: Int,
        val title: String? = null,
        val name: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("backdrop_path") val backdropPath: String? = null,
        val overview: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
    ) {
        fun toPreview(media: String): MetaPreview? {
            val poster = posterPath?.takeIf(String::isNotBlank) ?: return null
            return MetaPreview(
                id = "tmdb:$id",
                type = if (media == "tv") "series" else "movie",
                name = title ?: name ?: return null,
                poster = "https://image.tmdb.org/t/p/w500$poster",
                banner = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" },
                description = overview?.takeIf(String::isNotBlank),
                releaseInfo = (releaseDate ?: firstAirDate)?.take(4)?.takeIf(String::isNotBlank),
            )
        }
    }

    @Serializable
    private data class NamedPage(val results: List<Named> = emptyList())

    @Serializable
    private data class Named(val id: Int, val name: String? = null, @SerialName("poster_path") val posterPath: String? = null)

    @Serializable
    private data class CollectionDetails(val parts: List<Result> = emptyList())

    /** Best Picture winners, newest first (by the film's year), as on the TVs. */
    private val BEST_PICTURE: List<Pair<String, Int>> = listOf(
        "One Battle After Another" to 2025, "Anora" to 2024, "Oppenheimer" to 2023,
        "Everything Everywhere All at Once" to 2022, "CODA" to 2021, "Nomadland" to 2020, "Parasite" to 2019,
        "Green Book" to 2018, "The Shape of Water" to 2017, "Moonlight" to 2016, "Spotlight" to 2015,
        "Birdman or (The Unexpected Virtue of Ignorance)" to 2014, "12 Years a Slave" to 2013, "Argo" to 2012,
        "The Artist" to 2011, "The King's Speech" to 2010, "The Hurt Locker" to 2008, "Slumdog Millionaire" to 2008,
        "No Country for Old Men" to 2007, "The Departed" to 2006, "Crash" to 2005, "Million Dollar Baby" to 2004,
        "The Lord of the Rings: The Return of the King" to 2003, "Chicago" to 2002, "A Beautiful Mind" to 2001,
        "Gladiator" to 2000, "American Beauty" to 1999, "Shakespeare in Love" to 1998, "Titanic" to 1997,
        "The English Patient" to 1996, "Braveheart" to 1995, "Forrest Gump" to 1994, "Schindler's List" to 1993,
        "Unforgiven" to 1992, "The Silence of the Lambs" to 1991, "Dances with Wolves" to 1990,
        "Driving Miss Daisy" to 1989, "Rain Man" to 1988, "The Last Emperor" to 1987, "Platoon" to 1986,
        "Out of Africa" to 1985, "Amadeus" to 1984, "Terms of Endearment" to 1983, "Gandhi" to 1982,
        "Chariots of Fire" to 1981, "Ordinary People" to 1980, "Kramer vs. Kramer" to 1979, "The Deer Hunter" to 1978,
        "Annie Hall" to 1977, "Rocky" to 1976, "One Flew Over the Cuckoo's Nest" to 1975,
        "The Godfather Part II" to 1974, "The Sting" to 1973, "The Godfather" to 1972,
    )

    /** Franchises for the Collections tile, roughly most-watched first (the TVs' list). */
    private val COLLECTION_NAMES = listOf(
        "The Lord of the Rings", "The Hobbit", "Harry Potter", "Fantastic Beasts", "Star Wars",
        "Star Trek: The Original Series", "Star Trek: The Next Generation", "Star Trek: Alternate Reality",
        "Indiana Jones", "Jurassic Park", "Back to the Future", "The Matrix", "The Dark Knight",
        "The Avengers", "Iron Man", "Captain America", "Thor", "Guardians of the Galaxy",
        "Spider-Man (Avengers)", "Spider-Man", "The Amazing Spider-Man", "X-Men", "Deadpool",
        "James Bond", "Mission: Impossible", "The Fast and the Furious", "John Wick", "The Bourne",
        "Pirates of the Caribbean", "Toy Story", "Shrek", "Despicable Me", "Ice Age", "Kung Fu Panda",
        "How to Train Your Dragon", "Frozen", "The Incredibles", "Cars", "Finding Nemo",
        "The Hunger Games", "Twilight", "Dune", "Avatar", "Alien", "Predator", "The Terminator",
        "Planet of the Apes", "Mad Max", "Rocky", "Creed", "Die Hard", "Lethal Weapon", "Home Alone",
        "Ocean's", "The Godfather", "Top Gun", "Men in Black", "Ghostbusters", "Transformers",
        "Jaws", "Scream", "Halloween", "The Conjuring", "Paddington",
    )
}

/** The Explore row (Search, before you type). */
@Composable
internal fun TotalEclipseExploreRow(tiles: List<TotalEclipseExploreTile>, onOpen: (TotalEclipseExploreTile) -> Unit) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Text(
            text = "Explore",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(tiles, key = { it.id }) { tile ->
                Box(
                    modifier = Modifier
                        .size(width = 168.dp, height = 88.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.linearGradient(tile.colors))
                        .clickable { onOpen(tile) },
                ) {
                    Icon(
                        imageVector = tile.icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp),
                    )
                    Text(
                        text = tile.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }
            }
        }
    }
}

/** An open tile (or collection): a back row and its posters, more as you reach the end. */
internal fun LazyListScope.totalEclipseExploreContent(
    state: TotalEclipseExploreState,
    columns: Int,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    item(key = "te_explore_header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { TotalEclipseExplore.back() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            Text(
                text = state.collection?.name ?: state.tile?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    val items = state.items
    when {
        items.isEmpty() && state.loading -> items(2) {
            PosterGridSkeletonRow(columns = columns, modifier = Modifier.padding(horizontal = 16.dp))
        }
        items.isEmpty() -> item(key = "te_explore_empty") {
            Text(
                text = if (TmdbSettingsRepository.effectiveApiKey().isBlank()) {
                    "Explore needs a TMDB key (it comes from your Apple TVs)."
                } else {
                    "Nothing came back from TMDB. Try again in a moment."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        else -> {
            val click: (MetaPreview) -> Unit = { preview ->
                if (preview.id.startsWith(TotalEclipseExplore.COLLECTION_PREFIX)) TotalEclipseExplore.openCollection(preview)
                else onPosterClick?.invoke(preview)
            }
            val rows = (items.size + columns - 1) / columns
            items(count = rows, key = { "te_explore_row:$it" }) { rowIndex ->
                val first = rowIndex * columns
                PosterGridRow(
                    items = items.subList(first, minOf(first + columns, items.size)),
                    columns = columns,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    watchedKeys = watchedKeys,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    onPosterClick = click,
                    onPosterLongClick = onPosterLongClick?.let { long ->
                        { preview: MetaPreview -> if (!preview.id.startsWith(TotalEclipseExplore.COLLECTION_PREFIX)) long(preview) }
                    },
                )
                if (rowIndex >= rows - 2 && state.hasMore) {
                    LaunchedEffect(items.size) { TotalEclipseExplore.loadMore() }
                }
            }
            if (state.loading) {
                item(key = "te_explore_more") {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Loading more…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
