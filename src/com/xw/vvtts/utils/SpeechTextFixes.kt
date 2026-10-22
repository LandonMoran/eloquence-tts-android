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
