package com.xw.vvtts.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Arrays

/**
 * Privacy-conscious, aggregate-only diagnostics for the Eloquence speech engine.
 *
 * This deliberately records NO utterance text, synthesized output, dictionary
 * entries, or accessibility content. It keeps only bounded counters and
 * durations with a small set of controlled status codes, so the numbers are
 * useful for investigating slow, frozen, or failing synthesis without
 * exfiltrating what was being spoken.
 *
 * Cross-process safe aggregation
 * ------------------------------
 * The main process and the isolated ":tts" engine process each keep their own
 * [local] counters in memory (reset to zero at process start — nothing is
 * folded *into* them from the shared aggregate). On each throttled checkpoint
 * the process merges ONLY its own outstanding increments [local]-[baseline]
 * into a shared on-disk aggregate via a single atomic read-modify-write
 * ([MirroredPreferences.update], which carries the cross-process file lock).
 * Two processes serializing their independent deltas never overwrite each
 * other's numbers, so updates accumulate reliably rather than clobbering.
 * [combined] = shared on-disk aggregate + this process's not-yet-merged deltas
 * is what gets attached to Crashlytics keys, so a crash report carries the
 * engine-health window across processes and process deaths.
 *
 * Consent / retention
 * -------------------
 * Crash reporting is the primary diagnostics surface (default-on, opt-out).
 * When it is opted out, nothing is merged or retained and this process's
 * deltas are dropped; opting back in starts from a cleared window. Opting out
 * (in Settings) also clears the already-retained shared snapshot, so older
 * diagnostics are NOT silently uploaded if the user later re-enables the
 * toggle. Timed/rotate/JNI-fail/shutdown checkpoints persist immediately; the
 * hot-path synthesis/queue records are throttled to bound disk writes.
 *
 * Cross-process clear
 * -------------------
 * Opt-out calls [clear], which zeroes the shared snapshot AND bumps a persisted
 * generation token. Every process caches that generation and, at each merge,
 * discards any pre-opt-out deltas it still holds if the token advanced (i.e. a
 * clear happened while it was idle). This closes the gap where an opted-out
 * window could otherwise be uploaded by the sibling process after re-enable.
 * The generation check is re-validated atomically INSIDE the same
 * [MirroredPreferences.update] transaction that applies a merge, so a clear
 * landing between the pre-check and the write is still caught and the entire
 * pre-opt-out batch is discarded rather than smuggled into the fresh store.
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
    // Cross-process generation key (deliberately not one of the bounded counter
    // KEYS): bumped whenever the retained snapshot is cleared on opt-out, so an
    // idle process still holding pre-opt-out deltas can detect and discard them
    // before they could merge after a later re-enable.
    private const val GEN_KEY = "metrics_generation"

    // Indexes into the counter arrays; must stay aligned with [KEYS].
    private const val I_INIT_N = 0
    private const val I_INIT_FAIL = 1
    private const val I_INIT_MS = 2
    private const val I_SHUT_N = 3
    private const val I_SHUT_MS = 4
    private const val I_SYNTH_N = 5
    private const val I_SYNTH_MS = 6
    private const val I_SYNTH_MAX = 7
    private const val I_TIMEOUT = 8
    private const val I_ROTATE = 9
    private const val I_JNI = 10
    private const val I_NATIVE = 11
    private const val I_QUEUE_N = 12
    private const val I_QUEUE_MS = 13
    private const val I_OK = 14
    private const val I_FAIL_OUT = 15
    private const val I_TIMEOUT_OUT = 16
    private const val I_INTR = 17
    private const val N_COUNTERS = 18

    // On-disk pref keys; index-aligned with the constants above.
    private val KEYS = arrayOf(
        "init_n", "init_fail", "init_ms", "shutdown_n", "shutdown_ms",
        "synth_n", "synth_ms", "synth_max_ms", "timeout", "rotate",
        "jni_open_fail", "native_load_fail", "queue_n", "queue_ms",
        "ok", "fail", "timeout_req", "interrupted",
    )

    private val lock = Any()
    private var appContext: Context? = null
    private var lastPersistElapsed = 0L

    // This process's counters (reset to zero at process start; never folded
    // from the shared aggregate, so a later merge cannot clobber the other
    // process's already-merged numbers).
    private val local = LongArray(N_COUNTERS)
    // How much of [local] is already merged into the shared on-disk aggregate.
    // outstanding = local[i] - baseline[i].
    private val baseline = LongArray(N_COUNTERS)
    // Mirror of the shared on-disk aggregate (merged across the main and :tts
    // processes). This is what the Crashlytics keys present (plus deltas that
    // have not yet reached disk).
    private val shared = LongArray(N_COUNTERS)
    // Cross-process reset generation (review #2): bumped by [clear] on opt-out.
    // An idle process that is still holding pre-opt-out deltas observes the bump
    // at its next merge and discards them before they could merge after re-enable.
    private var cachedGeneration = 0L
    // Host-regression seam: invoked between the delta snapshot and the baseline
    // advance so the harness can interleave a concurrent record and prove it stays
    // outstanding. Always null in production.
    private var mergeSeam: (() -> Unit)? = null
    // Host-regression seam for the fail-closed consent guard (review #1): when
    // armed, throws on the next consent read to prove a prefs store / file-lock
    // failure is swallowed and never breaks synthesis or initialization.
    // Always null in production.
    private var consentReadSeam: (() -> Unit)? = null
    // Set when the opt-out diagnostic wipe failed to persist (review #6): a
    // failed clear leaves the previous on-disk aggregate unzeroed, so while
    // this latch is up the engine treats the persisted base as zero and never
    // resurrects pre-opt-out counts by merging on top of the stale values.
    private var clearWriteFailed = false
    // Host-regression seam for review #6: when armed, its return value replaces
    // the MirroredPreferences.update() result (false/throw forces the failed-wipe
    // outcome deterministically). Always null in production.
    private var clearWriteSeam: (() -> Boolean)? = null

    // ----------------------------------------------------------------- lifecycle

    /** Called once per process at Application.onCreate. Loads the shared,
     *  cross-process aggregate into [shared] and attaches it to Crashlytics
     *  keys immediately, so the keys are never a cleared/zero window for the
     *  current run. Safe to call more than once. */
    fun init(context: Context) {
        appContext = context.applicationContext
        refreshShared()
        flush(now = true)
    }

    private fun flush(now: Boolean) {
        val ctx = appContext ?: return
        try {
            // Consent is checked FIRST, before the hot-path throttle, so an opted-out
            // session unconditionally drops its deltas (rolls the baseline) — even a
            // record whose flush falls inside the throttle window must not stay
            // "outstanding" and leak into the shared store when consent is re-enabled.
            if (!crashEnabled(ctx)) {
                synchronized(lock) { System.arraycopy(local, 0, baseline, 0, N_COUNTERS) }
                return
            }
            val elapsed = SystemClock.elapsedRealtime()
            if (!now && elapsed - lastPersistElapsed < PERSIST_INTERVAL_MS) return
            lastPersistElapsed = elapsed
            mergeDeltas(ctx)
            attachCrashKeys(ctx)
        } catch (t: Throwable) {
            // Telemetry must never break the app: any persistence/consent failure here
            // is swallowed so the record*() calls made from finally blocks (synthesis
            // worker, initialize, shutdown) can never mask the real engine result.
        }
    }

    /** Consent read with a fail-closed guard (review #1): if the prefs store or its
     *  file lock cannot be read, treat crash reporting as OFF — drop deltas and never
     *  attach keys — and above all never let a telemetry read throw into the speech,
     *  shutdown, or initialization paths. */
    private fun crashEnabled(context: Context): Boolean = try {
        consentReadSeam?.invoke()
        TelemetryPrefs.isCrashEnabled(context)
    } catch (t: Throwable) { false }

    // ------------------------------------------------------------- record site

    fun recordInit(ms: Long, ok: Boolean) {
        synchronized(lock) {
            local[I_INIT_N]++
            local[I_INIT_MS] += ms
            if (!ok) local[I_INIT_FAIL]++
        }
        flush(now = true)
    }

    fun recordShutdown(ms: Long) {
        synchronized(lock) {
            local[I_SHUT_N]++
            local[I_SHUT_MS] += ms
        }
        flush(now = true)
    }

    /** Wall-clock duration of one native synthesis attempt, success or failure:
     *  failed / null / timed-out attempts are recorded too, so latency totals
     *  are not biased toward the requests that happened to succeed. Never
     *  includes the utterance text — only the elapsed milliseconds. */
    fun recordSynth(ms: Long) {
        synchronized(lock) {
            local[I_SYNTH_N]++
            local[I_SYNTH_MS] += ms
            if (ms > local[I_SYNTH_MAX]) local[I_SYNTH_MAX] = ms
        }
        flush(now = false)
    }

    fun recordSynthTimeout() {
        synchronized(lock) { local[I_TIMEOUT]++ }
        flush(now = true)
    }

    fun recordRotate() {
        synchronized(lock) { local[I_ROTATE]++ }
        flush(now = true)
    }

    fun recordJniOpenFail() {
        synchronized(lock) { local[I_JNI]++ }
        flush(now = true)
    }

    fun recordNativeLoadFail() {
        synchronized(lock) { local[I_NATIVE]++ }
        flush(now = true)
    }

    /** Time spent waiting in the speech queue before synthesis started. */
    fun recordQueueWait(ms: Long) {
        synchronized(lock) {
            local[I_QUEUE_N]++
            local[I_QUEUE_MS] += ms
        }
        flush(now = false)
    }

    /** The final disposition of a synthesis request. */
    fun recordOutcome(code: Int) {
        synchronized(lock) {
            when (code) {
                RC_OK -> local[I_OK]++
                RC_FAIL -> local[I_FAIL_OUT]++
                RC_TIMEOUT -> local[I_TIMEOUT_OUT]++
                RC_INTERRUPTED -> local[I_INTR]++
                else -> Unit
            }
        }

        flush(now = false)
    }

    /** The SINGLE final disposition of a synthesis request (reviews #2, #3).
     *
     *  The TTS service calls this once when an utterance terminates and records
     *  the returned code — and ONLY this code — so a request is counted exactly
     *  once and never under two buckets (e.g. old code double-counted a watchdog
     *  timeout as both RC_TIMEOUT from the engine and RC_FAIL from the service).
     *  Priority: interrupted > timeout > fail > ok, and the result is fail-closed.
     *
     *  [ok] must be the request DID complete its framework completion callback;
     *  it is only true when done() returned normally (a failing done() is a
     *  failure, not a success — issue #3).
     */
    fun finalOutcome(interrupted: Boolean, timedOut: Boolean, failed: Boolean, ok: Boolean): Int = when {
        interrupted -> RC_INTERRUPTED
        timedOut -> RC_TIMEOUT
        failed -> RC_FAIL
        ok -> RC_OK
        else -> RC_FAIL // no explicit success disposition => not a success
    }

    // ------------------------------------------------------------ crash attach

    /** Copy the combined cross-process window into Crashlytics custom keys so a
     *  crash or hang carries fresh engine-health numbers (not a stale init
     *  window). Kept to sparse numeric strings so the keys carry no content. */
    private fun attachCrashKeys(context: Context) {
        // When the user has turned off crash reporting, do not populate any
        // Crashlytics keys this session (nothing would transmit, and keys should
        // never be populated on a session that opted out).
        if (!crashEnabled(context)) return
        val v = combined()
        CrashlyticsFx.setKey("m_init_n", v[I_INIT_N].toString())
        CrashlyticsFx.setKey("m_init_fail", v[I_INIT_FAIL].toString())
        CrashlyticsFx.setKey("m_init_ms", v[I_INIT_MS].toString())
        CrashlyticsFx.setKey("m_shutdown_ms", v[I_SHUT_MS].toString())
        CrashlyticsFx.setKey("m_synth_n", v[I_SYNTH_N].toString())
        CrashlyticsFx.setKey("m_synth_max_ms", v[I_SYNTH_MAX].toString())
        CrashlyticsFx.setKey("m_synth_avg_ms", avgMs(v[I_SYNTH_MS], v[I_SYNTH_N]))
        CrashlyticsFx.setKey("m_timeout", v[I_TIMEOUT].toString())
        CrashlyticsFx.setKey("m_rotate", v[I_ROTATE].toString())
        CrashlyticsFx.setKey("m_jni_open_fail", v[I_JNI].toString())
        CrashlyticsFx.setKey("m_native_load_fail", v[I_NATIVE].toString())
        CrashlyticsFx.setKey("m_queue_wait_avg_ms", avgMs(v[I_QUEUE_MS], v[I_QUEUE_N]))
        CrashlyticsFx.setKey("m_ok", v[I_OK].toString())
        CrashlyticsFx.setKey("m_fail", v[I_FAIL_OUT].toString())
        CrashlyticsFx.setKey("m_timeout_req", v[I_TIMEOUT_OUT].toString())
        CrashlyticsFx.setKey("m_interrupted", v[I_INTR].toString())
    }

    private fun avgMs(totalMs: Long, count: Long) = if (count > 0) (totalMs / count).toString() else "0"

    // -------------------------------------------------- cross-process persistence

    /** The window presented to Crashlytics = shared on-disk aggregate plus this
     *  process's not-yet-merged deltas. synth_max is a max (not additive), so it
     *  is taken as the larger of the two. */
    private fun combined(): LongArray {
        val out = LongArray(N_COUNTERS)
        synchronized(lock) {
            for (i in 0 until N_COUNTERS) {
                out[i] = if (i == I_SYNTH_MAX)
                    maxOf(shared[i], local[i])
                else
                    shared[i] + (local[i] - baseline[i])
            }
        }
        return out
    }

    /** Merge THIS process's outstanding increments into the shared aggregate in
     *  one atomic cross-process read-modify-write, then advance the baseline and
     *  refresh [shared]. synth_max is merged by max, not sum. */
    private fun mergeDeltas(context: Context) {
        val deltas = LongArray(N_COUNTERS)
        val mergedLocal = LongArray(N_COUNTERS)
        var snapshotGen: Long
        synchronized(lock) {
            // Snapshot the WHOLE local state at delta-capture time. The baseline
            // is advanced to THIS snapshot (never to the live [local]) after a
            // successful write, so an increment that lands during the disk I/O
            // stays outstanding for the next merge instead of being dropped (review #1).
            System.arraycopy(local, 0, mergedLocal, 0, N_COUNTERS)
            for (i in 0 until N_COUNTERS) deltas[i] = mergedLocal[i] - baseline[i]
            // Remember which reset generation this delta batch was produced in, so
            // the merge transaction can detect a sibling clear that lands between
            // this snapshot and the write (review #3, check-then-act guard).
            snapshotGen = cachedGeneration
        }
        // Cross-process clear (review #2): if another process cleared the retained
        // snapshot (opt-out) since this process last synchronized, discard any
        // pre-opt-out deltas we still hold before they could merge into a fresh,
        // post-re-enable window.
        if (generationChanged(context)) {
            synchronized(lock) { System.arraycopy(local, 0, baseline, 0, N_COUNTERS) }
            refreshShared()
            return
        }
        var any = false
        for (d in deltas) if (d != 0L) { any = true; break }
        if (!any) return
        // Host-regression seam: interleave a concurrent record here so the harness
        // can prove an increment landing after the snapshot remains outstanding.
        mergeSeam?.invoke()
        var ok = false
        var aborted = false
        try {
            ok = MirroredPreferences(context, PREFS_NAME).update { map ->
                // Check-then-act guard (review #3): validate the reset generation
                // INSIDE the same cross-process transaction that applies deltas. The
                // pre-check above already returned "unchanged", but a sibling clear
                // could land between that check and this locked write; re-reading the
                // generation here (under the file lock, so it cannot change again)
                // detects it and discards the whole pre-opt-out batch without touching
                // the freshly cleared counters.
                val storedGen = map[GEN_KEY] as? Long ?: 0L
                if (storedGen != snapshotGen) { aborted = true; return@update }
                for (i in 0 until N_COUNTERS) {
                    if (i == I_SYNTH_MAX) {
                        // A failed opt-out wipe (review #6) must never resurrect the
                        // previous max: treat the persisted base as zero while the
                        // clear-write-failed latch is up.
                        map[KEYS[i]] = if (clearWriteFailed) mergedLocal[I_SYNTH_MAX]
                                       else maxOf(map[KEYS[i]] as? Long ?: 0L, mergedLocal[I_SYNTH_MAX])
                    } else if (deltas[i] != 0L) {
                        val base = if (clearWriteFailed) 0L else (map[KEYS[i]] as? Long ?: 0L)
                        map[KEYS[i]] = base + deltas[i]
                    }
                }
            }
        } catch (t: Throwable) { return }
        if (aborted) {
            // A sibling cleared between our snapshot and the merge transaction.
            // Discard the pre-opt-out deltas (advance baseline so they never
            // re-merge) and resync to the freshly cleared store.
            synchronized(lock) { System.arraycopy(local, 0, baseline, 0, N_COUNTERS) }
            refreshShared()
            return
        }
        if (!ok) return
        // Advance the baseline to the delta-capture snapshot — never to the live
        // counters. A record made after the snapshot is still outstanding here.
        synchronized(lock) { System.arraycopy(mergedLocal, 0, baseline, 0, N_COUNTERS) }
        refreshShared()
    }

    /** Detect whether another process cleared the retained aggregate (opt-out)
     *  since this process last synchronized, and record that observation. */
    private fun generationChanged(context: Context): Boolean {
        val now = try {
            MirroredPreferences(context, PREFS_NAME).getLong(GEN_KEY, 0L)
        } catch (t: Throwable) { return false }
        synchronized(lock) {
            return if (now != cachedGeneration) { cachedGeneration = now; true } else false
        }
    }

    /** Reload [shared] to mirror the on-disk aggregate (picks up the other
     *  process's merges), and cache the current reset generation. */
    private fun refreshShared() {
        val ctx = appContext ?: return
        val fresh = LongArray(N_COUNTERS)
        var generation = 0L
        try {
            val p = MirroredPreferences(ctx, PREFS_NAME)
            for (i in 0 until N_COUNTERS) fresh[i] = p.getLong(KEYS[i], 0)
            generation = p.getLong(GEN_KEY, 0L)
        } catch (t: Throwable) { return }
        synchronized(lock) {
            System.arraycopy(fresh, 0, shared, 0, N_COUNTERS)
            cachedGeneration = generation
        }
    }

    /** Clear every retained diagnostic snapshot (shared on-disk aggregate, local
     *  deltas and baselines). Called when the user turns crash reporting OFF in
     *  Settings, so retained diagnostics are not silently uploaded if the toggle
     *  is later re-enabled. Returns the durable wipe result (review #6): a failed
     *  persistent wipe is flagged locally and the engine refuses to resurrect the
     *  old aggregate on a later re-enable. */
    fun clear(context: Context): Boolean {
        synchronized(lock) {
            Arrays.fill(local, 0L)
            Arrays.fill(baseline, 0L)
            Arrays.fill(shared, 0L)
        }
        // Bump the shared generation so any other process still holding
        // pre-opt-out deltas in memory can detect and discard them before they
        // could merge after a later re-enable (review #2). Zero the snapshot too.
        var ok = false
        try {
            // A clear MUST land: retry the atomic wipe. A failed wipe is an
            // incomplete privacy operation (review #6).
            var attempt = 0
            while (!ok && attempt++ < 3) {
                ok = clearWriteSeam?.invoke()
                    ?: MirroredPreferences(context, PREFS_NAME).update { map ->
                        for (key in KEYS) map[key] = 0L
                        map[GEN_KEY] = ((map[GEN_KEY] as? Long ?: 0L) + 1L)
                    }
            }
        } catch (t: Throwable) { ok = false }
        if (ok) {
            clearWriteFailed = false
            refreshShared() // observe our own bump so cachedGeneration stays current
        } else {
            // Privacy wipe did not persist. Keep the in-memory snapshot zeroed,
            // mark the failure so merges never resurrect the old aggregate, and
            // surface it locally (never via telemetry). New reports are already
            // halted because the caller disabled Crashlytics (review #4).
            clearWriteFailed = true
            Log.w("EngineMetrics", "clear: persistent diagnostic wipe FAILED; treating retained aggregate as zero (review #6)")
        }
        return ok
    }
}