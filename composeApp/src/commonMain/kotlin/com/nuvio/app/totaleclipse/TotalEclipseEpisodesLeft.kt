package com.nuvio.app.totaleclipse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.storage.AppFileCache
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemTypeAliases
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Total Eclipse: the Apple TVs' poster badge (HomeTV CatalogWatchedBadgeState): a check when
 * every aired episode of a show is watched, else the number of aired episodes still to watch.
 * Specials and unaired episodes don't count.
 *
 * As on the TVs (CatalogWatchedMetadataCache), only shows you've started cost anything: their
 * episode list is loaded from the add-ons once, at most four at a time, and kept in memory and
 * on disk (refreshed after a day, since new episodes air).
 */
internal object TotalEclipseEpisodeGuideCache {
    @Serializable
    data class Episode(val s: Int, val e: Int, val r: String? = null)

    @Serializable
    private data class Record(val at: Long, val episodes: List<Episode>)

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val limiter = Semaphore(4)
    private val lock = Mutex()
    private val memory = HashMap<String, Record>()
    private val inFlight = HashMap<String, Deferred<List<Episode>?>>()

    /** The show's regular episodes (season ≥ 1) with their release dates, or null if unknown. */
    suspend fun episodes(type: String, id: String): List<Episode>? = coroutineScope {
        val key = "$type:$id"
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val job = lock.withLock {
            memory[key]?.takeIf { now - it.at < MAX_AGE_MS }?.let { return@coroutineScope it.episodes }
            inFlight[key] ?: async { load(type, id, key, now) }.also { inFlight[key] = it }
        }
        try {
            job.await()
        } finally {
            lock.withLock { if (inFlight[key] === job && job.isCompleted) inFlight.remove(key) }
        }
    }

    private suspend fun load(type: String, id: String, key: String, now: Long): List<Episode>? {
        val path = "totaleclipse/episodes/" + key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + ".json"
        val disk = runCatching { AppFileCache.read(path)?.let { json.decodeFromString<Record>(it) } }.getOrNull()
        if (disk != null && now - disk.at < MAX_AGE_MS) {
            lock.withLock { memory[key] = disk }
            return disk.episodes
        }
        val fetched = try {
            limiter.withPermit { MetaDetailsRepository.fetch(type = type, id = id, cacheResult = false) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val episodes = fetched?.videos
            ?.mapNotNull { v -> val s = v.season ?: return@mapNotNull null; val e = v.episode ?: return@mapNotNull null
                if (s >= 1 && e >= 1) Episode(s, e, v.released?.take(10)) else null }
            ?.distinctBy { it.s to it.e }
        if (episodes.isNullOrEmpty()) return disk?.episodes // keep yesterday's list over nothing
        val record = Record(now, episodes)
        lock.withLock { memory[key] = record }
        runCatching { AppFileCache.write(path, json.encodeToString(record)) }
        return episodes
    }
}

/**
 * For a show you've started: 0 when every aired episode is watched (the check), the number of
 * aired episodes left, or null (not a show, nothing watched yet, or its episode list unknown).
 */
@Composable
internal fun rememberTotalEclipseEpisodesLeft(item: MetaPreview): Int? {
    if (item.type.lowercase() !in seriesTypes) return null
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val keys = watched.watchedKeys
    val prefixes = remember(item.type, item.id) { watchedItemTypeAliases(item.type).map { "$it:${item.id}:" } }
    if (item.id !in startedShowIds(keys)) return null
    val left by produceState<Int?>(null, item.id, keys) {
        val episodes = TotalEclipseEpisodeGuideCache.episodes(item.type, item.id) ?: return@produceState
        value = totalEclipseEpisodesLeft(episodes, CurrentDateProvider.todayIsoDate()) { s, e ->
            prefixes.any { "$it$s:$e" in keys }
        }
    }
    return left
}

/** Aired episodes not yet watched; null when none of them is watched. */
internal fun totalEclipseEpisodesLeft(
    episodes: List<TotalEclipseEpisodeGuideCache.Episode>,
    todayIsoDate: String,
    isWatched: (season: Int, episode: Int) -> Boolean,
): Int? {
    val aired = episodes.filter { ep -> ep.r == null || ep.r <= todayIsoDate }
    if (aired.isEmpty()) return null
    val watchedCount = aired.count { isWatched(it.s, it.e) }
    if (watchedCount == 0) return null
    return aired.size - watchedCount
}

private val seriesTypes = setOf("series", "show", "tv", "tvshow", "anime")

// Shows with at least one watched episode, worked out once per change of the watched keys
// (shared by every poster instead of each one scanning the whole history).
private var startedFor: Set<String>? = null
private var startedIds: Set<String> = emptySet()

private fun startedShowIds(keys: Set<String>): Set<String> {
    if (startedFor === keys) return startedIds
    val ids = HashSet<String>()
    for (key in keys) {
        // "type:id:season:episode"; ids can contain ':' (e.g. "tmdb:1396").
        val parts = key.split(':')
        if (parts.size < 4 || parts.first().lowercase() !in seriesTypes) continue
        val season = parts[parts.size - 2].toIntOrNull() ?: continue
        val episode = parts.last().toIntOrNull() ?: continue
        if (season < 1 || episode < 1) continue
        ids += parts.subList(1, parts.size - 2).joinToString(":")
    }
    startedIds = ids
    startedFor = keys
    return ids
}
