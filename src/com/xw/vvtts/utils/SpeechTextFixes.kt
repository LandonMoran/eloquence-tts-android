package com.xw.vvtts.utils

import java.nio.charset.CharsetEncoder

/**
 * Text fixes applied before text reaches the Eloquence engine. Ported from
 * trypsynth/evvdroid (TextFixes, Pauses and Prosody), which credits davidacm's
 * NVDA IBMTTS driver for the rules. Pure string logic; no engine API is used.
 */
object SpeechTextFixes {

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
     * Turn text into characters the Windows-1252 engine can read. Typographic
     * marks get plain equivalents, a character with an accent gives up its base
     * letter, and anything else becomes a space rather than a '?'. Ported from
     * evvdroid's WesternText.flatten.
     */
    fun flattenWestern(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        val windows1252 = WINDOWS_1252.newEncoder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val plain = PLAIN_WESTERN[c]
            when {
                plain != null -> out.append(plain)
                c.code == 0 -> out.append(' ')
                windows1252.canEncode(c) -> out.append(c)
                else -> out.append(stripWestern(text, i, windows1252))
            }
            i += if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
        }
        return out.toString()
    }

    private val WINDOWS_1252 = java.nio.charset.Charset.forName("windows-1252")

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
    private fun stripWestern(text: String, at: Int, windows1252: CharsetEncoder): String {
        val c = text[at]
        val one = if (Character.isHighSurrogate(c) && at + 1 < text.length) {
            text.substring(at, at + 2)
        } else {
            c.toString()
        }
        val base = java.text.Normalizer.normalize(one, java.text.Normalizer.Form.NFD)
            .filter { !isCombiningMark(it) && windows1252.canEncode(it) }
        return if (base.isEmpty()) " " else base
    }

    private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

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
