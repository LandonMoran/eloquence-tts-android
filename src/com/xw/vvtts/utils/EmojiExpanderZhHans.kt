package com.xw.vvtts.utils

/**
 * Expands emoji into plain spoken text (CLDR zh-Hans short names)
 * before text reaches the ECI engine, which has no emoji lexicon and
 * otherwise stays silent. Applied in onSynthesizeText before segmentation.
 *
 * Matches longest full sequences first (ZWJ families, flags, skin-tone
 * variants, variation-selected forms); unknown emoji-block codepoints
 * and bare components (ZWJ, VS16, skin tones) are dropped so they do not
 * reach the engine as unmappable bytes. Table regenerated from Unicode
 * emoji-test.txt + CLDR annotations (English tts.))
 */
class EmojiExpanderZhHans {
    companion object {
        private val NAMES: Map<String, String> = buildMap {
            putAll(EMOJI_ZHANS_01)
            putAll(EMOJI_ZHANS_02)
            putAll(EMOJI_ZHANS_03)
            putAll(EMOJI_ZHANS_04)
            putAll(EMOJI_ZHANS_05)
            putAll(EMOJI_ZHANS_06)
            putAll(EMOJI_ZHANS_07)
            putAll(EMOJI_ZHANS_08)
            putAll(EMOJI_ZHANS_09)
            putAll(EMOJI_ZHANS_10)
        }
        private val SORTED_KEYS: List<String> = NAMES.keys.sortedByDescending { it.length }

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
                if (KEY_START_CPS.contains(cp)) {
                    for (k in SORTED_KEYS) {
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
