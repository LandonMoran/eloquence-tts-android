package com.xw.vvtts.utils

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.perf.FirebasePerformance

/**
 * Runtime application of the user's telemetry-consent choices to every Firefox
 * data-collection surface. Called from [com.xw.vvtts.VvttsApp.onCreate], which
 * runs in BOTH the main process and the isolated ":tts" engine process, and
 * from the Settings toggles whenever the user changes them.
 *
 * The two opt-in categories are applied independently:
 *  - GA4 analytics: the manifest ships firebase_analytics_collection_enabled=false
 *    as the default; this re-states it at runtime and flips it on only when the
 *    user enables the "Share analytics" toggle.
 *  - Firebase Performance Monitoring: has no reliable manifest-wide suppression,
 *    so it is disabled at runtime unless the user enables the "Share
 *    performance" toggle.
 *
 * Crashlytics crash reporting is intentionally NOT touched here — it is always
 * on and governed separately (see [CrashlyticsFx]); this gate covers only the
 * marketing/performance diagnostics.
 *
 * Every call is a guarded no-op when the SDK is absent/unsuccessfully
 * initialized, matching the "never break the app for telemetry" rule.
 */
object Telemetry {
    fun apply(context: Context) {
        val analyticsOn = try { TelemetryPrefs.isAnalyticsEnabled(context) } catch (t: Throwable) { false }
        val perfOn = try { TelemetryPrefs.isPerfEnabled(context) } catch (t: Throwable) { false }
        apply(context, analyticsOn, perfOn)
    }

    fun apply(context: Context, analyticsOn: Boolean, perfOn: Boolean) {
        try {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(analyticsOn)
        } catch (t: Throwable) { /* no-op */ }
        try {
            FirebasePerformance.getInstance().setPerformanceCollectionEnabled(perfOn)
        } catch (t: Throwable) { /* no-op */ }
    }
}