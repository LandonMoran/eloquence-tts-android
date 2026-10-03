package com.xw.vvtts.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ElqUpdateInstaller {

    sealed interface Result {
        data class UserActionRequired(val confirmIntent: Intent?): Result
        data class Success(val statusCode: Int): Result
        data class Failed(val statusCode: Int?, val message: String): Result
    }

    private const val ACTION_INSTALL_STATUS = "com.xw.vvtts.action.APP_UPDATE_INSTALL_STATUS"
    private const val INSTALL_TIMEOUT_MINUTES = 5L

    /**
     * Writes and commits [apkFile] through PackageInstaller, registering the status receiver before commit.
     *
     * Blocks for up to five minutes after commit; call from a background thread.
     * Returns a confirmation intent requiring user action, success, or failure including timeout.
     */
    fun install(context: Context, apkFile: File): Result {
        val appContext = context.applicationContext ?: context
        val pm = appContext.packageManager
        val sessionId = try {
            val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                ).apply {
                    setOriginatingUid(Process.myUid())
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.setRequestUpdateOwnership(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            pm.packageInstaller.createSession(params)
        } catch (e: Exception) {
            return Result.Failed(null, e.message ?: e.javaClass.simpleName)
        }
        val latch = CountDownLatch(1)
        var result: Result? = null
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        val receiver = object : BroadcastReceiver() {
            /** Converts an install status broadcast into a result, releases the waiting caller and unregisters this receiver. */
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_INSTALL_STATUS) return
                if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        result = Result.UserActionRequired(confirm)
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        result = Result.Success(status)
                    }
                    else -> {
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        result = Result.Failed(status, msg ?: "install_status_$status")
                    }
                }
                latch.countDown()
                runCatching { appContext.unregisterReceiver(this) }
            }
        }
        // Register BEFORE commit:the system broadcast can arrive the instant
        // commit fires, and a late registration would miss it and sit out the
        // entire install timeout (reported \"install hangs/never completes\").
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            return Result.Failed(null, t.message ?: t.javaClass.simpleName)
        }
        var committed = false
                try {
                    val session = pm.packageInstaller.openSession(sessionId)
                    try {
                        apkFile.inputStream().use { input ->
                            session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                                input.copyTo(output, 64 * 1024)
                                session.fsync(output)
                            }
                        }
                        session.commit(commitIntent(appContext, sessionId).intentSender)
                        committed = true
                    } finally {
                        session.close()
                    }
                } catch (e: Exception) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (!committed) {
                        runCatching { pm.packageInstaller.abandonSession(sessionId) }
                    }
                    return Result.Failed(null, e.message ?: e.javaClass.simpleName)
                }
        try {
            val ok = latch.await(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!ok) {
                runCatching { appContext.unregisterReceiver(receiver) }
                return Result.Failed(null, "install timed out")
            }
        } catch (t: Throwable) {
            runCatching { appContext.unregisterReceiver(receiver) }
            return Result.Failed(null, t.message ?: t.javaClass.simpleName)
        }
        runCatching { appContext.unregisterReceiver(receiver) }
        return result ?: Result.Failed(null, "install timed out")
    }

    /** Creates a mutable broadcast PendingIntent targeting this package, keyed by [sessionId], for install status. */
    private fun commitIntent(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
