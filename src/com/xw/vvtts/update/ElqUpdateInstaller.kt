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
        try {
            val session = pm.packageInstaller.openSession(sessionId)
            try {
                apkFile.inputStream().use { input ->
                    val output = session.openWrite("base.apk", 0, apkFile.length())
                    input.copyTo(output, 64 * 1024)
                    session.fsync(output)
                    output.close()
                }
                session.commit(commitIntent(appContext, sessionId).intentSender)
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            return Result.Failed(null, e.message ?: e.javaClass.simpleName)
        }
        return awaitStatus(appContext, sessionId)
    }

    private fun commitIntent(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun awaitStatus(context: Context, sessionId: Int): Result {
        val latch = CountDownLatch(1)
        var result: Result? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_INSTALL_STATUS) return
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
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
                runCatching { context.unregisterReceiver(this) }
            }
        }
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            latch.await(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return result ?: Result.Failed(null, "install timed out")
    }
}