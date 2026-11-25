package com.nuvio.app.totaleclipse

import com.nuvio.app.features.profiles.ProfilePinCrypto
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Total Eclipse: Live TV favourites and Recently Watched shared with the Apple TVs (HomeTV
 * HomeTVFavoritesSync / HomeTVRecentsSync), read-only as with the rest of the household sync.
 *
 * Channels use the TVs' portable key: "ch:" + SHA-256 of name, group and guide id (each
 * prefixed with its UTF-8 length). The phone keeps the same dated logs as the TVs:
 *  - favourites: key → [1 on / 0 off, seconds since 1970]; newest change wins, "on" on a tie;
 *  - recents: key → seconds since 1970 last watched; negative = removed then.
 * The TVs' logs (in their profile settings in iCloud) merge into the phone's; the phone's own
 * stars and removals stay on the phone (it never writes to the TVs' iCloud values).
 */
internal object TotalEclipseLiveSync {
    const val TV_FAVORITES_LOG = "hometv.iptv.favorites.log"
    const val TV_RECENTS_LOG = "hometv.iptv.recents.log"
    private const val LOG_LIMIT = 60
    private val json = Json { ignoreUnknownKeys = true }

    /** The TVs' channel key. */
    fun portableKey(name: String, group: String?, guideId: String?): String {
        val identity = listOf(name.trim(), group?.trim().orEmpty(), guideId?.trim().orEmpty())
            .joinToString("") { "${it.encodeToByteArray().size}:$it" }
        return "ch:" + ProfilePinCrypto.sha256Hex(identity)
    }

    /** Old phone ids → portable keys, filled while playlists parse (for the one-time move). */
    @kotlin.concurrent.Volatile var legacyIds: Map<String, String> = emptyMap()
        private set

    fun addLegacyIds(map: Map<String, String>) {
        if (map.isNotEmpty()) legacyIds = legacyIds + map
    }

    // Logs ------------------------------------------------------------------------------------

    private fun now(): Double = kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000.0
    private fun favKey() = "totaleclipse.live.favorites.log.${ProfileRepository.activeProfileId}"
    private fun recentKey() = "totaleclipse.live.recents.log.${ProfileRepository.activeProfileId}"

    fun parseFavorites(text: String?): Map<String, Pair<Int, Double>> = runCatching {
        val result = mutableMapOf<String, Pair<Int, Double>>()
        json.parseToJsonElement(text ?: return emptyMap()).jsonObject.forEach { (key, value) ->
            val pair = value.jsonArray
            if (pair.size != 2) return@forEach
            val on = pair[0].jsonPrimitive.doubleOrNull ?: return@forEach
            val at = pair[1].jsonPrimitive.doubleOrNull ?: return@forEach
            result[key] = (if (on >= 1) 1 else 0) to at
        }
        result
    }.getOrDefault(emptyMap())

    fun parseRecents(text: String?): Map<String, Double> = runCatching {
        json.parseToJsonElement(text ?: return emptyMap()).jsonObject.mapNotNull { (key, value) ->
            value.jsonPrimitive.doubleOrNull?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun encodeFavorites(log: Map<String, Pair<Int, Double>>): String =
        JsonObject(log.entries.sortedBy { it.key }.associate { (k, v) -> k to JsonArray(listOf(JsonPrimitive(v.first), JsonPrimitive(v.second))) }).toString()

    private fun encodeRecents(log: Map<String, Double>): String =
        JsonObject(log.entries.sortedBy { it.key }.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    fun mergeFavorites(a: Map<String, Pair<Int, Double>>, b: Map<String, Pair<Int, Double>>): Map<String, Pair<Int, Double>> {
        val out = a.toMutableMap()
        b.forEach { (key, entry) ->
            val mine = out[key]
            if (mine == null || entry.second > mine.second || (entry.second == mine.second && entry.first > mine.first)) out[key] = entry
        }
        return out
    }

    fun mergeRecents(a: Map<String, Double>, b: Map<String, Double>): Map<String, Double> {
        val out = a.toMutableMap()
        b.forEach { (key, at) -> val mine = out[key]; if (mine == null || abs(at) > abs(mine)) out[key] = at }
        return out
    }

    private fun trimmedRecents(log: Map<String, Double>): Map<String, Double> =
        if (log.size <= LOG_LIMIT) log
        else log.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { abs(it.value) }.thenBy { it.key })
            .take(LOG_LIMIT).associate { it.key to it.value }

    /** Watched channels, most recent first (ties by key, as on the TVs); removed ones left out. */
    fun orderedRecents(log: Map<String, Double>, limit: Int): List<String> =
        log.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenByDescending { it.key })
            .map { it.key }.take(limit)

    fun favoritesOn(log: Map<String, Pair<Int, Double>>): Set<String> = log.filterValues { it.first == 1 }.keys

    // This phone's log, seeded from its lists the first time ---------------------------------------

    fun localFavorites(current: Set<String>): Map<String, Pair<Int, Double>> {
        val log = parseFavorites(HouseholdCloudStore.loadLocal(favKey())).toMutableMap()
        current.filter { it.startsWith("ch:") && it !in log }.forEach { log[it] = 1 to 0.0 }
        return log
    }

    fun localRecents(current: List<String>): Map<String, Double> {
        val log = parseRecents(HouseholdCloudStore.loadLocal(recentKey()))
        if (log.isNotEmpty()) return log
        val list = current.filter { it.startsWith("ch:") }
        return list.mapIndexed { i, key -> key to (list.size - i).toDouble() }.toMap()
    }

    fun saveFavorites(log: Map<String, Pair<Int, Double>>) = HouseholdCloudStore.saveLocal(favKey(), encodeFavorites(log))
    fun saveRecents(log: Map<String, Double>) = HouseholdCloudStore.saveLocal(recentKey(), encodeRecents(trimmedRecents(log)))

    fun recordFavorite(current: Set<String>, key: String, on: Boolean) {
        val log = localFavorites(current).toMutableMap()
        log[key] = (if (on) 1 else 0) to now()
        saveFavorites(log)
    }

    /** A watch (removed = false) or a removal from Recently Watched. */
    fun recordRecent(current: List<String>, key: String, removed: Boolean) {
        val log = localRecents(current).toMutableMap()
        val at = now()
        if (at >= abs(log[key] ?: 0.0)) log[key] = if (removed) -at else at
        saveRecents(log)
    }
}
