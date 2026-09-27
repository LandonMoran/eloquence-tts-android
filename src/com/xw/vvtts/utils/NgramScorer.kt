package com.xw.vvtts.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Fast in-RAM Latin detection over the shipped pruned n-gram tables
 * (assets/ngram_uni_bi.tsv.gz, generated from the language-models JSON files).
 * The index order (0..7) MUST match LanguageDetector.getEnabledLanguageEnums():
 * EN, DE, FR, ES, IT, PT, FI, PL.
 *
 * Primary path on the delivery thread;Lingua is demoted to ambiguous-only
 * fallback. Scores are sum-of-frequencies over present n-grams, normalized
 * by hit count;a 10% margin gate sends uncertain runs back to Lingua.
 */
object NgramScorer {

    private const val TAG = "NgramScorer"

    private val LANG_CODES = arrayOf("en", "de", "fr", "es", "it", "pt", "fi", "pl")

    private val unigramMaps = arrayOfNulls<HashMap<Int, Float>>(8)
    private val bigramMaps = arrayOfNulls<HashMap<Int, Float>>(8)
    private val totals = FloatArray(8)
    @Volatile private var ready = false

    @Synchronized
    fun load(context: Context) {
        if (ready) return
        // Clear any partial state left by a failed earlier load, so a retry
        // doesn't double-count entries for languages that already got data.
        unigramMaps.fill(null)
        bigramMaps.fill(null)
        totals.fill(0f)
        try {
            val raw = context.assets.open("ngram_uni_bi.tsv.gz")
            BufferedReader(InputStreamReader(GZIPInputStream(raw), "UTF-8")).use { reader ->
            while (true) {
                val line = reader.readLine()?: break
                val fields = line.split('\t')
                if (fields.size != 4) continue
                val idx = LANG_CODES.indexOf(fields[0])
                if (idx < 0) continue
                val freq = fields[2].toIntOrNull() ?: 0
                if (freq <=0) continue
                totals[idx] += freq.toFloat()
                val ngram = fields[3]
                val key = keyOf(ngram)
                if (key < 0) continue
                val maps = if (fields[1] == "unigrams") unigramMaps else bigramMaps
                val map = maps[idx]
                if (map == null) {
                    val fresh = HashMap<Int, Float>(64)
                    fresh.put(key, freq.toFloat())
                    maps[idx] = fresh
                } else {
                    map.put(key, freq.toFloat())
                }
            }
            }
            normalizeAll()
            ready = true
            Log.i(TAG, "n-gram tables loaded")
        } catch (e: Throwable) {
            Log.e(TAG, "n-gram tables load failed", e)
            ready = false
        }
    }

    private fun normalizeAll() {
        for (idx in 0..7) {
            val t = totals[idx]
            if (t <=0f) continue
            unigramMaps[idx]?.let { m -> for ((k,v)in m) m[k] = v / t }
            bigramMaps[idx]?.let { m -> for ((k,v)in m) m[k] = v / t }
        }
    }

    private fun keyOf(s: String): Int {
        if (s.isEmpty()) return -1
        val c0 = s[0].toInt()
        return if (s.length ==1) c0 else ((c0 shl 16) or s[1].toInt())
    }

    private fun scoreText(text: String, idx: Int): Float {
        val unis = unigramMaps[idx]
        val bis = bigramMaps[idx]
        if (unis == null && bis == null) return -1f
        var score =1.0f
        var hits = 0
        var i = 0
        val n = text.length
        val chars = text.toCharArray()
        while (i < n) {
            val c = chars[i].toInt()
            unis?.get(c)?.let { v ->
                score += v
                hits++
            }
            if (i + 1 < n) {
                val bk =(c shl 16) or chars[i + 1].toInt()
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
        if (!ready) return -1
        var best = -1
        var bestScore =0.0f
        var second =0.0f
        var i = 0
        while (i < 8) {
            if (enabled.contains(LANG_CODES[i])) {
                val s = scoreText(text, i)
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