package com.xw.vvtts.utils

/**
 * Expands emoji into plain spoken text (CLDR English short names)
 * before text reaches the ECI engine, which has no emoji lexicon and
 * otherwise stays silent. Applied in onSynthesizeText before segmentation.
 *
 * Matches longest full sequences first (ZWJ families, flags, skin-tone
 * variants, variation-selected forms); unknown emoji-block codepoints
 * and bare components (ZWJ, VS16, skin tones) are dropped so they do not
 * reach the engine as unmappable bytes. Table regenerated from Unicode
 * emoji-test.txt + CLDR annotations (English tts.))
 */
class EmojiExpander {
    companion object {
        private val NAMES: Map<String, String> = buildMap {
            putAll(EMOJI_01.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_02.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_03.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_04.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_05.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_06.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_07.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_08.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_09.mapKeys { (k, _) -> unescape(k) })
            putAll(EMOJI_10.mapKeys { (k, _) -> unescape(k) })
        }

        /** CLDR tables store emoji code points as literal text: a backslash char, then 'u',
         *  then 4 hex digits (e.g. the 6-char sequence backslash-u-D-8-3-D). That breaks
         *  longest-match: codePointAt(0) of such a key yields the backslash code point, so
         *  KEY_START_CPS never contains a real emoji code point and expand() silently passes
         *  emoji through unexpanded (the engine has no emoji lexicon, so it stays silent).
         *  Convert the literal escapes to real chars once, when the lookup table loads, so
         *  the longest-match scan below runs on real sequences. */
        private fun unescape(s: String): String {
            if (!s.any { it.code == 0x5C }) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.code == 0x5C && i + 5 < s.length && s[i + 1] == 'u') {
                    sb.append(String(Character.toChars(Integer.parseInt(s.substring(i + 2, i + 6), 16))))
                    i += 6
                } else {
                    sb.append(c)
                    i += 1
                }
            }
            return sb.toString()
        }

        private val SORTED_KEYS: List<String> = NAMES.keys.sortedByDescending { it.length }
        /** Per-start-codepoint buckets, longest-first, so expand() only scans the few
         *  candidates that can actually match at a given position, O(1) instead of the ~3600-key scan. */
        private val KEYS_BY_START_CP: Map<Int, List<String>> =
            SORTED_KEYS.groupBy { it.codePointAt(0) }

        /** Strip bare emoji components (no-name codepoints used inside sequences). */
        /** First code point of every emoji key — lets expand() skip the ~3600-key scan
         *  for the overwhelming majority of positions (plain text). Cheap O(1) gate. */
        private val KEY_START_CPS: java.util.HashSet<Int> =
            java.util.HashSet<Int>().apply { SORTED_KEYS.forEach { add(it.codePointAt(0)) } }

        private fun isEmojiComponent(cp: Int): Boolean {
            return cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E
                || (cp in 0x1F3FB..0x1F3FF)
        }

        /**
         * Replace emoji with spoken names via longest-match on the full sequence.
         * unmatched emoji components are dropped, everything else passes through. */
        fun expand(input: String?): String? {
            if (input == null || input.isEmpty()) return input
            val sb = StringBuilder(input.length + 16)
            var i = 0
            var lastWasName = false
            while (i < input.length) {
                val cp = input.codePointAt(i)
                var matched = false
                val candidates = KEYS_BY_START_CP[cp]
                if (candidates != null) {
                    for (k in candidates) {
                        if (input.startsWith(k, i)) {
                            val name = NAMES[k] ?: break
                            if (lastWasName) sb.append(' ')
                            else if (sb.length > 0 && !Character.isWhitespace(sb[sb.length - 1])) sb.append(' ')
                            sb.append(name)
                            lastWasName = true
                            i += k.length
                            matched = true
                            break
                        }
                    }
                }
                if (matched) continue
                i += Character.charCount(cp)
                if (isEmojiComponent(cp)) continue
                sb.appendCodePoint(cp)
                lastWasName = false
            }
            return sb.toString()
        }
    }
}
