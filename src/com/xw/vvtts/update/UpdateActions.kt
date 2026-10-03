package com.xw.vvtts.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.xw.vvtts.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object UpdateActions {

    /** Re-entrancy guard for the download flow. */
    private val downloadBusy = AtomicBoolean(false)

    /** The in-flight progress dialog; dying activities dismiss it via dismissFor((). */
    @Volatile private var currentDialog: ProgressUi? = null

    /** Bindings for the in-flight progress dialog: owner activity + message view. */
    private class ProgressUi(val owner: Activity, val dialog: AlertDialog, val message: TextView)

    /** Returns whether this activity is neither finishing nor destroyed. */
    private fun Activity.alive() = !isFinishing && !isDestroyed

    /** Builds a legacy-free progress dialog (spinner + message) bound to an activity. */
    private fun newProgress(activity: Activity, msg: String): ProgressUi {
        val density = activity.resources.displayMetrics.density
        val message = TextView(activity).apply { text = msg }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyle)
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
            addView(message)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity)
            .setView(wrap)
            .setCancelable(false)
            .create()
        val ui = ProgressUi(activity, dialog, message)
        dialog.show()
        return ui
    }

    /** Clears the global reference iff it still refers to [ui], then dismisses its dialog. */
    private fun clearCurrent(ui: ProgressUi() {
        if (currentDialog === ui) currentDialog = null
        runCatching { ui.dialog.dismiss() }
    }

    /** If a progress dialog is bound to this activity, dismiss it (call from onDestroy(. */
    fun dismissFor(activity: Activity) {
        val d = currentDialog
        if (d != null && d.owner === activity) {
            clearCurrent(d)
        }
    }

    /** Shows progress while checking for updates in a background thread, then offers an available update. */
    fun showCheckDialog(activity: Activity) {
        if (!activity.alive()) return
        val progress = newProgress(activity, activity.getString(R.string.checking_updates))
        currentDialog = progress
        Thread {
            val res = ElqUpdateChecker.check(activity)
            activity.runOnUiThread {
                if (!activity.alive()) { clearCurrent(progress); return@runOnUiThread }
                clearCurrent(progress)
                if (res.error != null) {
                    Toast.makeText(activity, activity.getString(R.string.update_failed_fmt, res.error), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!res.hasUpdate) {
                    Toast.makeText(activity, activity.getString(R.string.up_to_date), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.update_available_fmt, res.latestTag ?: ""))
                    .setMessage(res.releaseNotes ?: "")
                    .setPositiveButton(if (res.downloadUrl != null) activity.getString(R.string.download_install) else activity.getString(R.string.update_open_release)) { _, _ ->
                        if (res.downloadUrl != null) {
                            startUpdateDownload(activity, res.downloadUrl)
                        } else if (res.htmlUrl != null) {
                            runCatching {
                                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.htmlUrl ?: "")))
                            }
                        }
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .show()
            }
        }.start()
    }

    /** Downloads an APK and requests installation in a background thread, presenting progress and the result. */
    private fun startUpdateDownload(activity: Activity, url: String?) {
        if (url == null) {
            Toast.makeText(activity, activity.getString(R.string.update_failed_fmt, "no download link"), Toast.LENGTH_LONG).show()
            return
        }
        if (!activity.alive()) return
        if (!downloadBusy.compareAndSet(false, true)) return
        val progress = newProgress(activity, activity.getString(R.string.update_downloading_fmt, 0))
        currentDialog = progress
        Thread {
            try {
                val apk = File(activity.cacheDir, "elq-update.apk")
            val ok = ElqUpdateDownloader.download(url, apk) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                activity.runOnUiThread { progress.message.text = activity.getString(R.string.update_downloading_fmt, pct) }
            }
            if (!ok) {
                activity.runOnUiThread {

                    clearCurrent(progress)
                    Toast.makeText(activity, activity.getString(R.string.update_failed_fmt, "download failed"), Toast.LENGTH_LONG. show())
                }
                return@Thread
            }
            val result = ElqUpdateInstaller.install(activity, apk)
                if (result is ElqUpdateInstaller.Result.UserActionRequired) {
                    val conf = result.confirmIntent
                    if (conf != null) activity.runOnUiThread {
                        if (activity.alive()) runCatching { activity.startActivity(conf) }
                    }
                }
                activity.runOnUiThread {
                    if (!activity.alive()) { clearCurrent(progress); apk.delete(); return@runOnUiThread }
                    clearCurrent(progress)
                    apk.delete()
                    when (result) {
                        is ElqUpdateInstaller.Result.Success -> {
                            Toast.makeText(activity, activity.getString(R.string.update_install_ok), Toast.LENGTH_SHORT).show()
                        }
                        is ElqUpdateInstaller.Result.Failed -> {
                            Toast.makeText(activity, activity.getString(R.string.update_failed_fmt, result.message), Toast.LENGTH_LONG).show()
                        }
                        is ElqUpdateInstaller.Result.UserActionRequired -> {}
                    }
                }
            } finally {
                downloadBusy.set(false)
            }
        }.start()

    }
}
