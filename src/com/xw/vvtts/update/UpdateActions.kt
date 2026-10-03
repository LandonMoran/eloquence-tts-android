package com.xw.vvtts.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.xw.vvtts.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object UpdateActions {

    /** Re-entrancy guard for the download flow. */
    private val downloadBusy = AtomicBoolean(false)

    /** The in-flight progress dialog; dying activities dismiss it via dismissFor((). */
    @Volatile private var currentDialog: ProgressDialog? = null

    /** Returns whether this activity is neither finishing nor destroyed. */
    private fun Activity.alive() = !isFinishing && !isDestroyed

    /** If a progress dialog is bound to this activity, dismiss it (call from onDestroy(. */
    fun dismissFor(activity: Activity) {
        val d = currentDialog
        if (d != null && d.context === activity) {
            runCatching { d.dismiss() }
            currentDialog = null
        }
    }

    /** Shows progress while checking for updates in a background thread, then offers an available update. */
    fun showCheckDialog(activity: Activity) {
        if (!activity.alive()) return
        val progress = ProgressDialog(activity)
        progress.setMessage(activity.getString(R.string.checking_updates))
        progress.setCancelable(false)
        progress.show()
        currentDialog = progress
        Thread {
            val res = ElqUpdateChecker.check(activity)
            activity.runOnUiThread {
                if (!activity.alive()) { progress.dismiss(); return@runOnUiThread }
                progress.dismiss()
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
        val progress = ProgressDialog(activity)
        progress.setMessage(activity.getString(R.string.update_downloading_fmt, 0))
        progress.setCancelable(false)
        progress.show()
        currentDialog = progress
        Thread {
            try {
                val apk = File(activity.cacheDir, "elq-update.apk")
            val ok = ElqUpdateDownloader.download(url, apk) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                activity.runOnUiThread { progress.setMessage(activity.getString(R.string.update_downloading_fmt, pct)) }
            }
            if (!ok) {
                activity.runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.update_failed_fmt, "download failed"), Toast.LENGTH_LONG).show()
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
                    if (!activity.alive()) { progress.dismiss(); apk.delete(); return@runOnUiThread }
                    progress.dismiss()
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
