package com.nuvio.app.totaleclipse

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfilePushPayload
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.SettingsConfigTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Total Eclipse: the phone joins the household set up on the Apple TVs, read-only.
 *
 * From the TVs' iCloud key-value store (see [HouseholdCloudStore]) it takes:
 * - the profiles (the TVs' primary profile "guest" becomes slot 1; others get the next free
 *   slot, remembered on this phone so a slot never changes);
 * - for the active profile: add-on links in the TVs' order, the TMDB and MDBList keys, the
 *   Live TV sources (playlists, guide links, Xtream/Stalker logins) and the
 *   collections (Movies / Shows).
 *
 * Applied through the app's own settings import and collection import. Something is applied
 * again only when the TVs' value changes, so a change made on the phone stays until the TVs
 * change that value. Nothing is ever written to iCloud. Logs name keys and counts, never values.
 */
internal object HouseholdSync {
    /** The active profile's Live TV favourites and recents logs from the TVs (raw JSON). */
    @kotlin.concurrent.Volatile private var liveLogs: Pair<Int, Pair<String?, String?>>? = null

    /** The logs for phone profile [slot], or null when they're another profile's (or not read yet). */
    fun liveLogsFor(slot: Int): Pair<String?, String?>? = liveLogs?.takeIf { it.first == slot }?.second

    private const val TAG = "TotalEclipse/Household"

    // Written by the TVs (HomeTV ICloudSettingsSyncManager.swift).
    private const val TV_PROFILES_KEY = "hometv.icloud.profiles"
    private const val TV_PROFILE_SETTINGS_PREFIX = "nuvio.icloud.profile.settings."
    private const val TV_GLOBAL_SETTINGS_KEY = "nuvio.icloud.global.settings"
    private const val TV_COLLECTIONS_PREFIX = "hometv.icloud.collections."
    private const val TV_IPTV_PREFIX = "hometv.icloud.iptv."
    private const val TV_PRIMARY_PROFILE_ID = "guest"

    // TV setting names (HomeTV SettingsKey).
    private const val TV_ADDON_URLS = "nuvio.tv.settings.integrations.streamAddonManifestURLs"
    private const val TV_TMDB_KEY = "nuvio.tv.settings.integrations.tmdbApiKey"
    private const val TV_MDBLIST_KEY = "nuvio.tv.settings.integrations.mdbListApiKey"

    // Phone-only bookkeeping.
    private const val LOCAL_PROFILE_MAP = "totaleclipse.household.profileMap"
    private const val LOCAL_APPLIED_PREFIX = "totaleclipse.household.applied."
    private const val LOCAL_REMOTE_PREFIX = "totaleclipse.livetv.remote."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var started = false

    @Serializable
    private data class TvProfile(
        val id: String,
        val name: String,
        val usesPrimaryAddons: Boolean? = null,
    )

    /** A TV Live TV source (HomeTV `IPTVSource`); kind is m3u, xtream or stalker. */
    @Serializable
    private data class TvIptvSource(
        val name: String = "",
        val playlistURL: String = "",
        val kind: String = "m3u",
        val username: String? = null,
        val password: String? = null,
        val mac: String? = null,
        val guideURL: String? = null,
    )

    /** Call once there's a signed-in (local) account. Safe to call repeatedly. */
    fun start() {
        if (started || !TotalEclipse.HOUSEHOLD_SYNC_ENABLED || !HouseholdCloudStore.isAvailable) return
        started = true
        HouseholdCloudStore.observe { sync("iCloud change") }
        HouseholdCloudStore.refresh()
        sync("start")
    }

    /** The active profile changed: bring its add-ons, keys and collections in line with the TVs. */
    fun onProfileSelected() {
        if (liveLogs?.first != ProfileRepository.activeProfileId) {
            liveLogs = null
            TotalEclipseLiveLayout.update(null) // the last profile's layout; this one's is read by the sync
        }
        com.nuvio.app.features.livetv.LiveTvRepository.onProfileChanged()
        if (started) sync("profile selected")
    }

