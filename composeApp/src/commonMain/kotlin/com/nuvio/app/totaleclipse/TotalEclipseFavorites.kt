package com.nuvio.app.totaleclipse

import androidx.compose.runtime.compositionLocalOf
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * Total Eclipse: Trakt favourites (♥), as on the Apple TVs (HomeTV HomeTVTraktMarks):
 * GET /sync/favorites/{movies|shows}, POST /sync/favorites and /sync/favorites/remove.
 * A tap shows the new state at once and puts it back if Trakt refuses. Empty without Trakt.
 * Logs show only the action and HTTP status.
 */
internal object TotalEclipseFavorites {
    private const val TAG = "TotalEclipse/Favorites"
    private const val API = "https://api.trakt.tv"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    /** Favourite keys: "movie:tt…" / "series:tt…" (IMDb ids, as the details pages use). */
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    private var loadedForProfile: Int? = null
    private var loadedAt: TimeSource.Monotonic.ValueTimeMark? = null

    @Serializable private data class Ids(val imdb: String? = null, val trakt: Int? = null)
    @Serializable private data class Titled(val ids: Ids = Ids())
    @Serializable private data class Entry(val movie: Titled? = null, val show: Titled? = null)

    fun key(type: String, id: String): String =
        "${if (type == "movie") "movie" else "series"}:${id.substringBefore(':').trim()}"

    /** Loads the profile's favourites at most every 10 minutes (or after a profile switch). */
    fun ensureLoaded() {
        val profile = ProfileRepository.activeProfileId
        val fresh = loadedForProfile == profile &&
            (loadedAt?.elapsedNow()?.inWholeMinutes ?: Long.MAX_VALUE) < 10
        if (fresh) return
        loadedForProfile = profile
        loadedAt = TimeSource.Monotonic.markNow()
        scope.launch {
            val headers = TraktAuthRepository.authorizedHeaders() ?: run { _keys.value = emptySet(); return@launch }
            val found = mutableSetOf<String>()
            for ((path, type) in listOf("movies" to "movie", "shows" to "series")) {
                val response = runCatching {
                    httpRequestRaw("GET", "$API/sync/favorites/$path", mapOf("Accept" to "application/json") + headers, "")
                }.getOrNull() ?: return@launch
                if (response.status !in 200..299) {
                    InAppLogger.warn(TAG, "load $path: HTTP ${response.status}")
                    return@launch
                }
                runCatching { json.decodeFromString<List<Entry>>(response.body) }.getOrDefault(emptyList())
                    .mapNotNull { (it.movie ?: it.show)?.ids?.imdb }
                    .forEach { found += "$type:$it" }
            }
            if (ProfileRepository.activeProfileId == profile) _keys.value = found
        }
    }

    fun isFavorite(type: String, id: String): Boolean = key(type, id) in _keys.value

    /** Adds or removes the ♥ on Trakt. Only IMDb ids (tt…) can be sent; others are ignored. */
    fun toggle(type: String, id: String) {
        val imdb = id.substringBefore(':').trim().takeIf { it.startsWith("tt") } ?: return
        val key = key(type, imdb)
        val adding = key !in _keys.value
        _keys.value = if (adding) _keys.value + key else _keys.value - key
        scope.launch {
            val headers = TraktAuthRepository.authorizedHeaders()
            val group = if (type == "movie") "movies" else "shows"
            val body = """{"$group":[{"ids":{"imdb":"$imdb"}}]}"""
            val ok = headers != null && runCatching {
                httpRequestRaw(
                    method = "POST",
                    url = "$API/sync/favorites" + if (adding) "" else "/remove",
                    headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json") + headers,
                    body = body,
                ).also { if (it.status !in 200..299) InAppLogger.warn(TAG, "${if (adding) "add" else "remove"}: HTTP ${it.status}") }
                    .status in 200..299
            }.getOrDefault(false)
            if (!ok) {
                _keys.value = if (adding) _keys.value - key else _keys.value + key   // undo
            } else {
                // The Library's Favorites tab reads the Trakt library; refresh it now.
                runCatching { com.nuvio.app.features.trakt.TraktLibraryRepository.refreshNow() }
            }
        }
    }
}

/** The title a details page shows, for its ♥ button: (type, id). */
internal val LocalTotalEclipseDetailTitle = compositionLocalOf<Pair<String, String>?> { null }
