package com.xw.vvtts.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by the daily AlarmManager alarm. Runs the release check off the main thread
 * (ElqUpdateChecker does network I/O) and posts a high-importance notification when a
 * newer version is available and the check succeeded. Registered in the manifest with
 * exported=false so only the app's own pending intent (and its own process) can trigger it.
 */
class UpdateNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                val result = ElqUpdateChecker.check(app)
                // Only notify on a genuine, error-free update result; suppress for
                // transient network failures and "no stable release found".
                if (result.hasUpdate && result.error == null) {
                    UpdateNotificationScheduler.postNotification(app, result.latestTag ?: "?")
                }
            } catch (t: Throwable) {
                Log.e("UpdateNotificationReceiver", "background update check failed", t)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; name = "update-notify-check" }.start()
    }
}