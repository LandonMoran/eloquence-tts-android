package com.xw.vvtts.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.xw.vvtts.R
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** UI ownership stays on the main thread. Workers retain only application context
 * and a weak owner, and stale generations cannot dismiss or update another flow. */
object UpdateActions {
    private val main = Handler(Looper.getMainLooper())
    private class Flow(activity: Activity) {
        val owner = WeakReference(activity)
        val cancelled = AtomicBoolean(false)
        var worker: Thread? = null
    }
    private class ProgressUi(val dialog: AlertDialog, val message: TextView?)
    private var current: Flow? = null
    private var currentDialog: ProgressUi? = null

    private fun Activity.alive() = !isFinishing && !isDestroyed

    private fun progress(activity: Activity, message: String) {
        currentDialog?.dialog?.dismiss()
        val text = TextView(activity).apply { this.text = message }
        val density = activity.resources.displayMetrics.density
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), (12 * density).toInt())
            addView(text)
            addView(ProgressBar(activity))
        }
        val dialog = AlertDialog.Builder(activity).setView(layout).setCancelable(false).create()
        currentDialog = ProgressUi(dialog, text)
        dialog.show()
    }

    private fun finish(flow: Flow) {
        if (current !== flow) return
        currentDialog?.dialog?.dismiss()
        currentDialog = null
        current = null
    }

    fun dismissFor(activity: Activity) {
        val flow = current ?: return
        if (flow.owner.get() !== activity) return
        flow.cancelled.set(true)
        flow.owner.clear()
        flow.worker?.interrupt()
        finish(flow)
    }

    private fun post(flow: Flow, action: (Activity) -> Unit) {
        main.post {
            if (current === flow && !flow.cancelled.get()) {
                val owner = flow.owner.get()
                if (owner != null && owner.alive()) action(owner) else finish(flow)
            }
        }
    }

    fun showCheckDialog(activity: Activity) {
        if (!activity.alive() || current != null) return
        val flow = Flow(activity)
        val app = activity.applicationContext
        current = flow
        progress(activity, activity.getString(R.string.checking_updates))
        flow.worker = Thread {
            val result = ElqUpdateChecker.check(app)
            post(flow) { owner -> showResult(owner, flow, result) }
        }.apply { name = "update-check"; isDaemon = true; start() }
    }

    private fun showResult(activity: Activity, flow: Flow, result: ElqUpdateChecker.UpdateResult) {
        currentDialog?.dialog?.dismiss()
        currentDialog = null
        if (result.error != null || !result.hasUpdate) {
            val message = if (result.error != null) activity.getString(R.string.update_failed_fmt, result.error)
                else activity.getString(R.string.up_to_date)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            finish(flow)
            return
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_fmt, result.latestTag ?: ""))
            .setMessage(result.releaseNotes ?: "")
            .setPositiveButton(if (result.downloadUrl != null) R.string.download_install else R.string.update_open_release) { _, _ ->
                if (result.downloadUrl != null) startDownload(activity, flow, result.downloadUrl)
                else {
                    result.htmlUrl?.let { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                    finish(flow)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish(flow) }
            .setOnCancelListener { finish(flow) }.create()
        currentDialog = ProgressUi(dialog, null)
        dialog.show()
    }

    private fun startDownload(activity: Activity, flow: Flow, url: String) {
        if (current !== flow || !activity.alive()) return
        progress(activity, activity.getString(R.string.update_downloading_fmt, 0))
        // A separate function prevents the worker closure from capturing the Activity.
        download(activity.applicationContext, flow, url)
    }

    private fun download(app: Context, flow: Flow, url: String) {
        flow.worker = Thread {
            var apk: File? = null
            try {
                val file = File.createTempFile("elq-update-", ".apk", app.cacheDir)
                apk = file
                val ok = ElqUpdateDownloader.download(url, file) { done, total ->
                    if (flow.cancelled.get()) throw InterruptedException("Update owner destroyed")
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    post(flow) { owner -> currentDialog?.message?.text = owner.getString(R.string.update_downloading_fmt, percent) }
                }
                if (flow.cancelled.get()) return@Thread
                val result = if (ok) ElqUpdateInstaller.install(app, file)
                    else ElqUpdateInstaller.Result.Failed(null, "download failed")
                post(flow) { owner ->
                    finish(flow)
                    when (result) {
                        is ElqUpdateInstaller.Result.UserActionRequired -> result.confirmIntent?.let { runCatching { owner.startActivity(it) } }
                        is ElqUpdateInstaller.Result.Success -> Toast.makeText(owner, R.string.update_install_ok, Toast.LENGTH_SHORT).show()
                        is ElqUpdateInstaller.Result.Failed -> Toast.makeText(owner, owner.getString(R.string.update_failed_fmt, result.message), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                post(flow) { owner ->
                    finish(flow)
                    Toast.makeText(owner, owner.getString(R.string.update_failed_fmt, e.message ?: "update failed"), Toast.LENGTH_LONG).show()
                }
            } finally {
                apk?.delete()
            }
        }.apply { name = "update-download"; isDaemon = true; start() }
    }
}
