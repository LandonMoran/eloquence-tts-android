package com.xw.vvtts.utils

import android.content.Context

/**
 * Consent state for the marker-opt-in Firebase telemetry: GA4 analytics and
 * Firebase Performance Monitoring.
 *
 * Defaults to OFF so neither analytics nor performance data is uploaded until
 * the user opts in via the Settings > Share diagnostics toggle, matching the
 * AndroidManifest.xml disclosure.
 *
 * Crashlytics crash reporting is intentionally NOT governed by this flag — it
 * is always on (the app's primary diagnostics surface, incl. native engine
 * crashes from the :tts process) and never collects speech text. See
 * [CrashlyticsFx] and the Settings note.
 *
 * Stored in ordinary SharedPreferences (via [MirroredPreferences]) so the value
 * is readable both in the main process and the isolated ":tts" engine process.
 */
object TelemetryPrefs {
    const val PREFS_NAME = "vvtts_telemetry"
    const val ENABLED = "telemetry_enabled"

    fun isEnabled(context: Context): Boolean =
        MirroredPreferences(context, PREFS_NAME).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        MirroredPreferences(context, PREFS_NAME).edit { e -> e.putBoolean(ENABLED, on) }
    }
}