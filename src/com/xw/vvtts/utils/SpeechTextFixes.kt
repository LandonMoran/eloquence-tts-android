package com.xw.vvtts.utils

/**
 * Text fixes applied before text reaches the Eloquence engine. Ported from
 * trypsynth/evvdroid (TextFixes, Pauses and Prosody), which credits davidacm's
 * NVDA IBMTTS driver for the rules. Pure string logic; no engine API is used.
 */
object SpeechTextFixes {

    /** Leave the engine's own pauses alone. */
    const val PAUSES_KEEP = 0

    /** Shorten only the gap after the last thing said. */
    const val PAUSES_END_ONLY = 1

    /** Shorten the gaps at punctuation as well. */
    const val PAUSES_ALL = 2

    /** Pauses the engine's default, as in evvdroid. */
    const val PAUSES_DEFAULT = PAUSES_ALL

    /**
     * Rewrite words the engine would otherwise spell letter by letter, such as
     * "teamtalk5" (a letter against a digit) and "books (s)".
     */
    fun fixText(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = BEFORE_A_DIGIT.replace(out, "$1 $2")
        out = BEFORE_AN_OPENER.replace(out, "$1 $2")
        out = LOOSE_S_SUFFIX.replace(out, "$1$2")
        out = SPACE_BEFORE_A_MARK.replace(out, "$1$2")
        out = GROUPED_THOUSANDS.replace(out) { it.value.replace(",", "") }
        return out
    }

    /**
     * Shorten the engine's pauses at punctuation and at the end of [text],
     * using the `p annotation. [last] says whether this is the end of what was
     * asked for, since the gap after that is the one [PAUSES_END_ONLY] is about.
     */
    fun shortenPauses(text: String, mode: Int, last: Boolean): String {
        if (mode == PAUSES_KEEP || text.isEmpty()) return text
        var out = text
        if (mode == PAUSES_ALL) out = AT_A_MARK.replace(out, "$1 $BRIEF$2$3$4")
        // The engine pauses at the end of an utterance whether or not anything
        // there asked it to. A mark at the end has already been dealt with.
        if (last && out.trimEnd().lastOrNull()?.let { it !in PAUSE_MARKS } ?: false) out = "$out $AT_THE_END"
        return out
    }

    /**
     * Turn text into characters the Windows-1252 engine can read. Typographic
     * marks get plain equivalents, a character with an accent gives up its base
     * letter, and anything else becomes a space rather than a '?'. Ported from
     * evvdroid's WesternText.flatten.
     */
    fun flattenWestern(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val plain = PLAIN_WESTERN[c]
            when {
                plain != null -> out.append(plain)
                c.code == 0 -> out.append(' ')
                c.code <= 0xFF -> out.append(c)
                else -> out.append(stripWestern(text, i))
            }
            i += if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
        }
        return out.toString()
    }

    private val PLAIN_WESTERN: Map<Char, String> = mapOf(
        '\u2018' to "'", '\u2019' to "'", '\u201A' to "'", '\u201B' to "'",
        '\u201C' to "\"", '\u201D' to "\"", '\u201E' to "\"", '\u201F' to "\"",
        '\u2010' to "-", '\u2011' to "-", '\u2012' to "-", '\u2013' to "-",
        '\u2014' to "-", '\u2015' to "-", '\u2212' to "-",
        '\u2026' to "...", '\u2022' to ".", '\u00B7' to ".",
        '\u00A0' to " ", '\u2002' to " ", '\u2003' to " ", '\u200B' to "",
        '\u200C' to "", '\u200D' to "", '\uFEFF' to "",
        '\u2039' to "<", '\u203A' to ">", '\u2032' to "'", '\u2033' to "\"",
        '\u2044' to "/", '\u2215' to "/",
        '\u2122' to " trademark ", '\u2116' to " number ",
        '\u20AC' to " euro ", '\u00A3' to " pounds ", '\u00A5' to " yen ",
        '\u2264' to " less than or equal to ", '\u2265' to " greater than or equal to ",
        '\u2260' to " not equal to ", '\u2248' to " about ", '\u221E' to " infinity ",
        '\u2192' to " to ", '\u2190' to " from ",
    )

    /** Keep the base letter of an accented character outside the byte set, else a space. */
    private fun stripWestern(text: String, at: Int): String {
        val c = text[at]
        val one = if (Character.isHighSurrogate(c) && at + 1 < text.length) {
            text.substring(at, at + 2)
        } else {
            c.toString()
        }
        val base = java.text.Normalizer.normalize(one, java.text.Normalizer.Form.NFD)
            .filter { it.code <= 0xFF && !isCombiningMark(it) }
        return if (base.isEmpty()) " " else base
    }

    private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** Annotation that turns phrase prediction on or off for one utterance. */
    fun phrasePredictionPrefix(phrasePrediction: Boolean): String =
        if (phrasePrediction) PHRASE_ON else PHRASE_OFF

    private const val PHRASE_ON = "`pp1 "
    private const val PHRASE_OFF = "`pp0 "

    /** The marks the engine pauses at. */
    private const val PAUSE_MARKS = "-,.:;?!\u2013\u2014"

    /** A mark that is really punctuation rather than part of a number. */
    private val AT_A_MARK = Regex("([A-Za-z0-9]|\\s)([$PAUSE_MARKS])(\\2*?)(\\s|[\\\\/]|$)")

    private const val BRIEF = "`p1"

    private const val AT_THE_END = "`p100"

    /** A letter against a digit, which is the whole of the teamtalk5 problem. */
    private val BEFORE_A_DIGIT = Regex("""([A-Za-z])(\d)""")

    /** Marks the engine starts spelling in front of. */
    private const val OPENERS = "~#\$%^*({|\\[<\"\u2022"

    private val BEFORE_AN_OPENER = Regex("([A-Za-z]+)([" + Regex.escape(OPENERS) + "])")

    /** "books (s)" back to "books(s)". */
    private val LOOSE_S_SUFFIX = Regex("""([A-Za-z]+)\s+(\(s\))""")

    /** The engine does not tolerate a space in front of a punctuation mark. */
    private val SPACE_BEFORE_A_MARK = Regex("""([A-Za-z]+|\d+|\W+)\s+([:.!;,?](?![A-Za-z]|\d))""")

    /** A number grouped with commas around a round thousand. */
    private val GROUPED_THOUSANDS = Regex("""\b\d{1,3},000(?:,\d{3})+\b""")
}
