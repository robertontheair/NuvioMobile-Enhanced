package com.nuvio.app.totaleclipse

import com.nuvio.app.features.livetv.LiveTvChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Total Eclipse: the Apple TVs' Live TV layout (HomeTV HomeTVLiveLayout, "hometv.iptv.layout.v1"
 * in each profile's iCloud settings), read-only: category order, renamed categories, your own
 * categories with the channels moved into them, and hidden channels. A profile with no layout of
 * its own uses the primary profile's, as on the TVs.
 *
 * A channel moved into one of your categories takes it as its category (the first, if several);
 * one only added also shows in your category ([customMembers]) and stays in its own.
 */
internal object TotalEclipseLiveLayout {
    const val TV_KEY = "hometv.iptv.layout.v1"
    private const val CUSTOM_PREFIX = "\u0001custom."
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Custom(val id: String, val name: String, val channels: List<String> = emptyList())

    @Serializable
    data class Layout(
        val order: List<String> = emptyList(),
        val names: Map<String, String> = emptyMap(),
        val custom: List<Custom> = emptyList(),
        val moved: List<String> = emptyList(),
        val hiddenChannels: List<String> = emptyList(),
        val start: String? = null,
    )

    @kotlin.concurrent.Volatile private var layout: Layout? = null
    @kotlin.concurrent.Volatile private var layoutText: String? = null
    @kotlin.concurrent.Volatile private var orderedTitles: Map<String, Int> = emptyMap()
    /** Channel id → the TVs' key of its own category (before renames / your categories). */
    @kotlin.concurrent.Volatile var categoryKeyByChannel: Map<String, String> = emptyMap()
        private set

    /**
     * Your categories by name → their channels in your order, moved or only added
     * (as on the TVs, a channel can be in your category and still in its own).
     */
    @kotlin.concurrent.Volatile var customMembers: Map<String, List<String>> = emptyMap()
        private set

    fun membersOf(category: String?): List<String>? =
        category?.let { name -> customMembers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }

    /** Where the TVs open Live TV ("Open Live TV Here"). */
    sealed interface Start {
        data object Recents : Start
        data object Favorites : Start
        data object All : Start
        data class Category(val title: String) : Start
    }

    fun start(): Start? {
        val layout = layout ?: return null
        val key = layout.start?.takeIf(String::isNotBlank) ?: return null
        return when {
            key == "\u0001recent" -> Start.Recents
            key == "\u0001favorites" -> Start.Favorites
            key == "\u0001all" -> Start.All
            key.startsWith(CUSTOM_PREFIX) ->
                layout.custom.firstOrNull { CUSTOM_PREFIX + it.id == key }?.name?.let(Start::Category)
            key.startsWith("\u0001") -> null
            else -> (layout.names[key]?.takeIf(String::isNotBlank)
                ?: categoryKeyByChannel.entries.firstOrNull { it.value == key }?.let { groupTitles[it.key] })
                ?.let(Start::Category)
        }
    }

    @kotlin.concurrent.Volatile private var groupTitles: Map<String, String> = emptyMap()

    /** Returns true when the layout changed. */
    fun update(text: String?): Boolean {
        if (text == layoutText) return false
        layoutText = text
        layout = text?.let { runCatching { json.decodeFromString<Layout>(it) }.getOrNull() }
        return true
    }

    fun rank(category: String): Int? = orderedTitles[category.lowercase()]

    /** The channels as the TVs arrange them. */
    fun apply(channels: List<LiveTvChannel>): List<LiveTvChannel> {
        categoryKeyByChannel = channels.associate { it.id to TotalEclipseLiveGroups.tvKey(it.group.orEmpty()) }
        groupTitles = channels.associate { it.id to it.group.orEmpty() }
        val layout = layout ?: run { orderedTitles = emptyMap(); customMembers = emptyMap(); return channels }
        val hidden = layout.hiddenChannels.toSet()
        val ids = channels.mapTo(HashSet()) { it.id }
        customMembers = layout.custom.associate { c ->
            c.name to c.channels.filter { it in ids && it !in hidden }.distinct()
        }.filterValues { it.isNotEmpty() }
        val moved = layout.moved.toSet()
        val customOf = HashMap<String, Custom>()
        layout.custom.forEach { c -> c.channels.forEach { ch -> customOf.getOrPut(ch) { c } } }

        val result = channels.filter { it.id !in hidden }.map { channel ->
            val custom = customOf[channel.id]?.takeIf { channel.id in moved }
            val key = categoryKeyByChannel[channel.id].orEmpty()
            when {
                custom != null -> channel.copy(group = custom.name)
                else -> layout.names[key]?.takeIf(String::isNotBlank)?.let { channel.copy(group = it) } ?: channel
            }
        }

        // Category order: your categories, then the default order, arranged as on the TVs.
        val present = channels.mapNotNull { it.group }.distinct()
            .sortedWith(compareBy({ TotalEclipseLiveGroups.defaultRank(it) }, { it.lowercase() }))
            .map(TotalEclipseLiveGroups::tvKey)
        val defaults = layout.custom.map { CUSTOM_PREFIX + it.id } + present
        orderedTitles = arranged(layout.order, defaults).mapIndexedNotNull { index, key ->
            val title = layout.custom.firstOrNull { CUSTOM_PREFIX + it.id == key }?.name
                ?: layout.names[key]?.takeIf(String::isNotBlank)
                ?: channels.firstOrNull { categoryKeyByChannel[it.id] == key }?.group
            title?.let { it.lowercase() to index }
        }.toMap()
        return result
    }

    /**
     * [defaults] in the chosen [order]; a key the order doesn't know goes just before the one it
     * precedes by default, or at the end (HomeTVLiveLayout.arranged).
     */
    fun arranged(order: List<String>, defaults: List<String>): List<String> {
        val present = defaults.toSet()
        val result = order.filter { it in present }.distinct().toMutableList()
        val known = result.toSet()
        for (index in defaults.indices.reversed()) {
            val key = defaults[index]
            if (key in known) continue
            val after = defaults.drop(index + 1).firstOrNull { it in result }
            val at = after?.let { result.indexOf(it) } ?: -1
            if (at >= 0) result.add(at, key) else result.add(key)
        }
        return result
    }
}
