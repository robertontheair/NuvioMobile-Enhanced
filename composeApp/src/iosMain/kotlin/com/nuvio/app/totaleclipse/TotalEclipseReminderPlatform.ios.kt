package com.nuvio.app.totaleclipse

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSUserDefaults
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

internal actual object TotalEclipseReminderPlatform {
    private const val KEY = "totaleclipse.reminders"
    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    actual suspend fun schedule(id: String, title: String, body: String, atMillis: Long, link: String): Boolean {
        val seconds = (atMillis - kotlin.time.Clock.System.now().toEpochMilliseconds()) / 1000.0
        if (seconds < 1) return false
        val allowed = suspendCancellableCoroutine { cont ->
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
                cont.resume(granted)
            }
        }
        if (!allowed) return false
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf<Any?, Any?>("deeplink" to link))
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(seconds, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(id, content, trigger)
        return suspendCancellableCoroutine { cont ->
            center.addNotificationRequest(request) { error -> cont.resume(error == null) }
        }
    }

    actual fun cancel(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }

    actual fun loadIds(): Set<String> =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY)?.split('\n')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    actual fun saveIds(ids: Set<String>) {
        NSUserDefaults.standardUserDefaults.setObject(ids.joinToString("\n"), forKey = KEY)
    }
}
