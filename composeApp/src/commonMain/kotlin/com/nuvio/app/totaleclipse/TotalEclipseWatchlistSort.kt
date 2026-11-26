package com.nuvio.app.totaleclipse

/**
 * Total Eclipse: the Trakt watchlist in the order you see on trakt.tv, as on the Apple TVs
 * (HomeTV HomeTVWatchlistSort). Trakt sends your sort choice with each list (X-Sort-By,
 * X-Sort-How); the /rank endpoints alone give rank order. Sorts we can't reproduce here
 * (popularity, votes, runtime, random…) keep Trakt's order.
 */
internal object TotalEclipseWatchlistSort {
    data class Entry(
        val rank: Int?,
        val listedAt: String?,
        val title: String,
        val year: Int?,
        val rating: Double?,
    )

    /** Indices of [entries] in display order. */
    fun order(entries: List<Entry>, sortBy: String?, sortHow: String?): List<Int> {
        val descending = sortHow.equals("desc", ignoreCase = true)
        val indexed = entries.indices.toList()

        fun <T : Comparable<T>> byOptional(key: (Entry) -> T?): List<Int> =
            indexed.sortedWith { i, j ->
                val a = key(entries[i])
                val b = key(entries[j])
                when {
                    a != null && b != null && a != b -> if (descending) b.compareTo(a) else a.compareTo(b)
                    a != null && b == null -> -1 // titles missing the field go last either way
                    a == null && b != null -> 1
                    else -> i.compareTo(j)
                }
            }

        return when (sortBy?.lowercase()) {
            "rank" -> byOptional { it.rank }
            "added" -> byOptional { it.listedAt }
            "title" -> indexed.sortedWith { i, j ->
                val c = entries[i].title.compareTo(entries[j].title, ignoreCase = true)
                when {
                    c == 0 -> i.compareTo(j)
                    descending -> -c
                    else -> c
                }
            }
            "released", "year" -> byOptional { it.year }
            "percentage", "rating" -> byOptional { it.rating }
            // No header at all: rank order, which is what the /rank endpoints meant before.
            null -> byOptional { it.rank }
            else -> indexed
        }
    }
}
