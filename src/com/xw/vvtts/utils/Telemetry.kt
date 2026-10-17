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
    fun apply(context: Context) {
        val analyticsOn = try { TelemetryPrefs.isAnalyticsEnabled(context) } catch (t: Throwable) { false }
        try {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(analyticsOn)
        } catch (t: Throwable) { /* no-op */ }
    }
}