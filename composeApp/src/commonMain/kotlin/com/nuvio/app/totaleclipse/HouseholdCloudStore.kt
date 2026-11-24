package com.nuvio.app.totaleclipse

/**
 * Total Eclipse: the iCloud key-value store the Total Eclipse Apple TVs write
 * (identifier `B2Z256T95E.org.radiorob.hometv`, set in the app's entitlements).
 *
 * The phone only reads it. The TVs apply whatever arrives in their keys, so a write from
 * here could push phone defaults onto both TVs.
 */
internal expect object HouseholdCloudStore {
    /** False on platforms without the shared store (Android). */
    val isAvailable: Boolean

    /** Asks iCloud for the latest values; changes arrive through [observe]. */
    fun refresh()

    fun keys(): Set<String>

    /** A String value, or a Data value decoded as UTF-8 text. */
    fun text(key: String): String?

    /** The String values of a dictionary value (settings of other types are skipped). */
    fun stringSettings(key: String): Map<String, String>

    /** Calls [onChange] on the main thread when another device changes the store. */
    fun observe(onChange: () -> Unit)

    /** Phone-only bookkeeping (profile map, what was last applied). Never goes to iCloud. */
    fun loadLocal(key: String): String?
    fun saveLocal(key: String, value: String)
}
