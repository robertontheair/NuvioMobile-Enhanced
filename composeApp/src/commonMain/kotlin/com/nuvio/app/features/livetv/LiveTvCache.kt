package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.AppFileCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ChannelsCachePath = "live_tv/channels.json"
private const val EpgCachePath = "live_tv/epg2.json"
/** Total Eclipse: the previous, larger format; removed when the new file is written. */
private const val OldEpgCachePath = "live_tv/epg.json"

private val liveTvCacheJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class CachedLiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val group: String? = null,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val stalkerCommand: String? = null,
    val epgId: String? = null,
)

@Serializable
private data class CachedLiveTvChannelsPayload(
    val signature: String,
    val savedAtMillis: Long,
    val epgUrls: List<String> = emptyList(),
    val channels: List<CachedLiveTvChannel> = emptyList(),
)

@Serializable
private data class CachedLiveTvProgram(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val description: String? = null,
    val category: String? = null,
    val flags: Int = 0,
)

/**
 * Total Eclipse: compact guide cache. Titles and descriptions repeat across channels and days
 * (100k programmes, 15k distinct descriptions), so each text is stored once in [texts] and
 * programmes refer to it by index; times are seconds. About 40% of the old file's size, and the
 * repeated texts are shared in memory too.
 */
@Serializable
private data class CompactEpgProgram(
    @kotlinx.serialization.SerialName("t") val title: Int,
    @kotlinx.serialization.SerialName("s") val startSeconds: Long,
    @kotlinx.serialization.SerialName("e") val durationSeconds: Int,
    @kotlinx.serialization.SerialName("d") val description: Int = -1,
    @kotlinx.serialization.SerialName("c") val category: Int = -1,
    @kotlinx.serialization.SerialName("f") val flags: Int = 0,
    @kotlinx.serialization.SerialName("n") val episode: Int = -1,
    @kotlinx.serialization.SerialName("u") val episodeTitle: Int = -1,
    @kotlinx.serialization.SerialName("r") val rating: Int = -1,
    @kotlinx.serialization.SerialName("y") val year: Int = 0,
)

@Serializable
private data class CompactEpgPayload(
    @kotlinx.serialization.SerialName("v") val version: Int = 2,
    @kotlinx.serialization.SerialName("g") val signature: String,
    @kotlinx.serialization.SerialName("a") val savedAtMillis: Long,
    @kotlinx.serialization.SerialName("x") val texts: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("p") val programs: Map<String, List<CompactEpgProgram>> = emptyMap(),
)

@Serializable
private data class CachedLiveTvEpgPayload(
    val signature: String,
    val savedAtMillis: Long,
    val programs: Map<String, List<CachedLiveTvProgram>> = emptyMap(),
)

internal class LiveTvCachedChannels(
    val signature: String,
    val savedAtMillis: Long,
    val epgUrls: List<String>,
    val channels: List<LiveTvChannel>,
)

internal class LiveTvCachedEpg(
    val signature: String,
    val savedAtMillis: Long,
    val programs: Map<String, List<LiveTvEpgProgram>>,
)

internal object LiveTvCacheStore {
    fun loadChannels(): LiveTvCachedChannels? = runCatching {
        val payload = AppFileCache.read(ChannelsCachePath)?.takeIf(String::isNotBlank) ?: return null
        val decoded = liveTvCacheJson.decodeFromString<CachedLiveTvChannelsPayload>(payload)
        LiveTvCachedChannels(
            signature = decoded.signature,
            savedAtMillis = decoded.savedAtMillis,
            epgUrls = decoded.epgUrls,
            channels = decoded.channels.map { channel ->
                LiveTvChannel(
                    id = channel.id,
                    name = channel.name,
                    streamUrl = channel.streamUrl,
                    logoUrl = channel.logoUrl,
                    group = channel.group,
                    playlistId = channel.playlistId,
                    playlistName = channel.playlistName,
                    headers = channel.headers,
                    streamType = channel.streamType,
                    stalkerCommand = channel.stalkerCommand,
                    epgId = channel.epgId,
                )
            },
        )
    }.getOrNull()

    fun saveChannels(signature: String, savedAtMillis: Long, epgUrls: List<String>, channels: List<LiveTvChannel>) {
        runCatching {
            val payload = CachedLiveTvChannelsPayload(
                signature = signature,
                savedAtMillis = savedAtMillis,
                epgUrls = epgUrls,
                channels = channels.map { channel ->
                    CachedLiveTvChannel(
                        id = channel.id,
                        name = channel.name,
                        streamUrl = channel.streamUrl,
                        logoUrl = channel.logoUrl,
                        group = channel.group,
                        playlistId = channel.playlistId,
                        playlistName = channel.playlistName,
                        headers = channel.headers,
                        streamType = channel.streamType,
                        stalkerCommand = channel.stalkerCommand,
                        epgId = channel.epgId,
                    )
                },
            )
            AppFileCache.write(ChannelsCachePath, liveTvCacheJson.encodeToString(payload))
        }
    }

