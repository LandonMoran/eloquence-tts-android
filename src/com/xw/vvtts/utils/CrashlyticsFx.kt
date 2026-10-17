package com.xw.vvtts.utils

import android.content.Context
import android.os.Build
import android.util.Log
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

    // Write gate for the opt-out (review #5). Crash reporting is an OPT-OUT that
    // defaults ON, so this starts true (writes allowed) and is narrowed to the
    // consent value by [initProcess] in Application.onCreate. When a process is
    // opted out it must emit NO keys, breadcrumbs, or exceptions — even if the
    // SDK's disable toggle could not be applied (fail closed: do not populate
    // Crashlytics for a user who asked to opt out).
    private var writesAllowed = true

    // Host-regression seams (always null in production): force the toggle path's
    // missing-method / invocation-failure outcome and observe every attempted
    // write, so the harness can assert an opted-out process emits nothing.
    private var toggleSeam: (() -> Boolean)? = null
    private var writeProbe: ((String) -> Unit)? = null

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
            // Apply the user's crash-reporting opt-out (default ON). The toggle
            // may be a no-op if the SDK lacks the method or the reflection call
            // fails — that is detected and warned about by setCollectionEnabled.
            val enabled = TelemetryPrefs.isCrashEnabled(appCtx)
            setCollectionEnabled(c, enabled)
            // Write gate (review #5): an opted-out process emits NOTHING — no
            // process key, no breadcrumb, no exception. Fail closed: even if the
            // dialog toggle could not be applied (SDK may still be live), do not
            // populate Crashlytics for a user who asked to opt out.
            writesAllowed = enabled
            if (!enabled) return
            c.setCustomKey("process", processName)
            c.log("crashlytics initialized process=$processName")
        } catch (t: Throwable) { /* never crash the app for telemetry */ }
    }

    /**
     * Turn crash-report collection/transmission on or off for this process (the
     * "Crash reporting" setting). Called at init in BOTH processes (see
     * [initProcess]) so the choice is consistent, and immediately whenever the
     * user toggles it from Settings. The SDK persists the value across restarts.
     *
     * LIMITATION (disclosed to the user): disabling stops NEW collection and the
     * transmission of crashes that occur after the change, as far as the SDK
     * permits, but reports already collected/queued on-device may still be
     * uploaded, and data already uploaded to Firebase cannot be deleted.
     */
    fun setCrashCollectionEnabled(enabled: Boolean) {
        val c = cs ?: return
        setCollectionEnabled(c, enabled)
    }

    /**
     * Whether the SDK actually exposes the opt-out method on this runtime. The
     * host-contract stub omits it (so this returns false under the host gate),
     * while the shipped Firebase SDK on device exposes it (true). Used to
     * surface a genuinely missing toggle method instead of letting an opt-out
     * silently fail to take effect.
     */
    fun collectionToggleAvailable(): Boolean {
        val c = cs ?: return false
        return try {
            c.javaClass.getMethod("setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType) != null
        } catch (t: Throwable) { false }
    }

    private var toggleMissingWarned = false

    /**
     * Call FirebaseCrashlytics.setCrashlyticsCollectionEnabled(boolean) via
     * reflection and report whether it took effect. The host-contract stub
     * exposes a REDUCED FirebaseCrashlytics API (no such method — it only
     * mirrors the small surface the app touches), so a direct call would fail
     * the host regression gate, while the real SDK shipped in the app DOES have
     * it. Reflecting preserves a genuine runtime toggle on device and degrades
     * to a safe no-op where the method is absent (host harness / tests). The
     * official API is what actually toggles collection, so the opt-out is real,
     * not just a documented preference.
     *
     * Returns true only when the toggle was actually applied. On any failure —
     * missing method OR reflection invocation error (review #5) — it warns once
     * and returns false so callers know the SDK's disable did NOT take effect
     * than they can fail closed.
     */
    private fun setCollectionEnabled(c: FirebaseCrashlytics, enabled: Boolean): Boolean {
        return try {
            // Host-regression seam: force the missing-method / invocation-failure
            // outcome without depending on the reduced stub's reflection surface.
            // Fired inside the try so a throwing seam is treated exactly like a
            // real reflection invocation failure (caught -> toggle not applied).
            toggleSeam?.invoke()
            c.javaClass.getMethod("setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
                .invoke(c, enabled)
            true
        } catch (e: Throwable) {
            // A real device running a newer Firebase SDK should never hit this.
            // Warn visibly (once) rather than fail silently, so a drift that makes
            // the opt-out a no-op cannot pass unnoticed.
            if (!toggleMissingWarned) {
                toggleMissingWarned = true
                Log.w("CrashlyticsFx", "setCrashlyticsCollectionEnabled unavailable/failed — crash reporting opt-out is a NO-OP; writes suppressed for opted-out processes")
            }
            false
        }
    }

    /** Attach stable per-session tags so dashboard reports are filterable. */
    fun setContext(abi: String) {
        val c = cs ?: return
        if (!writesAllowed) return  // opted-out process emits no keys (review #5)
        writeProbe?.invoke("context")
        try {
            c.setCustomKey("abi", abi)
            c.setCustomKey("android_sdk", Build.VERSION.SDK_INT)
            c.setCustomKey("android_release", Build.VERSION.RELEASE)
            c.setCustomKey("device_model", Build.MODEL)
        } catch (t: Throwable) { /* never crash the app for telemetry */ }
    }

    /** Attach a single numeric custom key (sparse, stable name). Used for the
     *  aggregate engine-health metrics in [EngineMetrics]; never carries text. */
    fun setKey(name: String, value: String) {
        val c = cs ?: return
        if (!writesAllowed) return
        writeProbe?.invoke("key:$name")
        try { c.setCustomKey(name, value) } catch (t: Throwable) { /* no-op */ }
    }

    /** Report a caught-but-not-fatal exception with a short location tag. */
    fun record(tag: String, e: Throwable) {
        val c = cs ?: return
        if (!writesAllowed) return
        writeProbe?.invoke("record:$tag")
        try {
            c.log(tag)
            c.recordException(e)
        } catch (t: Throwable) { /* no-op */ }
    }

    /**
     * Force an intentional fatal crash so a real report reaches the dashboard.
     * equivalent is throwing from the calling (main) thread — the
     * SDK's uncaught-exception handler turns the resulting process death into
     * a Crashlytics fatal report. Only reachable through the debug-only autotest
     * hook (am start --es autotest crash); release builds never honor external
     * autotest intents and never call this.
     */
    fun crash() {
        val c = cs ?: return
        // Sentinel value so the dashboard message is identifiable in grep.
        val marker = "ELQ_VERDICT autotest-crash " + (Thread.currentThread().name ?: "main")
        c.log(marker)
        throw RuntimeException("Intentional autotest crash (debug hook) [$marker]")
    }

    /** Add a breadcrumb to the current session's crash timeline. */
    fun br(label: String) {
            val c = cs ?: return
            if (!writesAllowed) return
            writeProbe?.invoke("br:$label")
            try { c.log(label) } catch (t: Throwable) { /* no-op */ }
        }
}