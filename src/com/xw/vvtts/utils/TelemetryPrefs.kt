package com.xw.vvtts.utils

import android.content.Context

/**
 * Consent state for the two opt-in Firebase telemetry categories, kept
 * INDEPENDENT so usage analytics and performance monitoring can be enabled or
 * disabled separately (see Settings > Share analytics / Share performance).
 *
 *  - Analytics  (GA4): opt-in, defaults OFF.
 *  - Performance (Firebase Performance Monitoring): opt-in, defaults OFF.
 *
 * Crashlytics crash reporting is intentionally NOT governed by either flag —
 * it is always on (the app's primary diagnostics surface, incl. native engine
 * crashes from the :tts process) and never collects speech text. See
 * [CrashlyticsFx] and the Settings note.
 *
 * Stored in ordinary SharedPreferences (via [MirroredPreferences]) so the value
 * is readable both in the main process and the isolated ":tts" engine process.
 */
object TelemetryPrefs {
    const val PREFS_NAME = "vvtts_telemetry"
    const val ANALYTICS = "analytics_enabled"
    const val PERFORMANCE = "performance_enabled"
    private const val LEGACY_COMBINED = "telemetry_enabled"

    fun isAnalyticsEnabled(context: Context): Boolean {
        migrateLegacy(context)
        return MirroredPreferences(context, PREFS_NAME).getBoolean(ANALYTICS, false)
    }

    fun isPerfEnabled(context: Context): Boolean {
        migrateLegacy(context)
        return MirroredPreferences(context, PREFS_NAME).getBoolean(PERFORMANCE, false)
    }

    fun setAnalyticsEnabled(context: Context, on: Boolean) {
        val p = MirroredPreferences(context, PREFS_NAME)
        p.edit { e -> e.putBoolean(ANALYTICS, on).putBoolean(LEGACY_COMBINED, false) }
    }

    fun setPerfEnabled(context: Context, on: Boolean) {
        val p = MirroredPreferences(context, PREFS_NAME)
        p.edit { e -> e.putBoolean(PERFORMANCE, on).putBoolean(LEGACY_COMBINED, false) }
    }

    /** One-time migration: a user who opted into the pre-split single
     *  "Share diagnostics" toggle keeps that choice across both new categories
     *  instead of silently losing it. No-op once the legacy flag is cleared. */
    private fun migrateLegacy(context: Context) {
        val p = MirroredPreferences(context, PREFS_NAME)
        if (!p.contains(LEGACY_COMBINED)) return
        val legacy = try { p.getBoolean(LEGACY_COMBINED, false) } catch (t: Throwable) { false }
        // Only apply once AND only when the split flags were never touched.
        if (legacy && !p.contains(ANALYTICS) && !p.contains(PERFORMANCE)) {
            p.edit { e ->
                e.putBoolean(ANALYTICS, true)
                e.putBoolean(PERFORMANCE, true)
                e.putBoolean(LEGACY_COMBINED, false)
            }
        } else if (!legacy) {
            p.edit { e -> e.putBoolean(LEGACY_COMBINED, false) }
        }
    }
}