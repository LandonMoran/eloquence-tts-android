package com.xw.vvtts.utils

import android.content.Context
import android.os.SystemClock

/**
 * Privacy-conscious, aggregate-only diagnostics for the Eloquence speech engine.
 *
 * This deliberately records NO utterance text, synthesized output, dictionary
 * entries, or accessibility content. It keeps only bounded counters and
 * durations with a small set of controlled status codes, so the numbers are
 * useful for investigating slow, frozen, or failing synthesis without
 * exfiltrating what was being spoken.
 *
 * The aggregates are surfaced on crash reports: every process copies the
 * current aggregate window into Crashlytics as sparse numeric custom keys, so a
 * native or Java crash carries engine-health numbers (rotate count, longest
 * synth, timeouts, JNI-open failures, ...). This follows the always-on
 * crash-reporting policy and contains no content, so it is governed by
 * Crashlytics consent, not the opt-in analytics toggle. A deliberately no-op
 * fallback keeps every method safe even when the Crashlytics SDK is absent.
 *
 * Delivery: a fatal crash in the isolated ":tts" engine process can kill the
 * process mid-synthesis, before any graceful shutdown callback. So raw init-time
 * keys alone are stale. Instead:
 *  - every process calls [init] once at Application.onCreate, which restores the
 *    last persisted aggregate window and attaches it to Crashlytics keys
 *    immediately (the keys are never a cleared/zero window for the current run);
 *  - the record call-sites also re-attach the keys and periodically persist a
 *    compact snapshot (bounded disk-write throttle on the hot path; sparse,
 *    important events like rotate / JNI-open-fail / timeout / shutdown persist
 *    immediately).
 *
 * The window therefore accumulates across process deaths and across the main +
 * :tts processes, so a crash report always carries engine-health numbers rather
 * than a cleared or stale zero window.
 *
 * Thread-safe via one internal lock; the hot-path calls are a single timing
 * read plus a bounded counter increment (persistence is throttled).
 */
object EngineMetrics {
    // Sparse, controlled outcome codes (no free-form/user-derived values).
    const val RC_OK = 0
    const val RC_FAIL = 1
    const val RC_TIMEOUT = 2
    const val RC_INTERRUPTED = 3

    private const val PREFS_NAME = "vvtts_engine_metrics"
    // Coarse/rare events force an immediate persist; hot-path records are
    // chunked to bound disk writes (MirroredPreferences does an fsync + atomic
    // rename per write).
    private const val PERSIST_INTERVAL_MS = 2_000L

    private val lock = Any()
    private var appContext: Context? = null
    private var lastPersistElapsed = 0L

    private var initCount = 0L
    private var initFailCount = 0L
    private var initTotalMs = 0L
    private var shutdownCount = 0L
    private var shutdownTotalMs = 0L
    private var synthCount = 0L
    private var synthTotalMs = 0L
    private var synthMaxMs = 0L
    private var synthTimeoutCount = 0L
    private var rotateCount = 0L
    private var jniOpenFailCount = 0L
    private var nativeLoadFailCount = 0L
    private var queueRequestCount = 0L
    private var queueWaitTotalMs = 0L
    private var outcomeOk = 0L
    private var outcomeFail = 0L
    private var outcomeTimeout = 0L
    private var outcomeInterrupted = 0L

    // ----------------------------------------------------------------- lifecycle

    /** Called once per process at Application.onCreate (well before the first
     *  synthesis). Restores the last persisted aggregate window, attaches it to
     *  Crashlytics keys immediately, and re-persists so the restored state and
     *  the keys stay in agreement. Safe to call more than once. */
    fun init(context: Context) {
        appContext = context.applicationContext
        restore()
        flush(now = true)
    }

    private fun flush(now: Boolean) {
        val ctx = appContext ?: return
        val elapsed = SystemClock.elapsedRealtime()
        if (!now && elapsed - lastPersistElapsed < PERSIST_INTERVAL_MS) return
        lastPersistElapsed = elapsed
        attachCrashKeys(ctx)
        persist()
    }

    // ------------------------------------------------------------------- record

    fun recordInit(ms: Long, ok: Boolean) {
        synchronized(lock) {
            initCount++
            initTotalMs += ms
            if (!ok) initFailCount++
        }
        flush(now = true)
    }

