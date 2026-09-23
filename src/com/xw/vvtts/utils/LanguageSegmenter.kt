package com.xw.vvtts.utils

/**
 * Routing for mixed Chinese-English text(ported from Guangrong's BilingualRoutingPolicy).
 *
 * Fixes "CJK voices not reading English":Apple's zh/ja/ko banks only recognize native scripts,
 * dropping pure ASCII English/letter runs outright. The right move isn't forcing the zh bank to read English,
 * but splitting by language——zh runs go to the zh engine,,en runs to the en engine,,and PCM is stitched at the end.
 *
 * Guangrong's logic(reverse-engineered from routeSegment):
 *   segmentByLanguage=true and the current run is English -> ENGLISH voice
 *   otherwise -> CHINESE voice
 */
class LanguageSegmenter {
    class Segment(
        val text: String,
       /** true=English run;false=Chinese run */
        val english: Boolean,
    )

    companion object {
       /** Split by language:returned runs——Chinese/symbol/digit go Chinese;pure-English-letter runs go English. */
        fun segment(text: String?): List<Segment> {
            val out = ArrayList<Segment>()
            if (text == null || text.isEmpty()) return out

            var cur = StringBuilder()
            var curType = -1  // 0=Chinese/other, 1=English letter
            for (i in text.indices) {
                val c = text[i]
                val type = if (isEnglishChar(c)) 1 else 0
                if (curType == -1) {
                    curType = type
                } else if (type != curType) {
                    out.add(Segment(cur.toString(), curType == 1))
                    cur = StringBuilder()
                    curType = type
                }
                cur.append(c)
            }
            if (cur.length > 0) {
                out.add(Segment(cur.toString(), curType == 1))
            }
            return out
        }

       /** Whether a run is English letters(a-z A-Z);digitsand symbols don't count(digits go to the Chinese digit reader) */
        private fun isEnglishChar(c: Char): Boolean {
            return (c in 'a'..'z') || (c in 'A'..'Z')
        }

       /** Whether a run contains English(lets callers decide whetherthe whole run goes to the en engine) */
        fun containsEnglish(text: String?): Boolean {
            if (text == null) return false
            for (i in text.indices) {
                if (isEnglishChar(text[i])) return true
            }
            return false
        }

       /** Whether a run contains CJK(Chinese/Japanese/Korean) */
        fun containsCjk(text: String?): Boolean {
            if (text == null) return false
            for (i in text.indices) {
                val c = text[i]
                if ((c.code in 0x4E00..0x9FFF)      // base Han
                        || (c.code in 0x3400..0x4DBF)  // extension A
                        || (c.code in 0x3040..0x30FF)  // Japanese kana
                        || (c.code in 0xAC00..0xD7AF)) { // Hangul
                    return true
                }
            }
            return false
        }
    }
}