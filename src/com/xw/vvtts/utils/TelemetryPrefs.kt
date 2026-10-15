package com.xw.vvtts.utils

import android.content.Context

/**
 * Consent state for all Firebase telemetry (Crashlytics crash reports, GA4
 * analytics, Firebase Performance Monitoring).
 *
 * Defaults to OFF so nothing is uploaded until the user opts in via the
 * Settings toggle — matching the disclosure in AndroidManifest.xml
 * ("no telemetry uploads until a user-facing opt-in enables it") and the
 * product posture of collecting only minimal, actionable diagnostics.
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