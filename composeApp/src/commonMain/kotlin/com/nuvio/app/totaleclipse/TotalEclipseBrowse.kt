package com.nuvio.app.totaleclipse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.FolderDetailStore
import com.nuvio.app.features.collection.FolderDetailUiState
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeUiState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/**
 * Total Eclipse: the Movies and Shows tabs, as on the Apple TVs.
 *
 * Each is the Home screen fed by one folder of the household's "Browse" collection (synced from
 * the TVs): its catalogs become the rows, the hero keeps Home's picks of that type, and Continue
 * Watching keeps only that type.
 */
enum class TotalEclipseBrowseKind(val contentTypes: Set<String>, private val titleWords: List<String>) {
    Movies(setOf("movie"), listOf("movie", "film")),
    Shows(setOf("series", "tv", "show"), listOf("show", "series", "tv"));

    internal fun matchesTitle(title: String): Boolean {
        val lower = title.lowercase()
        return titleWords.any { lower.contains(it) }
    }

    fun includesType(type: String?): Boolean = type?.lowercase() in contentTypes
}

internal object TotalEclipseBrowse {
    private val stores = mutableMapOf<TotalEclipseBrowseKind, FolderDetailStore>()

    private fun store(kind: TotalEclipseBrowseKind): FolderDetailStore =
        stores.getOrPut(kind) { FolderDetailStore() }

    /** The folder for this tab: in a collection titled "Browse" first, then in any collection. */
    internal fun findFolder(collections: List<Collection>, kind: TotalEclipseBrowseKind): Pair<String, String>? {
        val ordered = collections.sortedBy { if (it.title.trim().equals("browse", ignoreCase = true)) 0 else 1 }
        for (collection in ordered) {
            val folder = collection.folders.firstOrNull { kind.matchesTitle(it.title) } ?: continue
            return collection.id to folder.id
        }
        return null
    }

    /** Home's state for this tab: the folder's catalogs as rows, hero items of this type. */
    @Composable
    fun rememberHomeUiState(kind: TotalEclipseBrowseKind, home: HomeUiState): HomeUiState {
        val collections by CollectionRepository.collections.collectAsStateWithLifecycle()
        val target = remember(collections, kind) { findFolder(collections, kind) }
        val store = remember(kind) { store(kind) }
        LaunchedEffect(target) {
            CollectionRepository.initialize()
            target?.let { (collectionId, folderId) -> store.initialize(collectionId, folderId) }
        }
        val folderState by store.uiState.collectAsStateWithLifecycle()
        val personal by TotalEclipsePersonalRows.state(kind).collectAsStateWithLifecycle()
        LaunchedEffect(kind) { TotalEclipsePersonalRows.refresh(kind) }
        return remember(folderState, home.heroItems, target, personal) {
            val (pinned, personalRows) = personal.toSections(kind)
            val sections = arrangeLikeTheTVs(
                catalogs = tvTitles(store.getCatalogSectionsForRows(), folderState),
                pinned = pinned.map(::withoutViewAll),
                personal = personalRows.map(::withoutViewAll),
            )
            // As on the TVs (CollectionHomeModel: hero = the first row's titles): the hero is the
            // first Browse catalog (e.g. Trending), not Home's hero picks.
            val firstCatalog = tvTitles(store.getCatalogSectionsForRows(), folderState)
                .let { arrangeLikeTheTVs(it) }.firstOrNull()
            val hero = firstCatalog?.items?.take(10).orEmpty()
            HomeUiState(
                isLoading = target != null && folderState.isLoading && sections.isEmpty(),
                heroItems = hero,
                sections = sections,
                errorMessage = if (target == null && collections.isNotEmpty()) {
                    "No \"${kind.name}\" folder in your collections yet."
                } else {
                    null
                },
            )
        }
    }

    /**
     * Row names as on the TVs (HomeTV CollectionSourceResolver.label): the source's own title
     * when the collection gives one, otherwise the catalog's name, with no "Series" / "Movies"
     * type line under it.
     */
    private fun tvTitles(
        sections: List<HomeCatalogSection>,
        state: FolderDetailUiState,
    ): List<HomeCatalogSection> {
        val folderId = state.folder?.id ?: return sections
        val tabsByKey = state.tabs.associateBy { "folder_${folderId}_${it.label}" }
        return sections.map { section ->
            val tab = tabsByKey[section.key]
            val title = tab?.source?.title?.trim()?.takeIf(String::isNotEmpty) ?: section.title
            section.copy(title = title, subtitle = "")
        }
    }

    /**
     * HomeTV's row order (HomeTVRowOrder.arrange, Rob 2026-11-19): the first catalog stays first;
     * Popular, Top Rated and New Episodes Today (slowest to load, and they overlap Trending) go
     * last, in that order.
     */
    internal fun arrangeLikeTheTVs(
        catalogs: List<HomeCatalogSection>,
        pinned: List<HomeCatalogSection> = emptyList(),
        personal: List<HomeCatalogSection> = emptyList(),
    ): List<HomeCatalogSection> {
        fun starts(section: HomeCatalogSection, prefix: String) = section.title.trim().lowercase().startsWith(prefix)
        val isPopular = { s: HomeCatalogSection -> starts(s, "popular") }
        val isTopRated = { s: HomeCatalogSection -> starts(s, "top rated") }
        val isNewToday = { s: HomeCatalogSection -> starts(s, "new episodes today") }
        val isSlow = { s: HomeCatalogSection -> isPopular(s) || isTopRated(s) || isNewToday(s) }
        val first = catalogs.firstOrNull()?.takeUnless(isSlow)
        val middle = catalogs.filter { !isSlow(it) && it !== first }
        val bottom = catalogs.filter(isPopular) + catalogs.filter(isTopRated) + catalogs.filter(isNewToday)
        // Pinned (Watchlist, New This Week), Top 10, the first catalog, then the other
        // personal rows (Because you finished, Recommended, Because you're watching, Coming Soon).
        val top10 = personal.filter { it.key.startsWith("totaleclipse_top10") }
        val otherPersonal = personal - top10.toSet()
        return pinned + top10 + listOfNotNull(first) + otherPersonal + middle + bottom
    }

    /** Personal rows aren't catalogs: no "View all" (Home shows it past 18 items). */
    private fun withoutViewAll(section: HomeCatalogSection): HomeCatalogSection =
        section.copy(availableItemCount = minOf(section.items.size, 18), hasMore = false)

    /** One enabled row per folder catalog, in the folder's order. */
    fun homeItems(home: HomeUiState): List<HomeCatalogSettingsItem> =
        home.sections.mapIndexed { index, section ->
            HomeCatalogSettingsItem(
                key = section.key,
                defaultTitle = section.title,
                addonName = section.addonName,
                enabled = true,
                heroSourceEnabled = index == 0,
                order = index,
            )
        }

    fun continueWatching(kind: TotalEclipseBrowseKind, items: List<ContinueWatchingItem>): List<ContinueWatchingItem> =
        items.filter { kind.includesType(it.parentMetaType) }
}
