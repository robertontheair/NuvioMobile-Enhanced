package com.nuvio.app.totaleclipse

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Total Eclipse: small player choices, per profile, kept on the phone. */
internal object TotalEclipsePlayerPrefs {
    private const val RATING = "totaleclipse.ratingPrompt."
    private const val HIDE_SPEED = "totaleclipse.hideSpeed."
    private val _ratingPrompt = MutableStateFlow(true)

    /** Offer a rating when a movie's or a season's credits start. On unless turned off. */
    val ratingPrompt: StateFlow<Boolean> = _ratingPrompt.asStateFlow()

    fun load() {
        _ratingPrompt.value = HouseholdCloudStore.loadLocal(RATING + ProfileRepository.activeProfileId) != "off"
    }

    fun setRatingPrompt(on: Boolean) {
        HouseholdCloudStore.saveLocal(RATING + ProfileRepository.activeProfileId, if (on) "on" else "off")
        _ratingPrompt.value = on
    }

    /** The TVs' "Show Speed" is off for this profile (Mark's simpler player). */
    val hidesSpeed: Boolean
        get() = HouseholdCloudStore.loadLocal(HIDE_SPEED + ProfileRepository.activeProfileId) == "yes"

    fun setHidesSpeed(profile: Int, hidden: Boolean) =
        HouseholdCloudStore.saveLocal(HIDE_SPEED + profile, if (hidden) "yes" else "no")
}

/**
 * When to offer a rating, as on the Apple TVs (HomeTVRatingPrompt): at the credits marker, or
 * without one in the last 90 seconds past 85%, never earlier.
 */
internal fun creditsHaveStarted(positionMs: Long, durationMs: Long, creditsStartMs: Long?): Boolean {
    if (durationMs <= 0L || positionMs <= 0L) return false
    if (creditsStartMs != null && creditsStartMs > durationMs / 2) return positionMs >= creditsStartMs
    return durationMs - positionMs <= 90_000L && positionMs >= durationMs * 85 / 100
}
