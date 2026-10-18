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
    // Host-regression seam (always null in production): observe the GA4
    // collection-change every SDK call is asked to apply, so the harness can
    // assert a failed analytics opt-out still disables collection in-process
    // (review #12). Never used by shipped code.
    private var sdkProbe: ((Boolean) -> Unit)? = null

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
     *  Guarded no-op if the SDK is absent — never break the app for telemetry. */
    fun setEnabled(context: Context, on: Boolean) {
        sdkProbe?.invoke(on)
        try {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(on)
        } catch (t: Throwable) { /* no-op */ }
    }

    /** Persist and apply an analytics-consent toggle from Settings, failing
     *  closed (review #12) the same way the crash surface does. The change is
     *  applied to the live SDK immediately; an opt-out that could NOT be hardened
     *  to disk still disables GA4 for this session, so the switch never reads OFF
     *  while analytics stay live, and the caller is told the choice is not
     *  durable so the user can retry.
     *
     *  Returns true when the change was durable (nothing to surface); false when
     *  an OFF write failed to persist (GA4 is disabled for this session, but a
     *  later launch would re-read the old enabled value). */
    fun setConsent(context: Context, on: Boolean): Boolean {
        val persisted = try { TelemetryPrefs.setAnalyticsEnabled(context, on) } catch (t: Throwable) { false }
        if (persisted || on) { setEnabled(context, on); return true }
        // OFF write FAILED: the choice is not durable. Fail closed — disable GA4
        // for this session and surface the failure so the user knows they must
        // toggle again.
        setEnabled(context, false)
        return false
    }
}