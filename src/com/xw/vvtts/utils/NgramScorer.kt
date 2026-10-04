package com.xw.vvtts.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.zip.GZIPInputStream

/**
 * Fast in-RAM Latin detection over the shipped pruned n-gram tables
 * (assets/ngram_uni_bi.tsv.gz, generated from the language-models JSON files).
 * The index order (0..7) MUST match LanguageDetector.getEnabledLanguageEnums():
 * EN, DE, FR, ES, IT, PT, FI, PL.
 *
 * Primary path on the delivery thread; Lingua is demoted to ambiguous-only
 * fallback. Scores are sum-of-frequencies over present n-grams, normalized
 * by hit count;a 10% margin gate sends uncertain runs back to Lingua.
 *
 * Tables are built completely off to the side in private builder maps and
 * published as one immutable read-only snapshot under a volatile reference,
 * so concurrent detect() calls can never observe a partially populated table
 * and no caller can mutate a published snapshot.
 *
 * Unigram and bigram frequencies are normalized by their own independent
 * per-language totals (#222(, so the two populations do not distort each
 * other's normalization.
 *
 * A load failure is retried in-call with bounded exponential backoff and the
 * failure is exposed for diagnostics;the gate never becomes permanent --a
 * later load() call (e.g. next service start) retries cleanly, so a transient
 * asset/I/O failure does not silently disable the primary detector for the
 * life of the process.
 */
object NgramScorer {

    private const val TAG = "NgramScorer"
    private const val MAX_LOAD_ATTEMPTS = 5
    private const val BACKOFF_BASE_MS = 1000L
    private const val BACKOFF_MAX_MS = 60_000L

    private val LANG_CODES = arrayOf("en", "de", "fr", "es", "it", "pt", "fi", "pl")

    /** Immutable published scoring tables. All maps are read-only views; never mutate in place. */
    private class Snapshot(
        val unis: Array<Map<Int, Float>?>,
        val bis: Array<Map<Int, Float>?>,
        val uniTotals: FloatArray,
        val biTotals: FloatArray,
    )

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var loadAttempts: Int = 0
    @Volatile private var lastFailureAt: Long = 0L
    @Volatile private var lastFailure: String? = null
    private val loadLock = Any()

    /** Whether the scoring tables are ready (or still loading/retrying). */
    val isReady: Boolean get() = snapshot != null

    /** Human-readable description of the last load failure, if any (diagnostics). */
    val failureDescription: String? get() = lastFailure

    /**
     * Loads the n-gram tables, retrying in-call with bounded exponential backoff.
     * Safe to call from multiple threads (internally locked); no-ops once a
     * snapshot is published. On success the attempt/failure state resets;on
     * exhaustion the last failure stays visible for diagnostics and a later
     * call may retry (never permanently disabled). Interrupts abort the wait.
     */
    fun load(context: Context) {
        synchronized(loadLock) {
            if (snapshot != null) return
            var attempt = 0
            while (attempt < MAX_LOAD_ATTEMPTS) {
                if (Thread.currentThread().isInterrupted) return
                val now = SystemClock.elapsedRealtime()
                if (lastFailureAt != 0L && now - lastFailureAt < backoffDelayMs()) return
                try {
                    snapshot = buildTables(context)
                    loadAttempts = 0
                    lastFailure = null
                    Log.i(TAG, "n-gram tables loaded")
                    return
                } catch (e: Throwable) {
                    attempt++
                    loadAttempts = attempt
                    lastFailureAt = SystemClock.elapsedRealtime()
                    lastFailure = e.message ?: e.javaClass.simpleName
                    Log.e(TAG, "n-gram tables load failed (attempt $attempt/$MAX_LOAD_ATTEMPTS)", e)
                    if (attempt < MAX_LOAD_ATTEMPTS) {
                        try {
                            Thread.sleep(backoffDelayMs())
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                }
            }
        }
    }

    private fun backoffDelayMs(): Long =
        Math.min(BACKOFF_BASE_MS shl (loadAttempts.coerceAtMost(6) - 1), BACKOFF_MAX_MS)


    /** Builds complete local tables (never touching a published snapshot), then freezes them. */
    private fun buildTables(context: Context): Snapshot {
        val unigrams = arrayOfNulls<HashMap<Int, Float>>(8)
        val bigrams = arrayOfNulls<HashMap<Int, Float>>(8)
        val uniTotals = FloatArray(8)
        val biTotals = FloatArray(8)

        val raw = context.assets.open("ngram_uni_bi.tsv.gz")
        BufferedReader(InputStreamReader(GZIPInputStream(raw), "UTF-8")).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val fields = line.split('\t')
                if (fields.size != 4) continue
                val idx = LANG_CODES.indexOf(fields[0])
                if (idx < 0) continue
                val freq = fields[2].toIntOrNull() ?: 0
                if (freq <= 0) continue
                val isUni = fields[1] == "unigrams"
                if (isUni) uniTotals[idx] += freq.toFloat() else biTotals[idx] += freq.toFloat()
                val key = keyOf(fields[3])
                if (key < 0) continue
                val maps = if (isUni) unigrams else bigrams
                val map = maps[idx] ?: HashMap<Int, Float>(64).also { maps[idx] = it }
                map.put(key, freq.toFloat())
            }
        }
        normalizeInPlace(unigrams, bigrams, uniTotals, biTotals)

        // Freeze the private builder maps into read-only views before publication so
        // no caller can ever mutate a published snapshot.

        val uniViews = arrayOfNulls<Map<Int, Float>>(8)
        val biViews = arrayOfNulls<Map<Int, Float>>(8)
        for (idx in 0..7) {
            uniViews[idx] = unigrams[idx]?.let { Collections.unmodifiableMap(it) }
            biViews[idx] = bigrams[idx]?.let { Collections.unmodifiableMap(it) }
        }
        return Snapshot(uniViews, biViews, uniTotals, biTotals)
    }

