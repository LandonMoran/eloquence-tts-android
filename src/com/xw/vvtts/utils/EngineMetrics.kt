package com.xw.vvtts.utils

import android.content.Context
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Privacy-conscious, aggregate-only diagnostics for the Eloquence speech engine.
 *
 * This deliberately records NO utterance text, synthesized output, dictionary
 * entries, or accessibility content. It keeps only bounded counters and
 * durations with a small set of controlled status codes, so the numbers are
 * useful for investigating slow, frozen, or failing synthesis without
 * exfiltrating what was being spoken.
 *
 * Two independent consumers, governed by their own consent flags:
 *  - Crash evidence (ALWAYS on): the aggregates are attached to Crashlytics as
 *    numeric custom keys so a native/Java crash report carries engine-health
 *    numbers (rotate count, longest synth, timeouts, JNI-open failures, ...).
 *    This is part of crash diagnostics and contains no content, so it follows
 *    the always-on crash-reporting policy, not the opt-in analytics/perf gate.
 *  - Performance Monitoring (opt-in only): when the user has enabled the
 *    "performance" toggle, a single custom FirePerf trace is produced each
 *    flush summarizing the same aggregate window. It measures the engine
 *    directly from whichever process runs synthesis (including the isolated
 *    :tts process), which the bytecode auto-instrumenter cannot reach.
 *
 * Every path degrades to a cheap no-op when the SDK is absent or disabled.
 * Thread-safe via one internal lock; the hot-path calls are a single timing
 * read plus a bounded counter increment.
 */
object EngineMetrics {
    // Sparse, controlled outcome codes (no free-form/user-derived values).
    const val RC_OK = 0
    const val RC_FAIL = 1
    const val RC_TIMEOUT = 2
    const val RC_INTERRUPTED = 3

    private val lock = Any()
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
    private var stallCount = 0L
    private var jniOpenFailCount = 0L
    private var nativeLoadFailCount = 0L
    private var queueRequestCount = 0L
    private var queueWaitTotalMs = 0L
    private var outcomeOk = 0L
    private var outcomeFail = 0L
    private var outcomeTimeout = 0L
    private var outcomeInterrupted = 0L

    // ------------------------------------------------------------------ record

    fun recordInit(ms: Long, ok: Boolean) = synchronized(lock) {
        initCount++
        initTotalMs += ms
        if (!ok) initFailCount++
    }

    fun recordShutdown(ms: Long) = synchronized(lock) {
        shutdownCount++
        shutdownTotalMs += ms
    }

    /** Per synthesis request: wall-clock duration of the native synth body. */
    fun recordSynth(ms: Long) = synchronized(lock) {
        synthCount++
        synthTotalMs += ms
        if (ms > synthMaxMs) synthMaxMs = ms
    }

    fun recordSynthTimeout() = synchronized(lock) { synthTimeoutCount++ }

    fun recordRotate() = synchronized(lock) { rotateCount++ }

    fun recordStall() = synchronized(lock) { stallCount++ }

    fun recordJniOpenFail() = synchronized(lock) { jniOpenFailCount++ }

    fun recordNativeLoadFail() = synchronized(lock) { nativeLoadFailCount++ }

    /** Time spent waiting in the speech queue before synthesis started. */
    fun recordQueueWait(ms: Long) = synchronized(lock) {
        queueRequestCount++
        queueWaitTotalMs += ms
    }

    /** The final disposition of a synthesis request. */
    fun recordOutcome(code: Int) = synchronized(lock) {
        when (code) {
            RC_OK -> outcomeOk++
            RC_FAIL -> outcomeFail++
            RC_TIMEOUT -> outcomeTimeout++
            RC_INTERRUPTED -> outcomeInterrupted++
            else -> Unit
        }
    }

    // ------------------------------------------------------------- crash attach

