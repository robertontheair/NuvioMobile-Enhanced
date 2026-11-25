package com.nuvio.app.totaleclipse

import com.nuvio.app.features.livetv.LiveTvChannel

/**
 * Total Eclipse: HomeTV's own Live TV categories (HomeTV `HomeTVLiveGroups.swift`, Rob
 * 2026-11-21/22) for a lineup from the NAS (epgfix), built from the `hometv-*` labels on each
 * channel instead of the provider's 36 groups:
 *
 * - by type: Entertainment, Premium, Sports Networks, News Networks, Kids & Family, 24/7,
 *   Pay-Per-View, Religion, Shopping;
 * - locals by city: Miami first (home), the ten biggest US markets, Other Local Stations;
 * - international: Spanish, Canada, United Kingdom, Caribbean.
 *
 * Event slots (ESPN+, NCAAF…) go under "Live Events" (the TVs show them only in Live Sports Now).
 * Adult channels are left out: the TVs hide them by default and the phone can't hide a category.
 * A provider group nothing matches keeps its own name. Playlists without labels are unchanged.
 */
internal object TotalEclipseLiveGroups {
    const val LIVE_EVENTS = "Live Events"
    const val OTHER_LOCAL = "Other Local Stations"

    private data class Market(val id: String, val title: String, val places: List<String>)
    private data class ChannelType(val id: String, val title: String, val words: List<String>)

    private val markets = listOf(
        // West Palm Beach is its own market, but Rob counts it as home (2026-11-22).
        Market("miami", "Miami", listOf("Miami", "Fort Lauderdale", "West Palm Beach")),
        Market("newyork", "New York", listOf("New York", "Newark", "Long Island")),
        Market("losangeles", "Los Angeles", listOf("Los Angeles")),
        Market("chicago", "Chicago", listOf("Chicago")),
        Market("philadelphia", "Philadelphia", listOf("Philadelphia")),
        Market("dallas", "Dallas–Fort Worth", listOf("Dallas", "Fort Worth", "Irving")),
        Market("houston", "Houston", listOf("Houston")),
        Market("atlanta", "Atlanta", listOf("Atlanta")),
        Market("washington", "Washington, DC", listOf("Washington")),
        Market("boston", "Boston", listOf("Boston")),
        Market("sanfrancisco", "San Francisco Bay Area", listOf("San Francisco", "Oakland", "San Jose")),
    )

    private val types = listOf(
        ChannelType("entertainment", "Entertainment", listOf("entertainment")),
        ChannelType("premium", "Premium", listOf("premium")),
        ChannelType("sports", "Sports Networks", listOf("sports")),
        ChannelType("news", "News Networks", listOf("news")),
        ChannelType("kids", "Kids & Family", listOf("kids", "family")),
        ChannelType("247", "24/7", listOf("24/7")),
        ChannelType("ppv", "Pay-Per-View", listOf("pay-per", "ppv")),
        ChannelType("religion", "Religion", listOf("religio", "faith")),
        ChannelType("shopping", "Shopping", listOf("shopping")),
    )

    private val international = listOf("es" to "Spanish", "CA" to "Canada", "UK" to "United Kingdom", "caribbean" to "Caribbean")

    private val marketPatterns = markets.map { market ->
        market to Regex("\\b(" + market.places.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)
    }
    private val adultPattern = Regex("\\b(adult|xxx)\\b", RegexOption.IGNORE_CASE)

    /** Category order for the category list: types, locals, international, then others A–Z. */
    private val orderedTitles: List<String> =
        types.map { it.title } + markets.map { it.title } + OTHER_LOCAL + international.map { it.second } + LIVE_EVENTS

    fun rank(category: String): Int = TotalEclipseLiveLayout.rank(category)
        ?: orderedTitles.indexOfFirst { it.equals(category, ignoreCase = true) }
            .let { if (it < 0) Int.MAX_VALUE else it }

