package com.xw.vvtts.utils

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Runtime application of the user's telemetry-consent choices to the Firebase
 * analytics surface. Called from [com.xw.vvtts.VvttsApp.onCreate], which runs
 * in BOTH the main process and the isolated ":tts" engine process, and from
 * the Settings toggle whenever the user changes it.
 *
 * The GA4 analytics gate is the only opt-in category applied here: the
 * manifest ships firebase_analytics_collection_enabled=false as the default;
 * this re-states it at runtime and flips it on only when the user enables the
 * "Share analytics" toggle.
 *
 * Firebase Performance Monitoring is deliberately NOT gated here because it was
 * removed entirely (see build.gradle): its auto-instrumentation only reaches
 * the main process, not the isolated :tts engine process, and the host contract
 * has no custom-trace API, so engine timing is carried by Crashlytics keys.
 *
 * Crashlytics crash reporting is intentionally NOT touched here — it is always
 * on and governed separately (see [CrashlyticsFx]); this gate covers only the
 * marketing analytics.
 *
 * Every call is a guarded no-op when the SDK is absent/unsuccessfully
 * initialized, matching the "never break the app for telemetry" rule.
 */
object Telemetry {
    // Host-regression seams (always null in production; never used by shipped
    // code). sdkProbe observes the GA4 collection-change every SDK call is asked
    // to apply (review #12). sdkApplyProbe additionally lets the harness replace
    // the real SDK outcome when set: it returns whether the SDK "accepted" the
    // toggle, so a test can force an SDK/init failure while opting out — the
    // probe alone proves only the attempt, not acceptance (review round 6). Real
    // code runs the Firebase SDK path when sdkApplyProbe is null.
    private var sdkProbe: ((Boolean) -> Unit)? = null
    private var sdkApplyProbe: ((Boolean) -> Boolean)? = null

    /** Start-up application of the user's stored analytics consent to the GA4
     *  surface (reads the durable preference). Called from
     *  [com.xw.vvtts.VvttsApp.onCreate] in both the main and :tts processes.
     *  Safe no-op if the SDK is absent or the read fails. */
    fun apply(context: Context) {
        val analyticsOn = try { TelemetryPrefs.isAnalyticsEnabled(context) } catch (t: Throwable) { false }
        setEnabled(context, analyticsOn)
    }

    /** Apply analytics consent to the live GA4 SDK NOW, for this process, without
     *  re-reading the durable preference. This is the atomic fail-closed
     *  primitive for a consent toggle (review #12): the SDK state must follow the
     *  user's just-made choice immediately, even when persistence is unavailable.
     *
     *  Returns whether the SDK state was actually applied (review round 6): true
     *  only if the real SDK accepted the toggle; false if the SDK absent,
     *  uninitialized, or threw. Callers that must not over-claim (Settings) check
     *  the return instead of assuming success. Never breaks the app for
     *  telemetry — a throw is converted to false. */
    fun setEnabled(context: Context, on: Boolean): Boolean {
        return try {
            sdkProbe?.invoke(on)
            sdkApplyProbe?.invoke(on) ?: run {
                FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(on)
                true
            }
        } catch (t: Throwable) { false }
    }

    /** Persist and apply an analytics-consent toggle from Settings, succeeding
     *  only when BOTH sides actually landed (review round 6) — otherwise a saved
     *  OFF preference alone is no proof that live collection is OFF, so a success
     *  that still leaves GA4 live would be a silent privacy over-claim.
     *
     *  Returns true ONLY when the user's choice is fully effective:
     *    - OFF: the durable preference shows OFF AND the live SDK confirmed OFF.
     *    - ON:  the durable preference saved AND the live SDK accepted it.
     *  Any gap returns false and the caller surfaces WHICH gap (durable vs
     *  runtime) so the user is told the truth: a not-saved choice, or a saved
     *  choice the SDK could not apply right now (retry-able). */
    fun setConsent(context: Context, on: Boolean): Boolean {
        val durable = try { TelemetryPrefs.setAnalyticsEnabled(context, on) } catch (t: Throwable) { false }
        val live = setEnabled(context, on)
        // Effective only when BOTH the durable preference and the live SDK landed.
        return durable && live
    }
}