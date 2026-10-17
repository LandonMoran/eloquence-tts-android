package com.xw.vvtts.utils

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
 * current window into Crashlytics as sparse numeric custom keys, so a native or
 * Java crash carries engine-health numbers (rotate count, longest synth,
 * timeouts, JNI-open failures, ...). This follows the always-on crash-reporting
 * policy and contains no content, so it is governed by Crashlytics consent, not
 * the opt-in analytics/performance toggles. A deliberately no-op fallback keeps
 * every method safe even when the Crashlytics SDK is absent.
 *
 * Note: a custom Firebase-Performance trace is intentionally NOT produced here.
 * The FirePerf custom-trace API is absent from the host regression stub (only
 * setPerformanceCollectionEnabled is available), so compiling against it would
 * poison the host contract gate. Engine timing is instead carried by the
 * Crashlytics keys, which is the reliable, content-free channel.
 *
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
     *  process init and before expected teardown; keys are stable and sparse.
     *  Crashlytics has no Long-key overload, so durations/counts are stored as
     *  decimal strings (numeric, still machine-readable). */
    fun attachCrashKeys() {
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
        CrashlyticsFx.setKey("m_stall", s.stallCount.toString())
        CrashlyticsFx.setKey("m_jni_open_fail", s.jniOpenFailCount.toString())
        CrashlyticsFx.setKey("m_native_load_fail", s.nativeLoadFailCount.toString())
        CrashlyticsFx.setKey("m_queue_wait_avg_ms", if (s.queueRequestCount > 0) (s.queueWaitTotalMs / s.queueRequestCount).toString() else "0")
        CrashlyticsFx.setKey("m_ok", s.outcomeOk.toString())
        CrashlyticsFx.setKey("m_fail", s.outcomeFail.toString())
        CrashlyticsFx.setKey("m_timeout_req", s.outcomeTimeout.toString())
        CrashlyticsFx.setKey("m_interrupted", s.outcomeInterrupted.toString())
    }

    // ---------------------------------------------------------------- snapshot

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