package com.nuvio.app.totaleclipse

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Total Eclipse: as on the Apple TVs (HomeTVPlaybackEnd), a film that ends lands on the next
 * film of its collection (TMDB's, in release order) instead of the last frame.
 */
internal object TotalEclipseNextFilm {
    private val _open = MutableStateFlow<MetaPreview?>(null)
    /** A film's page to open once the player has closed. */
    val open: StateFlow<MetaPreview?> = _open.asStateFlow()

    fun request(film: MetaPreview) { _open.value = film }

    fun consume(): MetaPreview? = _open.value.also { _open.value = null }

    /** The film after [meta] in its collection, or null (none, the last one, or not found). */
    fun after(meta: MetaDetails?): MetaPreview? {
        val films = meta?.collectionItems.orEmpty()
        if (films.size < 2) return null
        val index = films.indexOfFirst { it.id == meta!!.id }
            .takeIf { it >= 0 }
            ?: films.indexOfFirst { it.name.trim().equals(meta!!.name.trim(), ignoreCase = true) }
        if (index < 0) return null
        return films.getOrNull(index + 1)
    }
}
