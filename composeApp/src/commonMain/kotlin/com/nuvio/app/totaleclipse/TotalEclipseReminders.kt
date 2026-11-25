package com.nuvio.app.totaleclipse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Total Eclipse: "Remind me" for a Live TV programme that hasn't started, as on the Apple TVs:
 * a local notification when it begins. Reminders are remembered on the phone (to show the bell
 * filled) and forgotten once their time has passed.
 */
internal object TotalEclipseReminders {
    private val _scheduled = MutableStateFlow<Set<String>>(emptySet())
    val scheduled: StateFlow<Set<String>> = _scheduled.asStateFlow()
    private var loaded = false

    fun id(channelId: String, startMillis: Long): String = "te-reminder:$channelId:$startMillis"

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        _scheduled.value = TotalEclipseReminderPlatform.loadIds()
            .filter { it.substringAfterLast(':').toLongOrNull()?.let { start -> start > now } == true }
            .toSet()
        TotalEclipseReminderPlatform.saveIds(_scheduled.value)
    }

    suspend fun toggle(channelId: String, channelName: String, title: String, startMillis: Long) {
        ensureLoaded()
        val id = id(channelId, startMillis)
        if (id in _scheduled.value) {
            TotalEclipseReminderPlatform.cancel(id)
            _scheduled.value = _scheduled.value - id
        } else {
            val ok = TotalEclipseReminderPlatform.schedule(
                id = id,
                title = title,
                body = "Starting now on $channelName",
                atMillis = startMillis,
                link = com.nuvio.app.core.deeplink.buildLiveChannelDeepLinkUrl(channelId),
            )
            if (ok) _scheduled.value = _scheduled.value + id
        }
        TotalEclipseReminderPlatform.saveIds(_scheduled.value)
    }
}

internal expect object TotalEclipseReminderPlatform {
    /** Asks for permission when needed; false when it's refused or the time has passed. [link] opens on tap. */
    suspend fun schedule(id: String, title: String, body: String, atMillis: Long, link: String): Boolean
    fun cancel(id: String)
    fun loadIds(): Set<String>
    fun saveIds(ids: Set<String>)
}
