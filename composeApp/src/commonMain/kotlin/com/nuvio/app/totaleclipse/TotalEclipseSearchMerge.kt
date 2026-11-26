package com.nuvio.app.totaleclipse

import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview

/**
 * Total Eclipse: search results as the Apple TVs show them (HomeTV CatalogRepository
 * deduplicatedSearchResults): every add-on's results merged into one row per type (Movies,
 * Series, then anything else), the same title kept once. A title matches by id (IMDb or TMDB,
 * whatever prefix the add-on uses), or by type, year and title when the ids don't conflict, so
 * remakes (different years) stay apart. Order is the add-ons' order, first answer first.
 */
internal object TotalEclipseSearchMerge {
    fun merge(all: List<HomeCatalogSection>): List<HomeCatalogSection> {
        // Your media servers' rows stay as they are (their copy plays from the server).
        val servers = all.filter { it.key.startsWith("server:") }
        val sections = all - servers.toSet()
        if (sections.size <= 1) return all
        val byType = LinkedHashMap<String, MutableList<Pair<MetaPreview, HomeCatalogSection>>>()
        for (section in sections) {
            for (item in section.items) byType.getOrPut(typeKey(item.type)) { mutableListOf() } += item to section
        }
        val order = listOf("movie", "series")
        return byType.entries
            .sortedBy { (type, _) -> order.indexOf(type).let { if (it < 0) order.size else it } }
            .map { (type, entries) ->
                val items = dedupe(entries.map { it.first })
                val first = entries.first().second
                first.copy(
                    key = "totaleclipse.search.$type",
                    title = title(type),
                    subtitle = "",
                    addonName = "",
                    items = items,
                    availableItemCount = items.size,
                    hasMore = false,
                )
            } + servers
    }

    fun dedupe(items: List<MetaPreview>): List<MetaPreview> {
        val byId = HashMap<String, Int>()
        val byFallback = HashMap<String, Int>()
        val result = mutableListOf<MetaPreview>()
        for (item in items) {
            val id = identity(item)
            val fallback = fallbackKey(item)
            val existing = id?.let(byId::get)
                ?: fallback?.let(byFallback::get)?.takeIf { index ->
                    // Ids conflict only within one namespace (two different IMDb ids).
                    val other = identity(result[index])
                    other == null || id == null || other.substringBeforeLast(':') != id.substringBeforeLast(':') || other == id
                }
            if (existing != null) {
                val kept = result[existing]
                if (kept.poster.isNullOrBlank() && !item.poster.isNullOrBlank()) result[existing] = kept.copy(poster = item.poster)
                continue
            }
            result += item
            id?.let { byId[it] = result.lastIndex }
            fallback?.let { byFallback[it] = result.lastIndex }
        }
        return result
    }

    private fun typeKey(type: String): String = when (type.lowercase()) {
        "movie", "movies", "film" -> "movie"
        "series", "tv", "show", "tvshow" -> "series"
        else -> type.lowercase()
    }

    private fun title(type: String): String = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    /** "tt0903747", "imdb:tt0903747" and "tmdb:1396" style ids, reduced to one form. */
    private fun identity(item: MetaPreview): String? {
        val id = item.id.trim().lowercase()
        Regex("""tt\d{5,}""").find(id)?.let { return "${typeKey(item.type)}:imdb:${it.value}" }
        Regex("""^tmdb[:_](?:movie[:_]|tv[:_])?(\d+)""").find(id)?.let { return "${typeKey(item.type)}:tmdb:${it.groupValues[1]}" }
        return null
    }

    private fun fallbackKey(item: MetaPreview): String? {
        val year = item.releaseInfo?.let { Regex("""\d{4}""").find(it)?.value } ?: return null
        val title = item.name.lowercase().filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() } ?: return null
        return "${typeKey(item.type)}:$year:$title"
    }
}
