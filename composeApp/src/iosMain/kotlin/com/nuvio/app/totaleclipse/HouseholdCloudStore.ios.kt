package com.nuvio.app.totaleclipse

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUbiquitousKeyValueStore
import platform.Foundation.NSUbiquitousKeyValueStoreDidChangeExternallyNotification
import platform.Foundation.NSUserDefaults
import platform.Foundation.create

@OptIn(BetaInteropApi::class)
internal actual object HouseholdCloudStore {
    private val store: NSUbiquitousKeyValueStore
        get() = NSUbiquitousKeyValueStore.defaultStore

    private var observer: Any? = null

    actual val isAvailable: Boolean = true

    actual fun refresh() {
        store.synchronize()
    }

    actual fun keys(): Set<String> =
        store.dictionaryRepresentation.keys.mapNotNull { it as? String }.toSet()

    actual fun text(key: String): String? =
        when (val value = store.objectForKey(key)) {
            is String -> value
            is NSData -> NSString.create(data = value, encoding = NSUTF8StringEncoding)?.toString()
            else -> null
        }

    actual fun stringSettings(key: String): Map<String, String> {
        val dictionary = store.dictionaryForKey(key) ?: return emptyMap()
        val out = HashMap<String, String>()
        for ((name, value) in dictionary) {
            if (name is String && value is String) out[name] = value
        }
        return out
    }

    actual fun boolSettings(key: String): Map<String, Boolean> {
        val dictionary = store.dictionaryForKey(key) ?: return emptyMap()
        val out = HashMap<String, Boolean>()
        for ((name, value) in dictionary) {
            if (name !is String) continue
            when (value) {
                is Boolean -> out[name] = value
                is platform.Foundation.NSNumber -> out[name] = value.boolValue
                is Number -> out[name] = value.toInt() != 0
            }
        }
        return out
    }

    actual fun observe(onChange: () -> Unit) {
        if (observer != null) return
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSUbiquitousKeyValueStoreDidChangeExternallyNotification,
            `object` = store,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> onChange() }
    }

    actual fun loadLocal(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    actual fun saveLocal(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }
}
