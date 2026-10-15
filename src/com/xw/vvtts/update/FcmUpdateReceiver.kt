package com.xw.vvtts.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.xw.vvtts.R
import com.xw.vvtts.ui.SettingsActivity

/**
 * Receives release notifications pushed by CI (release.yml -> send_fcm.py) over
 * Firebase Cloud Messaging and surfaces them as a high-importance update dialog.
 *
 * The server sends a data-only message to topic `eloquence_updates` (stable
 * releases) or `eloquence_updates_dev` (pre-releases). We subscribe to both so
 * a device always receives what applies to its installed channel; a pre-release
 * push simply never arrives unless one is published.
 *
 * Tap does not open a web page — it opens SettingsActivity, which forwards the
 * same EXTRA to UpdateActions.showCheckDialog so the system notification leads
 * to the exact dialog a manual check would show.
 */
class FcmUpdateReceiver : FirebaseMessagingService() {

    /** Android ignores unknown topics; subscribe so pushes can route to us. */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val topics = listOf(TOPIC_STABLE, TOPIC_DEV)
        for (t in topics) {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance()
                    .subscribeToTopic(t)
                    .addOnCompleteListener { task ->
                        if (!task.isSuccessful) {
                            Log.w(TAG, "subscribeToTopic($t) failed", task.exception)
                        }
                    }
            } catch (rejected: Exception) {
                Log.w(TAG, "subscribeToTopic($t) rejected: ${rejected.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data ?: return
        if (data["type"] != "release") return
        val version = data["version"] ?: return
        postUpdateNotification(version)
    }

    private fun postUpdateNotification(version: String) {
        manager()?.let { ensureChannel(it) }

        val openDialog = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SettingsActivity.EXTRA_OPEN_UPDATE_DIALOG, true)
        val pending = PendingIntent.getActivity(
            this, REQUEST_DIALOG, openDialog,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val text = getString(R.string.update_available_fmt, version)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.update_notification_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        val id = NOTIFICATION_ID
        try {
            manager()?.notify(id, builder.build())
        } catch (security: SecurityException) {
            // POST_NOTIFICATIONS revoked since backgrounding the dialog;
            // fall back to a Toast rather than crash the receive callback.
            val fallback = "${
                getString(R.string.update_notification_title)
            }: $version"
            Toast.makeText(this, fallback, Toast.LENGTH_LONG).show()
        }
    }

    private fun manager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_HIGH)
            channel.description = getString(R.string.update_channel_desc)
            manager.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val TAG = "FcmUpdateReceiver"
        const val CHANNEL_ID = "eloquence_updates"
        const val TOPIC_STABLE = "eloquence_updates"
        const val TOPIC_DEV = "eloquence_updates_dev"
        const val REQUEST_DIALOG = 5539
        const val NOTIFICATION_ID = 5539
    }
}