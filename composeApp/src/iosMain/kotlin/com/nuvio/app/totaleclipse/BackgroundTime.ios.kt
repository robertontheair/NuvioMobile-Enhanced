package com.nuvio.app.totaleclipse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

internal actual suspend fun <T> withBackgroundTime(name: String, block: suspend () -> T): T {
    val identifier = withContext(Dispatchers.Main) {
        var id: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        id = UIApplication.sharedApplication.beginBackgroundTaskWithName(name) {
            if (id != UIBackgroundTaskInvalid) {
                UIApplication.sharedApplication.endBackgroundTask(id)
                id = UIBackgroundTaskInvalid
            }
        }
        id
    }
    try {
        return block()
    } finally {
        if (identifier != UIBackgroundTaskInvalid) {
            withContext(Dispatchers.Main) { UIApplication.sharedApplication.endBackgroundTask(identifier) }
        }
    }
}