    private fun normalizeInPlace(
        unigrams: Array<HashMap<Int, Float>?>,
        bigrams: Array<HashMap<Int, Float>?>,
        uniTotals: FloatArray,
        biTotals: FloatArray,
    ) {
        for (idx in 0..7) {
            val ut = uniTotals[idx]
            if (ut > 0f) {
                unigrams[idx]?.let { m ->
                    // Entry.setValue does NOT touch modCount, so in-place normalization
                    // is safe while iterating (HashMap forbids put/remove here --that
                    // would throw ConcurrentModificationException on the next next()).
                    val it = m.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        e.setValue(e.value / ut)
                    }
                }
            }
            val bt = biTotals[idx]
            if (bt > 0f) {
                bigrams[idx]?.let { m ->
                    val it = m.entries.iterator()
                    while (it.hasNext()) {
                        val e = it.next()
                        e.setValue(e.value / bt)
                    }
                }
            }
        }
    }

    private fun keyOf(s: String): Int {
        if (s.isEmpty()) return -1
        val c0 = s[0].code
        return if (s.length == 1) c0 else ((c0 shl 16) or s[1].code)
    }

    private fun scoreText(snap: Snapshot, text: String, idx: Int): Float {
        val unis = snap.unis[idx]
        val bis = snap.bis[idx]
        if (unis == null && bis == null) return -1f
        var score = 0.0f
        var hits = 0
        var i = 0
        val n = text.length
        val chars = text.toCharArray()
        while (i < n) {
            val c = chars[i].code
            unis?.get(c)?.let { v ->
                score += v
                hits++
            }
            if (i + 1 < n) {
                val bk = (c shl 16) or chars[i + 1].code
                bis?.get(bk)?.let { v ->
                    score += v
                    hits++
                }
            }
            i++
        }
        return if (hits == 0) -1f else score / hits
    }

    /** Returns language index 0..7 (LANG_CODES order) or -1 when unsure/tables unloaded. */
    fun detect(text: String, enabled: Set<String>): Int {
        val snap = snapshot ?: return -1
        var best = -1
        var bestScore = 0.0f
        var second = 0.0f
        var i = 0
        while (i < 8) {
            if (enabled.contains(LANG_CODES[i])) {
                val s = scoreText(snap, text, i)
                if (s >= 0) {
                    if (s > bestScore) {
                        second = bestScore
                        bestScore = s
                        best = i
                    } else if (s > second) {
                        second = s
                    }
                }
            }
            i++
        }
        if (best < 0) return -1
        if (bestScore > 0 && bestScore - second < bestScore * 0.10f) return -1
        return best
    }
}