package com.xw.vvtts.update

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM topic strings used for update push notifications, and the preference-driven
 * subscription logic that decides which topics (if any) this device receives.
 *
 * Serially mirrors the Morphe Manager model for this project's release pair:
 *
 * | Topic                     | Audience                                     |
 * |---------------------------|----------------------------------------------|
 * | [FcmTopics.STABLE]        | Stable releases (no hyphen in the tag)       |
 * | [FcmTopics.DEV]           | Prerelease builds (hyphenated tag, e.g. rc1) |
 *
 * No topic at all is subscribed when notifications are disabled. Stable is always
 * subscribed when notifications are on; the prerelease topic requires explicit opt-in —
 * matching "you choose whether to receive those updates or not".
 *
 * Safe to call repeatedly; FCM deduplicates subscribe/unsubscribe internally.
 */
object FcmTopics {
    const val STABLE = "eloquence_updates"
    const val DEV = "eloquence_updates_dev"

    val all = listOf(STABLE, DEV)
}

/**
 * Synchronizes the FCM topic subscriptions with the user's current preferences.
 *
 * | notificationsEnabled | usePrereleases | result                                  |
 * |----------------------|----------------|-----------------------------------------|
 * | false                | any            | unsubscribe from all topics             |
 * | true                 | false          | subscribe to stable only                |
 * | true                 | true           | subscribe to stable AND prerelease      |
 */
fun syncFcmTopics(context: Context) {
    val prefs = UpdateNotifPrefs.get(context)
    val notificationsEnabled = prefs.getBoolean(UpdateNotifPrefs.NOTIFICATIONS_ENABLED, true)
    val usePrereleases = prefs.getBoolean(UpdateNotifPrefs.USE_PRERELEASES, false)
    val messaging = FirebaseMessaging.getInstance()

    if (!notificationsEnabled) {
        FcmTopics.all.forEach { topic ->
            messaging.unsubscribeFromTopic(topic)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) Log.w(TAG, "unsubscribe($topic) failed", task.exception)
                }
        }
        return
    }

    messaging.subscribeToTopic(FcmTopics.STABLE)
        .addOnCompleteListener { task ->
            if (!task.isSuccessful) Log.w(TAG, "subscribe(${FcmTopics.STABLE}) failed", task.exception)
        }
    if (usePrereleases) {
        messaging.subscribeToTopic(FcmTopics.DEV)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) Log.w(TAG, "subscribe(${FcmTopics.DEV}) failed", task.exception)
            }
    } else {
        messaging.unsubscribeFromTopic(FcmTopics.DEV)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) Log.w(TAG, "unsubscribe(${FcmTopics.DEV}) failed", task.exception)
            }
    }
}

private const val TAG = "FcmTopicSync"