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
 * - for the active profile: add-on links in the TVs' order, the TMDB and MDBList keys, and the
 *   collections (Movies / Shows).
 *
 * Applied through the app's own settings import and collection import. Something is applied
 * again only when the TVs' value changes, so a change made on the phone stays until the TVs
 * change that value. Nothing is ever written to iCloud. Logs name keys and counts, never values.
 */
internal object HouseholdSync {
    private const val TAG = "TotalEclipse/Household"

    // Written by the TVs (HomeTV ICloudSettingsSyncManager.swift).
    private const val TV_PROFILES_KEY = "hometv.icloud.profiles"
    private const val TV_PROFILE_SETTINGS_PREFIX = "nuvio.icloud.profile.settings."
    private const val TV_GLOBAL_SETTINGS_KEY = "nuvio.icloud.global.settings"
    private const val TV_COLLECTIONS_PREFIX = "hometv.icloud.collections."
    private const val TV_PRIMARY_PROFILE_ID = "guest"

    // TV setting names (HomeTV SettingsKey).
    private const val TV_ADDON_URLS = "nuvio.tv.settings.integrations.streamAddonManifestURLs"
    private const val TV_TMDB_KEY = "nuvio.tv.settings.integrations.tmdbApiKey"
    private const val TV_MDBLIST_KEY = "nuvio.tv.settings.integrations.mdbListApiKey"

    // Phone-only bookkeeping.
    private const val LOCAL_PROFILE_MAP = "totaleclipse.household.profileMap"
    private const val LOCAL_APPLIED_PREFIX = "totaleclipse.household.applied."

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

        val import = buildJsonObject {
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
        val sections = import.keys.sorted().joinToString(",")
        if (import.isNotEmpty() && changedSinceLastApply("settings.$slot", import.toString())) {
            val failures = SettingsConfigTransfer.importHousehold(import)
            InAppLogger.info(TAG, "$reason: profile $slot ← TV '$tvId' settings [$sections] failures=$failures")
            if (failures == 0) markApplied("settings.$slot", import.toString())
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
