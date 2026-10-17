package com.xw.vvtts.utils

import android.content.Context

/**
 * Consent state for the opt-in analytics telemetry category and the opt-out
 * crash channel:
 *
 *  - Analytics (GA4): opt-in, defaults OFF.
 *  - Crash     (Crashlytics): opt-out, defaults ON (the app's primary
 *    diagnostics surface, incl. native engine crashes from the :tts process,
 *    and EngineMetrics keys). Independent of the analytics opt-in.
 *
 * (Firebase Performance Monitoring had its own opt-in flag here; it was
 * removed along with the FirePerf SDK because it cannot measure the isolated
 * :tts engine process — see build.gradle and Telemetry.kt.)
 *
 * Stored in ordinary SharedPreferences (via [MirroredPreferences]) so the value
 * is readable both in the main process and the isolated ":tts" engine process.
 */
object TelemetryPrefs {
    const val PREFS_NAME = "vvtts_telemetry"
    const val ANALYTICS = "analytics_enabled"
    const val CRASH = "crash_enabled"
    private const val LEGACY_COMBINED = "telemetry_enabled"

    fun isAnalyticsEnabled(context: Context): Boolean {
        migrateLegacy(context)
        return MirroredPreferences(context, PREFS_NAME).getBoolean(ANALYTICS, false)
    }

    fun setAnalyticsEnabled(context: Context, on: Boolean): Boolean {
        val p = MirroredPreferences(context, PREFS_NAME)
        return p.edit { e -> e.putBoolean(ANALYTICS, on).putBoolean(LEGACY_COMBINED, false) }
    }

    /** Crash reporting is an OPT-OUT: default ON. Independent of analytics and
     *  deliberately excluded from [migrateLegacy] (the old combined flag never
     *  governed crash reporting, which has always been its own channel). */
    fun isCrashEnabled(context: Context): Boolean =
        MirroredPreferences(context, PREFS_NAME).getBoolean(CRASH, true)

    /** Persist the crash (opt-out) consent. Returns the durable write result so
     *  callers can fail closed (review #7): a false return means the choice did
     *  NOT reach storage and a later process/launch would read the old value —
     *  an opted-out user must never be told their opt-out was saved when it was
     *  not, and the SDK must stay disabled for this session regardless. */
    fun setCrashEnabled(context: Context, on: Boolean): Boolean =
        MirroredPreferences(context, PREFS_NAME).edit { e -> e.putBoolean(CRASH, on) }

    /** One-time migration: a user who opted into the pre-split single
     *  "Share diagnostics" toggle keeps that choice for analytics instead of
     *  silently losing it. (Performance Monitoring, which the old combined flag
     *  also implied, no longer exists.) No-op once the legacy flag is cleared. */
    private fun migrateLegacy(context: Context) {
        val p = MirroredPreferences(context, PREFS_NAME)
        if (!p.contains(LEGACY_COMBINED)) return
        val legacy = try { p.getBoolean(LEGACY_COMBINED, false) } catch (t: Throwable) { false }
        // Only apply once AND only when the split flag was never touched.
        if (legacy && !p.contains(ANALYTICS)) {
            p.edit { e ->
                e.putBoolean(ANALYTICS, true)
                e.putBoolean(LEGACY_COMBINED, false)
            }
        } else if (!legacy) {
            p.edit { e -> e.putBoolean(LEGACY_COMBINED, false) }
        }
    }
}