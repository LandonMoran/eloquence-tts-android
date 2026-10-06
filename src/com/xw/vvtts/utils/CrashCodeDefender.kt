package com.xw.vvtts.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.Reader
import java.util.Locale
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.text.Charsets

/**
 * Crash-code defender - belt-and-braces gate in front of the delta rule machine.
 *
 * The delta rule machine dies with an unhandled page fault when certain strings
 * (e.g. "uncosp") drive a rule into nought arithmetic: a null node reference
 * or a divisor of zero. Native guards now make the engine survive by speaking
 * or giving the utterance up; this gate keeps known hostile shapes from ever
 * even reaching the guarded path, rewriting them into spaced forms the engine
 * voices normally.
 *
 * The corpus is every one-letter neighborhood of the seeds that took the
 * original engine down: words, apostrophe-led clock times, and the follow-on
 * family. A dictionary alone covers only observed shapes; the engine-side
 * guards cover the whole class; this gate is the cheap first layer.
 *
 * The corpus ships gzipped as an asset (~48KB); a missing or damaged asset
 * fails open (speech is never blocked by this gate) and reload is retried
 * with bounded backoff on later utterances - a transient read failure never
 * permanently disables the defense.
 *
 * Tokenization is Unicode-aware: any letter or digit (including accented and
 * CJK) belongs to a token, and corpus membership is tested on an ASCII-folded
 * key, so hostile shapes cannot evade the gate by swapping in diacritics or
 * combining marks. Decompression is bounded (line count, cumulative size, and
 * token length caps) so a tampered/corrupt asset cannot exhaust memory while
 * expanding; any bound violation fails open and retries like an I/O failure.
 */
object CrashCodeDefender {

    private const val TAG = "CrashCodeDefender"

    private const val MAX_CORPUS_LINES = 200_000L
    private const val MAX_CORPUS_CHARS = 4L * 1024L * 1024L
    private const val MAX_TOKEN_LEN = 1024
    private const val RETRY_BASE_MS = 5_000L
    private const val RETRY_MAX_MS = 60_000L

    @Volatile
    private var loaded: Boolean = false
    @Volatile
    private var corpus: Set<String> = emptySet()
    @Volatile
    private var attemptFailures: Int = 0
    @Volatile
    private var lastFailureAt: Long = 0L
    private val gate = Any()

    fun sanitize(context: Context?, text: String): String {
        if (text.isEmpty()) return text
        val ctx = context ?: return text
        ensureLoaded(ctx)
        if (corpus.isEmpty()) return text
        val out = StringBuilder(text.length + 32)
        val tok = StringBuilder()
        for (i in text.indices) {
            val c = text[i]
            if (isBreak(c)) {
                if (tok.isNotEmpty()) {
                    out.append(rewrite(tok.toString()))
                    tok.setLength(0)
                }
                out.append(c)
            } else {
                tok.append(c)
            }
        }
        if (tok.isNotEmpty()) out.append(rewrite(tok.toString()))
        return out.toString()
    }

    private fun isBreak(c: Char): Boolean {
        if (c.isLetterOrDigit() || c == '\'') return false
        return when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> false
            else -> true
        }
    }

    /**
     * ASCII fold for corpus membership: NFKD-normalize, drop combining marks,
     * lowercase. Corpus entries are plain ASCII, so this folds diacritic and
     * combining-mark variants of a hostile word onto the same key.
     */
    private fun fold(t: String): String {
        val nfkd = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKD)
        val sb = StringBuilder(nfkd.length)
        for (c in nfkd) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    private fun rewrite(t: String): String {
        val key = fold(t)
        if (key.isEmpty() || !corpus.contains(key)) return t
        // First folded-corpus-free split point; both halves must fold outside
        // the corpus so the rewrite cannot re-trigger the same guarded path.
        // Single letters are never corpus members, so the letter-by-letter
        // fallback always exists.
        for (i in 1 until t.length) {
            if (!corpus.contains(fold(t.substring(0, i))) &&
                !corpus.contains(fold(t.substring(i)))
            ) {
                return t.substring(0, i) + " " + t.substring(i)
            }
        }
        return t.toCharArray().joinToString(" ")
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val now = SystemClock.elapsedRealtime()
        if (lastFailureAt != 0L && now - lastFailureAt < retryDelayMs()) return
        synchronized(gate) {
            if (loaded) return
            try {
                corpus = ctx.assets.open("crashers.txt.gz").use { raw ->
                    GZIPInputStream(raw).use { gz -> readCorpus(InputStreamReader(gz, Charsets.UTF_8)) }
                }
                loaded = true
                attemptFailures = 0
            } catch (t: Throwable) {
                attemptFailures++
                lastFailureAt = SystemClock.elapsedRealtime()
                Log.e(TAG, "failed to load crashers corpus; defense off (retry in ${retryDelayMs()}ms)", t)
            }
        }
    }

    /** Enforce limits before allocating a complete decompressed line. */
    internal fun readCorpus(input: Reader): Set<String> {
        val reader = input.buffered()
        val result = HashSet<String>()
        val line = StringBuilder()
        var chars = 0L
        var lines = 0L
        fun accept() {
            check(++lines <= MAX_CORPUS_LINES) { "Too many corpus lines" }
            val word = line.toString().trim()
            if (word.isNotEmpty() && !word.startsWith("#")) result += fold(word)
            line.setLength(0)
        }
        while (true) {
            val c = reader.read()
            if (c == -1) { if (line.isNotEmpty()) accept(); break }
            check(++chars <= MAX_CORPUS_CHARS) { "Corpus exceeds size bound" }
            if (c == '\n'.code) accept()
            else { check(line.length < MAX_TOKEN_LEN) { "Corpus line too long" }; line.append(c.toChar()) }
        }
        return result
    }

    private fun retryDelayMs(): Long =
        Math.min(RETRY_BASE_MS shl (attemptFailures.coerceAtMost(6) - 1), RETRY_MAX_MS)
}