    fun loadEpg(): LiveTvCachedEpg? = runCatching {
        val payload = AppFileCache.read(EpgCachePath)?.takeIf(String::isNotBlank) ?: return loadOldEpg()
        val decoded = liveTvCacheJson.decodeFromString<CompactEpgPayload>(payload)
        val texts = decoded.texts
        fun text(index: Int): String? = texts.getOrNull(index)
        LiveTvCachedEpg(
            signature = decoded.signature,
            savedAtMillis = decoded.savedAtMillis,
            programs = decoded.programs.mapValues { (_, programs) ->
                programs.map { program ->
                    val start = program.startSeconds * 1000L
                    LiveTvEpgProgram(
                        title = text(program.title).orEmpty(),
                        startMillis = start,
                        endMillis = start + program.durationSeconds * 1000L,
                        description = text(program.description),
                        category = text(program.category),
                        flags = program.flags,
                        episode = text(program.episode),
                        episodeTitle = text(program.episodeTitle),
                        rating = text(program.rating),
                        year = program.year.takeIf { it > 0 },
                    )
                }
            },
        )
    }.getOrNull()

    private fun loadOldEpg(): LiveTvCachedEpg? = runCatching {
        val payload = AppFileCache.read(OldEpgCachePath)?.takeIf(String::isNotBlank) ?: return null
        val decoded = liveTvCacheJson.decodeFromString<CachedLiveTvEpgPayload>(payload)
        LiveTvCachedEpg(
            signature = decoded.signature,
            savedAtMillis = decoded.savedAtMillis,
            programs = decoded.programs.mapValues { (_, programs) ->
                programs.map { program ->
                    LiveTvEpgProgram(
                        title = program.title,
                        startMillis = program.startMillis,
                        endMillis = program.endMillis,
                        description = program.description,
                        category = program.category,
                        flags = program.flags,
                    )
                }
            },
        )
    }.getOrNull()

    fun saveEpg(signature: String, savedAtMillis: Long, programs: Map<String, List<LiveTvEpgProgram>>) {
        runCatching {
            val index = HashMap<String, Int>()
            val texts = ArrayList<String>()
            fun ref(value: String?): Int {
                if (value.isNullOrEmpty()) return -1
                return index.getOrPut(value) { texts.add(value); texts.size - 1 }
            }
            val compact = programs.mapValues { (_, list) ->
                list.map { program ->
                    CompactEpgProgram(
                        title = ref(program.title),
                        startSeconds = program.startMillis / 1000L,
                        durationSeconds = ((program.endMillis - program.startMillis) / 1000L).toInt(),
                        description = ref(program.description),
                        category = ref(program.category),
                        flags = program.flags,
                        episode = ref(program.episode),
                        episodeTitle = ref(program.episodeTitle),
                        rating = ref(program.rating),
                        year = program.year ?: 0,
                    )
                }
            }
            val payload = CompactEpgPayload(
                signature = signature,
                savedAtMillis = savedAtMillis,
                texts = texts,
                programs = compact,
            )
            AppFileCache.write(EpgCachePath, liveTvCacheJson.encodeToString(payload))
            AppFileCache.delete(OldEpgCachePath)
        }
    }
}

/**
 * Bumped when the channel list is read differently, so lists saved by an older version are
 * loaded again (3: VLC's user agent by default, Xtream panel links in the account's format).
 */
private const val ChannelsCacheFormat = 6 // Total Eclipse: 4 = the TVs' portable channel keys; 5 = programme kinds; 6 = episode facts

internal fun liveTvChannelSourceSignature(state: LiveTvUiState): String = buildString {
    append("v").append(ChannelsCacheFormat).append(';')
    state.playlists.filter { it.isEnabled }.forEach { playlist ->
        append(playlist.id).append('|').append(playlist.type.name).append('|')
        append(playlist.source.length).append(':').append(playlist.source.hashCode()).append(';')
    }
    val xtream = state.xtreamSettings
    if (xtream.isConfigured && xtream.isEnabled) {
        append("x|").append(xtream.serverUrl).append('|').append(xtream.username).append('|')
        append(xtream.password.hashCode()).append(';')
    }
    val stalker = state.stalkerSettings
    if (stalker.isConfigured && stalker.isEnabled) {
        append("s|").append(stalker.portalUrl).append('|').append(stalker.macAddress).append('|')
        append(stalker.username).append(';')
    }
}.let { raw -> "${raw.length}:${raw.hashCode()}" }
