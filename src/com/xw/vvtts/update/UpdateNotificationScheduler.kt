package com.xw.vvtts.update

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

import com.xw.vvtts.ui.SettingsActivity

/**
 * Self-contained background update notifications: a once-per-day AlarmManager alarm
 * fires a manifest-registered receiver that runs the release check off the main thread
 * (ElqUpdateChecker performs network I/O) and posts a high-importance notification when
 * a newer version is available.
 *
 * The check is opt-in: Settings exposes a toggle that persists in "update_prefs" and
 * schedules or cancels the alarm. Only framework + existing code is used (no WorkManager,
 * no new jars) so this compiles under the plain build.sh pipeline.
 */
object UpdateNotificationScheduler {

    private const val TAG = "UpdateNotificationScheduler"

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_ENABLED = "notify_updates"
    private const val CHANNEL_ID = "update_notifications"
    private const val NOTIFICATION_ID = 1001
    private const val ALARM_REQUEST_CODE = 0x5577

    /** Notification permission required on API 33+; declared in the manifest. */
    const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * Intent extra that tells SettingsActivity the app was opened by tapping the update
     * notification, so it surfaces the update dialog instead of the plain onboarding screen.
     */
    const val EXTRA_OPEN_UPDATE_DIALOG = "com.xw.vvtts.extra.OPEN_UPDATE_DIALOG"

    /** Whether the background notification toggle is currently enabled. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** Persists the toggle state. */
    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "failed to persist notify_updates=$enabled", t)
        }
    }

    /**
     * Ensures the notification channel exists and schedules the daily alarm. Safe to call
     * repeatedly (a new pending intent replaces the old one); no-op if AlarmManager is absent.
     */
    fun schedule(context: Context) {
        createChannel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        // First check ~12h out, then daily; setInexactRepeating lets the system batch
        // the alarm and is doze-tolerant.
        val triggerAt = System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, AlarmManager.INTERVAL_DAY, pi)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to schedule update alarm", t)
        }
    }

    /** Cancels the daily alarm. Safe to call when nothing is scheduled. */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            am.cancel(pendingIntent(context))
        } catch (t: Throwable) {
            Log.e(TAG, "failed to cancel update alarm", t)
        }
    }

    /**
     * Posts the high-importance "new version available" notification. No-ops on API 33+
     * when POST_NOTIFICATIONS is not granted. Tap opens SettingsActivity with the update
     * dialog (the release page remains the dialog's secondary button).
     */
    fun postNotification(context: Context, versionTag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(PERMISSION_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(context.getString(com.xw.vvtts.R.string.update_notification_title))
                .setContentText(
                    context.getString(com.xw.vvtts.R.string.update_notification_text_fmt, versionTag))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
            try {
                val open = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    openUpdateDialogIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(open)
            } catch (ignore: Exception) {
                // Intent construction failed: still show the notification without a tap target.
            }
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Runtime permission revoked between the guard above and notify(): skip silently.
            Log.w(TAG, "POST_NOTIFICATIONS revoked, notification suppressed", e)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to post update notification", t)
        }
    }

    /**
     * Intent that (re)opens SettingsActivity and surfaces the update dialog, used as the
     * notification tap target. The activity distinguishes this via [EXTRA_OPEN_UPDATE_DIALOG].
     */
    internal fun openUpdateDialogIntent(context: Context): Intent {
        val intent = Intent(context, SettingsActivity::class.java)
        intent.putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return intent
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateNotificationReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(com.xw.vvtts.R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to create notification channel", t)
        }
    }
}