    fun recordShutdown(ms: Long) {
        synchronized(lock) {
            shutdownCount++
            shutdownTotalMs += ms
        }
        flush(now = true)
    }

    /** Per synthesis request: wall-clock duration of the native synth body. */
    fun recordSynth(ms: Long) {
        synchronized(lock) {
            synthCount++
            synthTotalMs += ms
            if (ms > synthMaxMs) synthMaxMs = ms
        }
        flush(now = false)
    }

    fun recordSynthTimeout() {
        synchronized(lock) { synthTimeoutCount++ }
        flush(now = true)
    }

    fun recordRotate() {
        synchronized(lock) { rotateCount++ }
        flush(now = true)
    }

    fun recordJniOpenFail() {
        synchronized(lock) { jniOpenFailCount++ }
        flush(now = true)
    }

    fun recordNativeLoadFail() {
        synchronized(lock) { nativeLoadFailCount++ }
        flush(now = true)
    }

    /** Time spent waiting in the speech queue before synthesis started. */
    fun recordQueueWait(ms: Long) {
        synchronized(lock) {
            queueRequestCount++
            queueWaitTotalMs += ms
        }
        flush(now = false)
    }

    /** The final disposition of a synthesis request. */
    fun recordOutcome(code: Int) {
        synchronized(lock) {
            when (code) {
                RC_OK -> outcomeOk++
                RC_FAIL -> outcomeFail++
                RC_TIMEOUT -> outcomeTimeout++
                RC_INTERRUPTED -> outcomeInterrupted++
                else -> Unit
            }
        }
        flush(now = false)
    }

    // ------------------------------------------------------------ crash attach

    /** Copy the current aggregate window into Crashlytics custom keys and, via
     *  [flush], persist a durable snapshot. Callers are the record call-sites,
     *  so the keys reflect fresh engine-health numbers at the moment a crash or
     *  hang happens — not a stale process-init snapshot. Crashlytics has no
     *  Long-key overload, so durations/counts are stored as decimal strings
     *  (numeric, still machine-readable). */
    private fun attachCrashKeys(context: Context) {
        // When the user has turned off crash reporting, do not populate any
        // Crashlytics keys this session (nothing would transmit, and keys should
        // never be populated on a session that opted out).
        if (!TelemetryPrefs.isCrashEnabled(context)) return
        val s = snapshot()
        CrashlyticsFx.setKey("m_init_n", s.initCount.toString())
        CrashlyticsFx.setKey("m_init_fail", s.initFailCount.toString())
        CrashlyticsFx.setKey("m_init_ms", s.initTotalMs.toString())
        CrashlyticsFx.setKey("m_shutdown_ms", s.shutdownTotalMs.toString())
        CrashlyticsFx.setKey("m_synth_n", s.synthCount.toString())
        CrashlyticsFx.setKey("m_synth_max_ms", s.synthMaxMs.toString())
        CrashlyticsFx.setKey("m_synth_avg_ms", if (s.synthCount > 0) (s.synthTotalMs / s.synthCount).toString() else "0")
        CrashlyticsFx.setKey("m_timeout", s.synthTimeoutCount.toString())
        CrashlyticsFx.setKey("m_rotate", s.rotateCount.toString())
        CrashlyticsFx.setKey("m_jni_open_fail", s.jniOpenFailCount.toString())
        CrashlyticsFx.setKey("m_native_load_fail", s.nativeLoadFailCount.toString())
        CrashlyticsFx.setKey("m_queue_wait_avg_ms", if (s.queueRequestCount > 0) (s.queueWaitTotalMs / s.queueRequestCount).toString() else "0")
        CrashlyticsFx.setKey("m_ok", s.outcomeOk.toString())
        CrashlyticsFx.setKey("m_fail", s.outcomeFail.toString())
        CrashlyticsFx.setKey("m_timeout_req", s.outcomeTimeout.toString())
        CrashlyticsFx.setKey("m_interrupted", s.outcomeInterrupted.toString())
    }

    // ------------------------------------------------------- durable snapshot

