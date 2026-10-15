package com.xw.vvtts

import android.app.Application
import com.xw.vvtts.utils.CrashlyticsFx

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
 */
class VvttsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashlyticsFx.initProcess(this)
    }
}