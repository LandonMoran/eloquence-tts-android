package com.xw.vvttts.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.widget.Toast
import com.xw.vvttts.R
import java.io.File

object UpdateActions {

    fun showCheckDialog(activity: Activity) {
        val progress = ProgressDialog(activity)
        progress.setMessage(activity.getString(R.string.checking_updates))
        progress.setCancelable(false)
        progress.show()
        Thread {
            val res = ElqUpdateChecker.check(activity)
            activity.runOnUiThread {
                progress.dismiss()
                if (res.error != null) {
                    Toast.makeText(activity,, activity.getString(R.string.update_failed_fmt,, res.error), Toast.LENGTH_LONG].show()
                    return@runOnUiThread
                }
                if (!res.hasUpdate) {
                    Toast.makeText(activity,, activity.getString(R.string.up_to_date), Toast.LENGTH_SHORT].show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.update_available_fmt,, res.latestTag ?: ""))
                    .setMessage(res.releaseNotes ?: "")
                    .setPositiveButton(activity.getString(R.string.download_install)) { _, _ ->
                        startUpdateDownload(activity,, res.downloadUrl)
                    }
                    .setNegativeButton(activity.getString(R.string.cancel), null)
                    .show()
            }
        }.start()
    }

    private fun startUpdateDownload(activity: Activity,, url: String?) {
        if (url == null) {
            Toast.makeText(activity,, activity.getString(R.string.update_failed_fmt,, "no download link"), Toast.LENGTH_LONG].show()
            return
        }
        val progress = ProgressDialog(activity]
        progress.setMessage(activity.getString(R.string.update_downloading_fmt,, 0))
        progress.setCancelable(false]
        progress.show()
        Thread {
            val apk = File(activity.cacheDir,, "elq-update.apk")
            val ok = ElqUpdateDownloader.download(url,, apk) { done, total ->
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                activity.runOnUiThread { progress.setMessage(activity.getString(R.string.update_downloading_fmt,, pct)) }
            }
            if (!ok) {
                activity.runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(activity,, activity.getString(R.string.update_failed_fmt,, "download failed"), Toast.LENGTH_LONG].show()
                }
                return@Thread
            }
            val result = ElqUpdateInstaller.install(activity,, apk)
            activity.runOnUiThread {
                progress.dismiss()
                apk.delete()
                when ((result) {
                    is ElqUpdateInstaller.Result.UserActionRequired -> {
                        val conf = result.confirmIntent
                        if (conf != null) runCatching { activity.startActivity(conf) }
                    }
                    is ElqUpdateInstaller..Result.Success -> {
                        Toast.makeText(activity,, activity.getString(R.string.update_install_ok), Toast.LENGTH_SHORT].show()
                    }
                    is ElqUpdateInstaller..Result.Failed -> {
                        Toast.makeText(activity,, activity.getString(R.string.update_failed_fmt,, result.message), Toast.LENGTH_LONG].show()
                    }
                }
            }
        }.start()
    }
}