    /** Merge a previously persisted window into the live counters so the
     *  aggregates survive a process death and accumulate across the main and
     *  :tts processes. Runs once at [init]; also lets a run that was crash-opted
     *  OUT recover its history if the user later re-enables crash reporting. */
    private fun restore() {
        val ctx = appContext ?: return
        try {
            val p = MirroredPreferences(ctx, PREFS_NAME)
            synchronized(lock) {
                initCount += p.getLong("init_n", 0)
                initFailCount += p.getLong("init_fail", 0)
                initTotalMs += p.getLong("init_ms", 0)
                shutdownCount += p.getLong("shutdown_n", 0)
                shutdownTotalMs += p.getLong("shutdown_ms", 0)
                synthCount += p.getLong("synth_n", 0)
                synthTotalMs += p.getLong("synth_ms", 0)
                synthMaxMs = maxOf(synthMaxMs, p.getLong("synth_max_ms", 0))
                synthTimeoutCount += p.getLong("timeout", 0)
                rotateCount += p.getLong("rotate", 0)
                jniOpenFailCount += p.getLong("jni_open_fail", 0)
                nativeLoadFailCount += p.getLong("native_load_fail", 0)
                queueRequestCount += p.getLong("queue_n", 0)
                queueWaitTotalMs += p.getLong("queue_ms", 0)
                outcomeOk += p.getLong("ok", 0)
                outcomeFail += p.getLong("fail", 0)
                outcomeTimeout += p.getLong("timeout_req", 0)
                outcomeInterrupted += p.getLong("interrupted", 0)
            }
        } catch (t: Throwable) { /* ignore unreadable/corrupt snapshot */ }
    }

    /** Persist the current aggregate window to a compact, durable snapshot so a
     *  fatal crash short of teardown still leaves real numbers to restore. */
    private fun persist() {
        val ctx = appContext ?: return
        val s = snapshot()
        try {
            MirroredPreferences(ctx, PREFS_NAME).edit { e ->
                e.putLong("init_n", s.initCount)
                e.putLong("init_fail", s.initFailCount)
                e.putLong("init_ms", s.initTotalMs)
                e.putLong("shutdown_n", s.shutdownCount)
                e.putLong("shutdown_ms", s.shutdownTotalMs)
                e.putLong("synth_n", s.synthCount)
                e.putLong("synth_ms", s.synthTotalMs)
                e.putLong("synth_max_ms", s.synthMaxMs)
                e.putLong("timeout", s.synthTimeoutCount)
                e.putLong("rotate", s.rotateCount)
                e.putLong("jni_open_fail", s.jniOpenFailCount)
                e.putLong("native_load_fail", s.nativeLoadFailCount)
                e.putLong("queue_n", s.queueRequestCount)
                e.putLong("queue_ms", s.queueWaitTotalMs)
                e.putLong("ok", s.outcomeOk)
                e.putLong("fail", s.outcomeFail)
                e.putLong("timeout_req", s.outcomeTimeout)
                e.putLong("interrupted", s.outcomeInterrupted)
            }
        } catch (t: Throwable) { /* telemetry must never break the app */ }
    }

    // ---------------------------------------------------------------- snapshot

    private fun snapshot() = synchronized(lock) {
        Snapshot(
            initCount, initFailCount, initTotalMs, shutdownCount, shutdownTotalMs,
            synthCount, synthTotalMs, synthMaxMs, synthTimeoutCount, rotateCount,
            jniOpenFailCount, nativeLoadFailCount, queueRequestCount,
            queueWaitTotalMs, outcomeOk, outcomeFail, outcomeTimeout, outcomeInterrupted,
        )
    }

    private class Snapshot(
        val initCount: Long, val initFailCount: Long, val initTotalMs: Long,
        val shutdownCount: Long, val shutdownTotalMs: Long,
        val synthCount: Long, val synthTotalMs: Long, val synthMaxMs: Long,
        val synthTimeoutCount: Long, val rotateCount: Long,
        val jniOpenFailCount: Long, val nativeLoadFailCount: Long,
        val queueRequestCount: Long, val queueWaitTotalMs: Long,
        val outcomeOk: Long, val outcomeFail: Long, val outcomeTimeout: Long,
        val outcomeInterrupted: Long,
    )
}