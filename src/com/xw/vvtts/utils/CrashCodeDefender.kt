package com.xw.vvtts.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.text.Charsets

/**
 * Crash-code defender - belt-and-braces gate in front of the delta rule machine.
 *
 * The delta rule machine dies with an unhandled page fault when certain strings
 * (e.g. "uncosp") drive a rule into nought arithmetic:a null node reference
 * or a divisor of zero. Native guards now make the engine survive by speaking
 * or giving the utterance up;this gate keeps known hostile shapes from ever even
 * reaching the guarded path, rewriting them into spaced forms the engine voices
 * normally.
 *
 * The saying behind all of the corpus: it is every one-letter neighborhood of the
 * seeds that took the original engine down ( words, apostrophe-led clock times,
 * and the follow-on family(. A dictionary alone covers only observed shapes;the
 * engine-side guards cover the whole class;;this gate is the cheap first layer.

 * The corpus ships gzipped as an asset (~48KB(;a missing or damaged asset fails
 * open:speech is never blocked by this gate.e
 */
object CrashCodeDefender {

    @Volatile
    private var loaded: Boolean = false
    @Volatile
    private var corpus: Set<String> = emptySet()
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

    private fun isBreak(c: Char): Boolean =
        !(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '\'')

    private fun rewrite(t: String): String {
        if (!corpus.contains(t)) return t
        // First corpus-free split point; both halves must be corpus-free so the
        // rewrite cannot re-trigger the same guarded path. Single letters are
        // never corpus members, so the letter-by-letter fallback always exists.


        for (i in 1 until t.length) {
            val left = t.substring(0, i)
            val right = t.substring(i)
            if (!corpus.contains(left) && !corpus.contains(right)) {
                return left + " " + right
            }
        }
        return t.toCharArray().joinToString(" ")
    }

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(gate) {
            if (loaded) return
            try {
                val set = HashSet<String>(65536)
                ctx.assets.open("crashers.txt.gz").use { raw ->
                    GZIPInputStream(raw).use { gz ->
                        val reader = BufferedReader(InputStreamReader(gz, Charsets.UTF_8))
                        reader.forEachLine { line ->
                            val l = line.trim()
                            if (l.isNotEmpty() && !l.startsWith("#")) set.add(l)
                        }
                    }
                }
                corpus = set
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load crashers corpus;running without defender", t)
            } finally {
                loaded = true
            }
        }
    }
}
