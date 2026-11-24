package com.nuvio.app.totaleclipse

/** The Total Eclipse household store is iCloud; Android has no access to it. */
internal actual object HouseholdCloudStore {
    actual val isAvailable: Boolean = false
    actual fun refresh() = Unit
    actual fun keys(): Set<String> = emptySet()
    actual fun text(key: String): String? = null
    actual fun stringSettings(key: String): Map<String, String> = emptyMap()
    actual fun boolSettings(key: String): Map<String, Boolean> = emptyMap()
    actual fun observe(onChange: () -> Unit) = Unit
    actual fun loadLocal(key: String): String? = null
    actual fun saveLocal(key: String, value: String) = Unit
}
