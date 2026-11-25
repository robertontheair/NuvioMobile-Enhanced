package com.nuvio.app.totaleclipse

internal actual object TotalEclipseReminderPlatform {
    actual suspend fun schedule(id: String, title: String, body: String, atMillis: Long, link: String): Boolean = false
    actual fun cancel(id: String) = Unit
    actual fun loadIds(): Set<String> = emptySet()
    actual fun saveIds(ids: Set<String>) = Unit
}