    /** The default order only (for laying out the TVs' arrangement). */
    fun defaultRank(category: String): Int = orderedTitles.indexOfFirst { it.equals(category, ignoreCase = true) }
        .let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * The Apple TVs' key for a category title (HomeTVLiveGroups): "\u0001smart.type.news",
     * "\u0001smart.local.miami", "\u0001smart.region.CA"…; a provider group keeps its name.
     */
    fun tvKey(title: String): String {
        types.firstOrNull { it.title == title }?.let { return "\u0001smart.type.${it.id}" }
        markets.firstOrNull { it.title == title }?.let { return "\u0001smart.local.${it.id}" }
        if (title == OTHER_LOCAL) return "\u0001smart.local.other"
        international.firstOrNull { it.second == title }?.let { return "\u0001smart.region.${it.first}" }
        return title
    }

    const val HOME_MARKET_KEY = "\u0001smart.local.miami"
    const val KIDS_KEY = "\u0001smart.type.kids"

    /**
     * [labels] holds each channel's `hometv-*` attributes, by channel id. Returns the channels
     * with HomeTV's category as their group (adult ones dropped), or unchanged without labels.
     */
    fun regroup(channels: List<LiveTvChannel>, labels: Map<String, Map<String, String>>): List<LiveTvChannel> {
        if (labels.isEmpty()) return channels
        // Groups mostly made of event slots: their few regular channels are sports networks.
        val groupTotal = HashMap<String, Int>()
        val groupEvents = HashMap<String, Int>()
        for (channel in channels) {
            val l = labels[channel.id] ?: continue
            val group = channel.group ?: "Other"
            groupTotal[group] = (groupTotal[group] ?: 0) + 1
            if (l["hometv-kind"] == "event") groupEvents[group] = (groupEvents[group] ?: 0) + 1
        }
        val eventGroups = groupTotal.filter { (group, total) -> (groupEvents[group] ?: 0) >= total * 0.6 }.keys
        val regionTitles = international.toMap()

        return channels.mapNotNull { channel ->
            val l = labels[channel.id] ?: return@mapNotNull channel
            val group = channel.group ?: "Other"
            val kind = l["hometv-kind"]
            val category = categoryOverride(l["hometv-category"])
                ?: when {
                    adultPattern.containsMatchIn(group) -> return@mapNotNull null
                    kind == "local" -> marketOf(channel) ?: OTHER_LOCAL
                    l["hometv-region"]?.let { it != "US" && it in regionTitles } == true -> regionTitles.getValue(l.getValue("hometv-region"))
                    l["hometv-lang"] == "es" ||
                        listOf("spanish", "telemundo", "univision").any { group.contains(it, ignoreCase = true) } -> "Spanish"
                    group.contains("caribbean", ignoreCase = true) -> "Caribbean"
                    else -> typeOf(group)
                        ?: when {
                            group.contains("local", ignoreCase = true) -> "Entertainment" // CW, MyNetwork
                            group in eventGroups -> if (kind == "event") LIVE_EVENTS else "Sports Networks"
                            kind == "event" -> LIVE_EVENTS
                            else -> displayName(group)
                        }
                }
            channel.copy(group = category)
        }
    }

    private fun categoryOverride(value: String?): String? {
        val parts = value?.split(":", limit = 2)?.takeIf { it.size == 2 } ?: return null
        return when (parts[0]) {
            "type" -> types.firstOrNull { it.id == parts[1] }?.title
            "market" -> markets.firstOrNull { it.id == parts[1] }?.title
            "region" -> international.firstOrNull { it.first == parts[1] }?.second
            "local" -> if (parts[1] == "other") OTHER_LOCAL else null
            else -> null
        }
    }

    private fun marketOf(channel: LiveTvChannel): String? {
        val text = "${channel.name} ${channel.group.orEmpty()}"
        return marketPatterns.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first?.title
    }

    private fun typeOf(group: String): String? {
        val g = group.lowercase()
        return types.firstOrNull { t -> t.words.any { g.contains(it) } }?.title
    }

    private fun displayName(group: String): String {
        for (prefix in listOf("US - ", "USA - ")) {
            if (group.startsWith(prefix) && group.length > prefix.length) return group.removePrefix(prefix)
        }
        return group
    }
}
