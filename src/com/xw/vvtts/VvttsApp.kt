package com.xw.vvtts

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.xw.vvtts.utils.CrashlyticsFx
import com.xw.vvtts.utils.Telemetry

/**
 * Application entry point.
 *
 * Android instantiates this class and calls [onCreate] in EVERY process the app
 * spawns — the default (main) process AND the isolated ":tts" engine process
 * where [com.xw.vvtts.services.VvTtsService] and its native libvvtts_core.so
 * run.
 *
 * Firebase's default auto-init path relies on the FirebaseInitProvider
 * ContentProvider, which only fires in the default process. Without this class
 * the engine process would never initialize FirebaseApp, so
 * [com.google.firebase.crashlytics.FirebaseCrashlytics] — and its native (NDK)
 * crash handler — would silently no-op for every native/JNI engine crash.
 * Bootstrap crash reporting here, in every process, so those crashes are
 * captured. See [CrashlyticsFx.initProcess].
 *
 * Direct-Boot guard: before the user first unlocks (Direct Boot mode, which the
 * engine process enters via manifest `directBootAware`), credential-encrypted
 * storage is not yet accessible and the process must be ready to speak on the
 * lock screen immediately. FirebaseApp/Crashlytics NDK init and Telemetry both
 * touch storage on the main thread, which can stall `Application.onCreate` and
 * push VvTtsService past the TTS framework's bind timeout — leaving the engine
 * unable to speak on the lock screen. So when still locked we DEFER both until
 * the ACTION_USER_UNLOCKED broadcast, after which all of it runs exactly as
 * before (analytics still tracked). While locked, the engine process stays
 * lean and starts fast.
 */
class VvttsApp : Application() {
    /** Guards against re-running the deferred init on a second broadcast. */
    private var deferScheduled = false

    override fun onCreate() {
        super.onCreate()
        if (isUserUnlocked()) {
            CrashlyticsFx.initProcess(this)
            Telemetry.apply(this)
        } else {
            scheduleDeferredInit()
        }
    }

    private fun isUserUnlocked(): Boolean =
        try {
            getSystemService(UserManager::class.java).isUserUnlocked
        } catch (t: Throwable) {
            // If we can't tell, run the init — Firebase init is itself guarded
            // and idempotent, so it's the safe default.
            true
        }

    /**
     * Defer Firebase init until the user unlocks, then do the full bootstrap.
     * The receiver is built lazily here (not as a field initializer) so this
     * class can be constructed on the host JVM regression test without an
     * Android runtime — a field `BroadcastReceiver()` would throw `Stub!` in
     * `<init>`, before [onCreate] even runs.
     */
    private fun scheduleDeferredInit() {
        if (deferScheduled) return
        deferScheduled = true
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                try { unregisterReceiver(this) } catch (_: Throwable) {}
                // BroadcastReceiver scope ends after onReceive; do the heavy init on
                // a fresh context that outlives this callback.
                CrashlyticsFx.initProcess(context.applicationContext)
                Telemetry.apply(context.applicationContext)
            }
        }
        try {
            registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            // Registration failed (e.g. DOCT from a restricted context): run now
            // rather than never. Firebase init is guarded, so this cannot crash.
            CrashlyticsFx.initProcess(this)
            Telemetry.apply(this)
        }
    }
}