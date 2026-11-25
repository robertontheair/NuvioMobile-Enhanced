package com.nuvio.app.totaleclipse

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktExternalIds
import com.nuvio.app.features.trakt.TraktScrobbleItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Total Eclipse: watched titles Trakt couldn't take, kept on the phone and sent later, as on the
 * Apple TVs. Per profile; one request every 1.2 s; given up (parked, kept for the log) after
 * [MAX_TRIES] or when Trakt refuses the entry itself. Before adding a play Trakt may have counted
 * after all (the answer was lost), its history around that time is checked.
 */
internal object TotalEclipseTraktOutbox {
    private const val TAG = "TotalEclipse/Outbox"
    private const val API = "https://api.trakt.tv"
    private const val MAX_TRIES = 8
    private const val WATCHED_PERCENT = 80f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Entry(
        val body: String,
        val watchedAt: String? = null,
        val imdb: String? = null,
        val tmdb: Int? = null,
        val trakt: Int? = null,
        val season: Int? = null,
        val number: Int? = null,
        val tries: Int = 0,
        val parked: Boolean = false,
    )

    private fun key(profile: Int) = "totaleclipse.trakt.outbox.$profile"

    private fun load(profile: Int): List<Entry> = HouseholdCloudStore.loadLocal(key(profile))
        ?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() }.orEmpty()

    private fun save(profile: Int, entries: List<Entry>) =
        HouseholdCloudStore.saveLocal(key(profile), json.encodeToString(entries.takeLast(200)))

    /** A finished title whose scrobble didn't reach Trakt (not finished: nothing to add). */
    suspend fun addFinished(profile: Int, item: TraktScrobbleItem, percent: Float) {
        if (percent < WATCHED_PERCENT) return
        val at = Clock.System.now().toString()
        fun ids(i: TraktExternalIds) = buildJsonObject {
            i.trakt?.let { put("trakt", it) }
            i.imdb?.let { put("imdb", it) }
            i.tmdb?.let { put("tmdb", it) }
        }
        val entry = when (item) {
            is TraktScrobbleItem.Movie -> Entry(
                body = JsonObject(
                    mapOf("movies" to JsonArray(listOf(buildJsonObject { put("ids", ids(item.ids)); put("watched_at", at) }))),
                ).toString(),
                watchedAt = at, imdb = item.ids.imdb, tmdb = item.ids.tmdb, trakt = item.ids.trakt,
            )
            is TraktScrobbleItem.Episode -> Entry(
                body = JsonObject(
                    mapOf(
                        "shows" to JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("ids", ids(item.showIds))
                                    put(
                                        "seasons",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("number", item.season)
                                                    put(
                                                        "episodes",
                                                        JsonArray(listOf(buildJsonObject { put("number", item.number); put("watched_at", at) })),
                                                    )
                                                },
                                            ),
                                        ),
                                    )
                                },
                            ),
                        ),
                    ),
                ).toString(),
                watchedAt = at, imdb = item.showIds.imdb, tmdb = item.showIds.tmdb, trakt = item.showIds.trakt,
                season = item.season, number = item.number,
            )
        }
        if (entry.imdb == null && entry.tmdb == null && entry.trakt == null) return
        mutex.withLock { save(profile, load(profile) + entry) }
        InAppLogger.info(TAG, "kept a finished title for later (${load(profile).count { !it.parked }} waiting)")
    }

    /** Sends what's waiting for the active profile. */
    fun flush() {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                flushLocked(ProfileRepository.activeProfileId)
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun flushLocked(profile: Int) {
        val waiting = load(profile).filterNot { it.parked }
        if (waiting.isEmpty()) return
        val headers = TraktAuthRepository.authorizedHeaders()?.let {
            mapOf("Accept" to "application/json", "Content-Type" to "application/json") + it
        } ?: return
        for (entry in waiting) {
            if (ProfileRepository.activeProfileId != profile) return
            val known = alreadyInHistory(entry, headers) ?: return // can't reach Trakt: try another time
            val status = if (known) 200 else runCatching {
                httpRequestRaw("POST", "$API/sync/history", headers, entry.body).status
            }.getOrNull() ?: return
            val all = load(profile)
            when {
                status in 200..299 -> save(profile, all - entry)
                status == 401 || status == 429 || status >= 500 -> {
                    val next = entry.copy(tries = entry.tries + 1)
                    save(profile, all.map { if (it == entry) next.copy(parked = next.tries >= MAX_TRIES) else it })
                    InAppLogger.warn(TAG, "Trakt HTTP $status; will try again")
                    return
                }
                else -> {
                    save(profile, all.map { if (it == entry) it.copy(parked = true) else it })
                    InAppLogger.warn(TAG, "Trakt refused an entry (HTTP $status); parked")
                }
            }
            delay(1_200)
        }
        InAppLogger.info(TAG, "sent what was waiting")
    }

    /** Null when Trakt can't be asked. */
    private suspend fun alreadyInHistory(entry: Entry, headers: Map<String, String>): Boolean? {
        val at = entry.watchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        val kind = if (entry.season == null) "movies" else "episodes"
        val response = runCatching {
            httpRequestRaw(
                "GET",
                "$API/sync/history/$kind?start_at=${at - 30.minutes}&end_at=${at + 30.minutes}&limit=100",
                headers, "",
            )
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        return runCatching {
            json.parseToJsonElement(response.body).jsonArray.any { row ->
                val obj = row.jsonObject
                val title = (if (entry.season == null) obj["movie"] else obj["show"])?.jsonObject ?: return@any false
                val ids = title["ids"]?.jsonObject ?: return@any false
                fun same(name: String, value: Any?) = value != null && (ids[name] as? JsonPrimitive)?.content == value.toString()
                val sameTitle = same("imdb", entry.imdb) || same("tmdb", entry.tmdb) || same("trakt", entry.trakt)
                if (entry.season == null) return@any sameTitle
                val episode = obj["episode"]?.jsonObject ?: return@any false
                sameTitle && episode["season"]?.jsonPrimitive?.content == "${entry.season}" &&
                    episode["number"]?.jsonPrimitive?.content == "${entry.number}"
            }
        }.getOrNull()
    }
}
