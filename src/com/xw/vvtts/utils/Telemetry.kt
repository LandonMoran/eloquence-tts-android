package com.xw.vvtts.utils

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.perf.FirebasePerformance

/**
 * Runtime application of the user's telemetry-consent choice to every Firebase
 * data-collection surface. Called from [com.xw.vvtts.VvttsApp.onCreate], which
 * runs in BOTH the main process and the isolated ":tts" engine process, and from
 * the Settings toggle whenever the user changes it.
 *
 * Gate coverage (this consent flag gates the marketing/perf diagnostics only;
 *  Crashlytics crash reporting is intentionally NOT gated — it is the app's
 *  primary diagnostics surface and the point of this branch):
 *  - GA4 analytics (FirebaseAnalytics). The manifest already ships
 *    firebase_analytics_collection_enabled=false as the default; this call
 *    re-states it at runtime and flips it on the moment the user opts in.
 *  - Firebase Performance Monitoring, which has NO manifest suppression by
 *    default and would otherwise auto-collect independently of the GA4 flag —
 *    the one service that would have violated the "nothing uploads until opt-in"
 *    disclosure. We disable it at runtime unless the user consents.
 *
 * Every call is a guarded no-op when the SDK is absent/unsuccessfully
 * initialized, matching the "never break the app for telemetry" rule.
 */
object Telemetry {
    fun apply(context: Context) {
        val consented = try { TelemetryPrefs.isEnabled(context) } catch (t: Throwable) { false }
        try {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(consented)
        } catch (t: Throwable) { /* no-op */ }
        try {
            FirebasePerformance.getInstance().setPerformanceCollectionEnabled(consented)
        } catch (t: Throwable) { /* no-op */ }
    }
}