    /** Copy the current aggregate window into Crashlytics custom keys. Call at
     *  process init and before expected teardown; keys are stable and sparse. */
    fun attachCrashKeys() {
        val s = snapshot()
        CrashlyticsFx.setKey("m_init_n", s.initCount)
        CrashlyticsFx.setKey("m_init_fail", s.initFailCount)
        CrashlyticsFx.setKey("m_init_ms", s.initTotalMs)
        CrashlyticsFx.setKey("m_synth_n", s.synthCount)
        CrashlyticsFx.setKey("m_synth_max_ms", s.synthMaxMs)
        CrashlyticsFx.setKey("m_synth_avg_ms", if (s.synthCount > 0) s.synthTotalMs / s.synthCount else 0L)
        CrashlyticsFx.setKey("m_timeout", s.synthTimeoutCount)
        CrashlyticsFx.setKey("m_rotate", s.rotateCount)
        CrashlyticsFx.setKey("m_stall", s.stallCount)
        CrashlyticsFx.setKey("m_jni_open_fail", s.jniOpenFailCount)
        CrashlyticsFx.setKey("m_native_load_fail", s.nativeLoadFailCount)
        CrashlyticsFx.setKey("m_queue_wait_avg_ms", if (s.queueRequestCount > 0) s.queueWaitTotalMs / s.queueRequestCount else 0L)
        CrashlyticsFx.setKey("m_ok", s.outcomeOk)
        CrashlyticsFx.setKey("m_fail", s.outcomeFail)
        CrashlyticsFx.setKey("m_timeout_req", s.outcomeTimeout)
        CrashlyticsFx.setKey("m_interrupted", s.outcomeInterrupted)
    }

    // -------------------------------------------------------------------- perf

    /** Emit one aggregate FirePerf custom trace for the current window. Only
     *  runs when the user has opted in to performance data AND the perf SDK is
     *  initialized in this process; otherwise a guarded no-op. Call from the
     *  process that owns the work (e.g. after synthesis stops), not per request. */
    fun perfTrace(context: Context, traceName: String) {
        if (!TelemetryPrefs.isPerfEnabled(context)) return
        try {
            val t: Trace = FirebasePerformance.getInstance().newTrace(traceName)
            val s = snapshot()
            t.putMetric("init_n", s.initCount)
            t.putMetric("init_fail", s.initFailCount)
            t.putMetric("init_ms", s.initTotalMs)
            t.putMetric("synth_n", s.synthCount)
            t.putMetric("synth_max_ms", s.synthMaxMs)
            t.putMetric("synth_avg_ms", if (s.synthCount > 0) s.synthTotalMs / s.synthCount else 0L)
            t.putMetric("timeout", s.synthTimeoutCount)
            t.putMetric("rotate", s.rotateCount)
            t.putMetric("stall", s.stallCount)
            t.putMetric("jni_open_fail", s.jniOpenFailCount)
            t.putMetric("native_load_fail", s.nativeLoadFailCount)
            t.putMetric("queue_wait_avg_ms", if (s.queueRequestCount > 0) s.queueWaitTotalMs / s.queueRequestCount else 0L)
            t.putMetric("ok", s.outcomeOk)
            t.putMetric("fail", s.outcomeFail)
            t.putMetric("timeout_req", s.outcomeTimeout)
            t.putMetric("interrupted", s.outcomeInterrupted)
            t.start()
            t.stop()
        } catch (th: Throwable) { /* never break the engine for telemetry */ }
    }

    /** Resets counters for a fresh collection window (e.g. after each flush). */
    fun reset() = synchronized(lock) {
        initCount = 0; initFailCount = 0; initTotalMs = 0
        shutdownCount = 0; shutdownTotalMs = 0
        synthCount = 0; synthTotalMs = 0; synthMaxMs = 0
        synthTimeoutCount = 0; rotateCount = 0; stallCount = 0
        jniOpenFailCount = 0; nativeLoadFailCount = 0
        queueRequestCount = 0; queueWaitTotalMs = 0
        outcomeOk = 0; outcomeFail = 0; outcomeTimeout = 0; outcomeInterrupted = 0
    }

    private fun snapshot() = synchronized(lock) {
        Snapshot(
            initCount, initFailCount, initTotalMs, shutdownCount, shutdownTotalMs,
            synthCount, synthTotalMs, synthMaxMs, synthTimeoutCount, rotateCount,
            stallCount, jniOpenFailCount, nativeLoadFailCount, queueRequestCount,
            queueWaitTotalMs, outcomeOk, outcomeFail, outcomeTimeout, outcomeInterrupted,
        )
    }

    private class Snapshot(
        val initCount: Long, val initFailCount: Long, val initTotalMs: Long,
        val shutdownCount: Long, val shutdownTotalMs: Long,
        val synthCount: Long, val synthTotalMs: Long, val synthMaxMs: Long,
        val synthTimeoutCount: Long, val rotateCount: Long, val stallCount: Long,
        val jniOpenFailCount: Long, val nativeLoadFailCount: Long,
        val queueRequestCount: Long, val queueWaitTotalMs: Long,
        val outcomeOk: Long, val outcomeFail: Long, val outcomeTimeout: Long,
        val outcomeInterrupted: Long,
    )
}