    private fun sync(reason: String) {
        scope.launch {
            mutex.withLock {
                try {
                    syncNow(reason)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    InAppLogger.warn(TAG, "sync failed ($reason): ${e::class.simpleName}")
                }
            }
        }
    }

    private suspend fun syncNow(reason: String) {
        upgradeHomePlaylists()
        val keys = HouseholdCloudStore.keys()
        if (keys.none { it.startsWith(TV_PROFILE_SETTINGS_PREFIX) }) {
            InAppLogger.info(TAG, "$reason: no household data in iCloud (${keys.size} keys)")
            return
        }
        val auth = AuthRepository.state.value as? AuthState.Authenticated ?: return
        withTimeoutOrNull(10_000) { ProfileRepository.state.first { it.isLoaded } } ?: run {
            InAppLogger.info(TAG, "$reason: profiles not loaded yet")
            return
        }
        val profileMap = if (auth.isAnonymous) applyProfiles() else loadProfileMap()
        applyActiveProfile(profileMap, reason)
    }

    /** TV profile id → phone profile slot. Adds or renames phone profiles to match the TVs. */
    private suspend fun applyProfiles(): Map<String, Int> {
        val map = loadProfileMap().toMutableMap()
        val tvProfiles = HouseholdCloudStore.text(TV_PROFILES_KEY)
            ?.let { runCatching { json.decodeFromString<List<TvProfile>>(it) }.getOrNull() }
            ?: return map
        val ordered = tvProfiles.sortedBy { if (it.id == TV_PRIMARY_PROFILE_ID) 0 else 1 }
        for (profile in ordered) {
            if (profile.id in map) continue
            val wanted = if (profile.id == TV_PRIMARY_PROFILE_ID) 1 else null
            val slot = wanted?.takeIf { it !in map.values }
                ?: ((1..MAX_PROFILES).toSet() - map.values.toSet()).minOrNull()
                ?: continue
            map[profile.id] = slot
        }
        saveProfileMap(map)

        val existing = ProfileRepository.state.value.profiles.associateBy { it.profileIndex }
        val tvBySlot = ordered.mapNotNull { p -> map[p.id]?.let { it to p } }.toMap()
        val slots = (existing.keys + tvBySlot.keys).sorted()
        var changed = false
        val payloads = slots.map { slot ->
            val current = existing[slot]
            val tv = tvBySlot[slot]
            val name = tv?.name?.takeIf { it.isNotBlank() } ?: current?.name.orEmpty()
            val usesPrimaryAddons = tv?.usesPrimaryAddons ?: current?.usesPrimaryAddons ?: false
            if (current == null || current.name != name || current.usesPrimaryAddons != usesPrimaryAddons) changed = true
            ProfilePushPayload(
                profileIndex = slot,
                name = name,
                avatarColorHex = current?.avatarColorHex ?: "#1E88E5",
                usesPrimaryAddons = usesPrimaryAddons,
                usesPrimaryPlugins = current?.usesPrimaryPlugins ?: false,
                avatarId = current?.avatarId,
                avatarUrl = current?.avatarUrl,
                profileBackgroundId = current?.profileBackgroundId,
                profileBackgroundUrl = current?.profileBackgroundUrl,
            )
        }
        if (changed) {
            ProfileRepository.pushProfiles(payloads)
            InAppLogger.info(TAG, "profiles from the TVs: ${tvBySlot.size} (phone now has ${payloads.size})")
        }
        return map
    }

