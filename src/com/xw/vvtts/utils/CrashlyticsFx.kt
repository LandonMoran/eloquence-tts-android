package com.xw.vvtts.utils

import android.content.Context
import android.os.Build
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Central Crashlytics diagnostics surface for the app.
 *
 * Firebase Crashlytics auto-captures Java uncaught crashes and (via the
 * firebase-crashlytics-ndk dependency) native SIGSEGV/SIGABRT inside the
 * libvvtts_core.so TTS engine. This wrapper adds the diagnostics the automatic
 * path cannot infer:
 *
 *  - [record]: report non-fatal exceptions (Caught-But-Not-Fatal) so silent
 *    fallbacks — engine open failures, synth errors, update pipeline hiccups —
 *    surface in the dashboard instead of being swallowed by try/catch.
 *  - [setContext]: stable per-session custom keys (ABI, Android version, device)
 *    attached to every crash report from this process.
 *  - [br]: breadcrumb log() so the last high-level steps before a crash are
 *    readable on the timeline.
 *
 * Every method is a no-op when Crashlytics is not available (e.g. a debug build
 * shipping without google-services), matching the "add diagnostics, never break
 * the app" rule.
 */
object CrashlyticsFx {
    private val cs: FirebaseCrashlytics? by lazy {
        try { FirebaseCrashlytics.getInstance() } catch (t: Throwable) { null }
    }

    /** Attach stable per-session tags so dashboard reports are filterable. */
    fun setContext(abi: String) {
        val c = cs ?: return
        try {
            c.setCustomKey("abi", abi)
            c.setCustomKey("android_sdk", Build.VERSION.SDK_INT)
            c.setCustomKey("android_release", Build.VERSION.RELEASE)
            c.setCustomKey("device_model", Build.MODEL)
        } catch (t: Throwable) { /* never crash the app for telemetry */ }
    }

    /** Report a caught-but-not-fatal exception with a short location tag. */
    fun record(tag: String, e: Throwable) {
        val c = cs ?: return
        try {
            c.log(tag)
            c.recordException(e)
        } catch (t: Throwable) { /* no-op */ }
    }

    /** Add a breadcrumb to the current session's crash timeline. */
    fun br(msg: String) {
        val c = cs ?: return
        try { c.log(msg) } catch (t: Throwable) { /* no-op */ }
    }
}