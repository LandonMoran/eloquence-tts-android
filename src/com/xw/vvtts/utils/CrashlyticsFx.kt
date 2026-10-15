package com.xw.vvtts.utils

import android.content.Context
import android.os.Build
import com.google.firebase.FirebaseApp
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
 *  - [initProcess]: the default FirebaseInitProvider auto-init runs only in the
 *    app's default process. [initProcess] explicitly bootstraps FirebaseApp and
 *    Crashlytics in EVERY process — including the isolated ":tts" engine process
 *    where libvvtts_core.so crashes otherwise go unreported — and tags reports
 *    with the originating process name.
 *
 * Every method is a no-op when Crashlytics is not available (e.g. a debug build
 * shipping without google-services), matching the "add diagnostics, never break
 * the app" rule.
 */
object CrashlyticsFx {
    private val cs: FirebaseCrashlytics? by lazy {
        try { FirebaseCrashlytics.getInstance() } catch (t: Throwable) { null }
    }

    // Current process name from /proc/self/cmdline. Isolated processes are
    // named "<app>:<name>" (e.g. "com.xw.vvtts:tts"); the default process is
    // just "<app>". Report is tagged with this so engine-process crashes are
    // distinguishable in the dashboard.
    private val processName: String by lazy {
        try {
            val pid = android.os.Process.myPid()
            val s = java.io.File("/proc/$pid/cmdline").readBytes()
            val end = s.indexOf(0)
            val cmd = if (end >= 0) s.copyOf(end).toString(Charsets.UTF_8) else s.toString(Charsets.UTF_8)
            val i = cmd.lastIndexOf(':')
            if (i >= 0) cmd.substring(i + 1) else "main"
        } catch (t: Throwable) { "unknown" }
    }

    /**
     * Initialize crash reporting for THIS process, including secondary
     * processes where Firebase auto-init never runs.
     *
     * The default path (FirebaseInitProvider ContentProvider) only fires in the
     * app's default process. The :tts engine process is isolated — VvTtsService
     * runs in android:process=":tts" and no provider executes there — so without
     * explicit initialization FirebaseCrashlytics.getInstance() throws and every
     * native/JNI crash in libvvtts_core.so would be invisible. This:
     *
     *  1. Initializes the default FirebaseApp in this process (idempotent: in the
     *     main process the provider has already done it).
     *  2. Forces FirebaseCrashlytics to initialize here, which installs the NDK
     *     native crash handler for this process.
     *  3. Tags every report with the process name so engine-process crashes are
     *     filterable in the dashboard.
     *
     * Called from Application.onCreate, which Android runs in every process.
     */
    fun initProcess(ctx: Context) {
        try {
            val appCtx = ctx.applicationContext
            if (FirebaseApp.getApps(appCtx).isEmpty()) FirebaseApp.initializeApp(appCtx)
            val c = cs ?: return // now initializable in this process
            c.setCustomKey("process", processName)
            c.log("crashlytics initialized process=$processName")
        } catch (t: Throwable) { /* never crash the app for telemetry */ }
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