    private suspend fun applyActiveProfile(profileMap: Map<String, Int>, reason: String) {
        val slot = ProfileRepository.activeProfileId
        val tvId = profileMap.entries.firstOrNull { it.value == slot }?.key
            ?: if (slot == 1) TV_PRIMARY_PROFILE_ID else return
        val settings = HouseholdCloudStore.stringSettings(TV_GLOBAL_SETTINGS_KEY) +
            HouseholdCloudStore.stringSettings(TV_PROFILE_SETTINGS_PREFIX + tvId)

        val baseImport = buildJsonObject {
            settings[TV_ADDON_URLS]?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList()
                ?.takeIf { it.isNotEmpty() }
                ?.let { urls ->
                    put("addons", buildJsonArray {
                        urls.forEach { url -> add(buildJsonObject { put("url", url); put("enabled", true) }) }
                    })
                }
            settings[TV_TMDB_KEY]?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
                put("tmdb", buildJsonObject {
                    put("enabled", encodeSyncBoolean(true))
                    put("api_key", encodeSyncString(key))
                })
            }
            settings[TV_MDBLIST_KEY]?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
                put("mdblist", buildJsonObject {
                    put("enabled", encodeSyncBoolean(true))
                    put("api_key", encodeSyncString(key))
                })
            }
        }
        val liveTv = liveTvImport(tvId)?.let { remoteLiveTv(it) }
        val import = if (liveTv == null) baseImport else JsonObject(baseImport + ("live_tv" to liveTv))
        val sections = import.keys.sorted().joinToString(",")
        if (import.isNotEmpty() && changedSinceLastApply("settings.$slot", import.toString())) {
            val failures = SettingsConfigTransfer.importHousehold(import)
            InAppLogger.info(TAG, "$reason: profile $slot ← TV '$tvId' settings [$sections] failures=$failures")
            if (failures == 0) markApplied("settings.$slot", import.toString())
        }

        applyPlayerSettings(slot, tvId, reason)

        // Another profile was chosen while this one was being read: its own sync follows.
        if (ProfileRepository.activeProfileId != slot) return

        // Live TV favourites and Recently Watched: the TVs' dated logs, merged into the phone's.
        val profileSettings = HouseholdCloudStore.stringSettings(TV_PROFILE_SETTINGS_PREFIX + tvId)
        val logs = profileSettings[TotalEclipseLiveSync.TV_FAVORITES_LOG] to profileSettings[TotalEclipseLiveSync.TV_RECENTS_LOG]
        // The TVs' Live TV layout (the primary profile's until this one has its own).
        val layoutText = profileSettings[TotalEclipseLiveLayout.TV_KEY]
            ?: HouseholdCloudStore.stringSettings(TV_PROFILE_SETTINGS_PREFIX + TV_PRIMARY_PROFILE_ID)[TotalEclipseLiveLayout.TV_KEY]
        if (TotalEclipseLiveLayout.update(layoutText)) {
            InAppLogger.info(TAG, "$reason: Live TV layout from TV '$tvId' (${layoutText?.length ?: 0} chars)")
            com.nuvio.app.features.livetv.LiveTvRepository.reapplyLayout()
        }
        if (logs != liveLogsFor(slot)) {
            liveLogs = slot to logs
            if (com.nuvio.app.features.livetv.LiveTvRepository.uiState.value.channels.isNotEmpty()) {
                com.nuvio.app.features.livetv.LiveTvRepository.applyHouseholdLiveLogs(logs.first, logs.second)
            }
        }

        HouseholdCloudStore.text(TV_COLLECTIONS_PREFIX + tvId)?.takeIf { it.isNotBlank() }?.let { collections ->
            if (changedSinceLastApply("collections.$slot", collections)) {
                CollectionRepository.importFromJson(collections)
                    .onSuccess {
                        markApplied("collections.$slot", collections)
                        InAppLogger.info(TAG, "$reason: profile $slot ← TV '$tvId' collections (${it.size})")
                    }
                    .onFailure { InAppLogger.warn(TAG, "TV collections didn't import: ${it::class.simpleName}") }
            }
        }
    }

    /**
     * The TVs' Live TV sources for this profile in the settings-import shape: M3U links as
     * playlists, their guide links as EPG sources, and an Xtream or Stalker login. The TVs fall
     * back to the primary profile's sources when a profile has none, so this does too.
     */
    private fun liveTvImport(tvId: String): JsonObject? {
        fun read(id: String): List<TvIptvSource> =
            HouseholdCloudStore.text(TV_IPTV_PREFIX + id)
                ?.let { runCatching { json.decodeFromString<List<TvIptvSource>>(it) }.getOrNull() }
                .orEmpty()
        val sources = read(tvId).ifEmpty { if (tvId != TV_PRIMARY_PROFILE_ID) read(TV_PRIMARY_PROFILE_ID) else emptyList() }
            .filter { it.playlistURL.isNotBlank() }
        if (sources.isEmpty()) return null
        val m3u = sources.filter { it.kind == "m3u" }
        val xtream = sources.firstOrNull { it.kind == "xtream" && !it.username.isNullOrBlank() && !it.password.isNullOrBlank() }
        val stalker = sources.firstOrNull { it.kind == "stalker" && !it.mac.isNullOrBlank() }
        val guides = sources.mapNotNull { s -> s.guideURL?.trim()?.takeIf(String::isNotEmpty)?.let { s.name to it } }
            .distinctBy { it.second }
        return buildJsonObject {
            if (m3u.isNotEmpty()) put("playlists", buildJsonArray {
                m3u.forEach { s ->
                    add(buildJsonObject {
                        put("source", s.playlistURL.trim())
                        put("type", "Url")
                        put("name", s.name)
                        put("enabled", true)
                    })
                }
            })
            if (guides.isNotEmpty()) put("epg_sources", buildJsonArray {
                guides.forEach { (name, url) ->
                    add(buildJsonObject { put("name", name); put("url", url); put("enabled", true) })
                }
            })
            xtream?.let { s ->
                put("xtream", buildJsonObject {
                    put("server_url", s.playlistURL.trim())
                    put("username", s.username.orEmpty())
                    put("password", s.password.orEmpty())
                    put("enabled", true)
                })
            }
            stalker?.let { s ->
                put("stalker", buildJsonObject {
                    put("portal_url", s.playlistURL.trim())
                    put("mac_address", s.mac.orEmpty())
                    put("username", s.username.orEmpty())
                    put("password", s.password.orEmpty())
                    put("enabled", true)
                })
            }
        }
    }

    /**
     * The TVs use epgfix on the home network (http://192.168.x.x:8787). On the phone, switch to
     * its remote link (Cloudflare Tunnel + secret path) so Live TV also works away from home:
     * epgfix names that link in an X-TotalEclipse-Remote header, which a HEAD request on the home
     * network reads. The link is remembered on the phone; with none known, the home link stays.
     */
    private suspend fun remoteLiveTv(import: JsonObject): JsonObject {
        val playlists = import["playlists"] as? kotlinx.serialization.json.JsonArray ?: return import
        var originMap = emptyMap<String, String>()   // LAN origin → remote base
        val mapped = buildJsonArray {
            playlists.forEach { element ->
                val item = element as? JsonObject
                val source = (item?.get("source") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val remote = source?.takeIf(::isHomeNetworkUrl)?.let { remotePlaylistFor(it) }
                if (item == null || source == null || remote == null) {
                    element.let(::add)
                } else {
                    originMap = originMap + (origin(source) to remote.removeSuffix("/playlist.m3u"))
                    add(JsonObject(item + ("source" to kotlinx.serialization.json.JsonPrimitive(remote))))
                    // Drop the home-network copy if it was added before the remote link was known.
                    com.nuvio.app.features.livetv.LiveTvRepository.uiState.value.playlists
                        .filter { it.source.trim().equals(source.trim(), ignoreCase = true) }
                        .forEach { com.nuvio.app.features.livetv.LiveTvRepository.removePlaylist(it.id) }
                }
            }
        }
        val guides = (import["epg_sources"] as? kotlinx.serialization.json.JsonArray)?.let { array ->
            buildJsonArray {
                array.forEach { element ->
                    val item = element as? JsonObject
                    val url = (item?.get("url") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val base = url?.let { originMap[origin(it)] }
                    if (item == null || url == null || base == null) add(element)
                    else add(JsonObject(item + ("url" to kotlinx.serialization.json.JsonPrimitive(base + url.removePrefix(origin(url))))))
                }
            }
        }
        return JsonObject(import + ("playlists" to mapped) + (guides?.let { mapOf("epg_sources" to it) } ?: emptyMap()))
    }

    /**
     * Any Live TV playlist on the phone that points at the home network (added by hand, or before
     * the remote link was known) is swapped for its away-from-home link once that's known.
     */
    private suspend fun upgradeHomePlaylists() {
        val repo = com.nuvio.app.features.livetv.LiveTvRepository
        repo.ensureLoaded()
        repo.uiState.value.playlists
            .filter { it.type == com.nuvio.app.features.livetv.LiveTvPlaylistType.Url && isHomeNetworkUrl(it.source) }
            .forEach { playlist ->
                val remote = remotePlaylistFor(playlist.source) ?: return@forEach
                // Remove first: adding starts a load, and one that still included the home
                // playlist could finish last and leave both lineups on screen.
                repo.removePlaylist(playlist.id)
                if (repo.uiState.value.playlists.none { it.source.equals(remote, ignoreCase = true) }) {
                    repo.addPlaylistUrl(playlist.name, remote)
                }
                // Its home-network guide too: the remote playlist names its own guide.
                val homeOrigin = origin(playlist.source)
                repo.uiState.value.epgSources
                    .filter { origin(it.url).equals(homeOrigin, ignoreCase = true) }
                    .forEach { repo.removeEpgSource(it.id) }
                InAppLogger.info(TAG, "Live TV: switched a home playlist to its away-from-home link")
            }
        // Home-network guides left from an earlier switch.
        repo.uiState.value.epgSources
            .filter { isHomeNetworkUrl(it.url) && repo.uiState.value.playlists.none { p -> isHomeNetworkUrl(p.source) } }
            .filter { HouseholdCloudStore.loadLocal(LOCAL_REMOTE_PREFIX + fingerprint(origin(it.url) + "/playlist.m3u")) != null }
            .forEach { repo.removeEpgSource(it.id) }
    }

    private suspend fun remotePlaylistFor(homeUrl: String): String? {
        val key = LOCAL_REMOTE_PREFIX + fingerprint(homeUrl)
        val learned = withTimeoutOrNull(4_000) {
            runCatching {
                com.nuvio.app.features.addons.httpRequestRaw(method = "HEAD", url = homeUrl, headers = emptyMap(), body = "")
                    .headers.entries.firstOrNull { it.key.equals("X-TotalEclipse-Remote", ignoreCase = true) }?.value
            }.getOrNull()
        }?.trim()?.takeIf { it.startsWith("https://") && it.endsWith("/playlist.m3u") }
        if (learned != null) {
            if (HouseholdCloudStore.loadLocal(key) != learned) {
                HouseholdCloudStore.saveLocal(key, learned)
                InAppLogger.info(TAG, "Live TV: learned the away-from-home link for a home playlist")
            }
            return learned
        }
        return HouseholdCloudStore.loadLocal(key)
    }

    private fun origin(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    private fun isHomeNetworkUrl(url: String): Boolean {
        val host = origin(url).substringAfter("://").substringBefore(':').lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /**
     * The player switches both apps share, per profile (Mark's profile on the TVs auto-skips
     * intros, recaps and credits and plays the next episode by itself). Speed is hidden where the
     * TVs hide it; their PiP choice is left alone (it's useful on a phone).
     */
    private fun applyPlayerSettings(slot: Int, tvId: String, reason: String) {
        val tv = HouseholdCloudStore.boolSettings(TV_GLOBAL_SETTINGS_KEY) +
            HouseholdCloudStore.boolSettings(TV_PROFILE_SETTINGS_PREFIX + tvId)
        val skip = mapOf(
            "nuvio.tv.settings.playback.autoSkipIntro" to com.nuvio.app.features.player.skip.AutoSkipSegmentType.INTRO,
            "nuvio.tv.settings.playback.autoSkipRecap" to com.nuvio.app.features.player.skip.AutoSkipSegmentType.RECAP,
            "nuvio.tv.settings.playback.autoSkipOutro" to com.nuvio.app.features.player.skip.AutoSkipSegmentType.OUTRO,
            "nuvio.tv.settings.playback.autoSkipMovieCredits" to com.nuvio.app.features.player.skip.AutoSkipSegmentType.MOVIE_CREDITS,
        )
        tv["nuvio.tv.settings.playback.showSpeed"]?.let { TotalEclipsePlayerPrefs.setHidesSpeed(slot, !it) }
        // Total Eclipse: as on the TVs, open on the last profile instead of asking every launch
        // (their default is on), and reuse the last working link when the TVs do.
        val rememberLast = tv["nuvio.tv.settings.profile.autoSelectLast"] ?: true
        if (changedSinceLastApply("profile.rememberLast", rememberLast.toString())) {
            com.nuvio.app.features.profiles.ProfileRepository.setRememberLastProfileEnabled(rememberLast)
            markApplied("profile.rememberLast", rememberLast.toString())
        }
        tv["nuvio.tv.settings.playback.reuseLastLink"]?.let { reuse ->
            if (changedSinceLastApply("player.reuseLastLink.$slot", reuse.toString())) {
                com.nuvio.app.features.player.PlayerSettingsRepository.ensureLoaded()
                com.nuvio.app.features.player.PlayerSettingsRepository.setStreamReuseLastLinkEnabled(reuse)
                markApplied("player.reuseLastLink.$slot", reuse.toString())
            }
        }
        val wanted = buildMap {
            skip.keys.forEach { key -> tv[key]?.let { put(key, it) } }
            tv["nuvio.tv.settings.playback.autoPlayNext"]?.let { put("autoPlayNext", it) }
            tv["nuvio.tv.settings.playback.postPlayRecommendationsEnabled"]?.let { put("recommendations", it) }
        }
        if (wanted.isEmpty()) return
        val fingerprintSource = wanted.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
        if (!changedSinceLastApply("player.$slot", fingerprintSource)) return
        val player = com.nuvio.app.features.player.PlayerSettingsRepository
        player.ensureLoaded()
        skip.forEach { (key, type) -> wanted[key]?.let { player.setAutoSkipSegmentTypeEnabled(type, it) } }
        wanted["autoPlayNext"]?.let(player::setStreamAutoPlayNextEpisodeEnabled)
        wanted["recommendations"]?.let(player::setMovieRecommendationsEnabled)
        markApplied("player.$slot", fingerprintSource)
        InAppLogger.info(TAG, "$reason: profile $slot ← TV '$tvId' player switches (${wanted.size})")
    }

    private fun changedSinceLastApply(name: String, value: String): Boolean =
        HouseholdCloudStore.loadLocal(LOCAL_APPLIED_PREFIX + name) != fingerprint(value)

    private fun markApplied(name: String, value: String) =
        HouseholdCloudStore.saveLocal(LOCAL_APPLIED_PREFIX + name, fingerprint(value))

    private fun fingerprint(value: String): String = "${value.length}:${value.hashCode()}"

    private fun loadProfileMap(): Map<String, Int> =
        HouseholdCloudStore.loadLocal(LOCAL_PROFILE_MAP)
            ?.let { runCatching { json.decodeFromString<Map<String, Int>>(it) }.getOrNull() }
            .orEmpty()

    private fun saveProfileMap(map: Map<String, Int>) =
        HouseholdCloudStore.saveLocal(LOCAL_PROFILE_MAP, json.encodeToString(map))
}
