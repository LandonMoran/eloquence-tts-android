package com.xw.vvtts.utils

import android.util.Log
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

/**
 * Multi-language detection + text segmenter.
 *
 * Layer 1:Unicode rules(O(n)single-pass scan,zero latency,100% precise)
 *   kana -> Japanese,Hangul -> Korean,Han -> Chinese,Latin -> Latin runs
 *   spaces/punct/separators -> always follow the previous segment's language(regardless of the default language)
 *
 * Layer 2:Lingua statistics(Latin 10-language disambiguation;short text falls back to the default language)
 *
 * Simplified/Traditional is not judged here——the user picks the Chinese dialect(Simplified zh-CN / Traditional zh-TW).
 */
class LanguageDetector {

    class Segment(
        val text: String,
        val dialect: Int,
    )

    companion object {
        private const val TAG = "LangDetector"

            // ECI dialect constants
        const val DIALECT_EN_US = 0x10000
        const val DIALECT_EN_GB = 0x10001
        const val DIALECT_ES_ES =  0x20000
        const val DIALECT_ES_US =  0x20001
        const val DIALECT_ES_MX =  0x20002
        const val DIALECT_FR_FR = 0x30000
        const val DIALECT_FR_CA = 0x30001
        const val DIALECT_DE_DE = 0x40000
        const val DIALECT_IT_IT = 0x50000
        const val DIALECT_ZH_CN = 0x60000
        const val DIALECT_ZH_TW = 0x60001
        const val DIALECT_PT_BR = 0x70000
        const val DIALECT_JA_JP = 0x80000
        const val DIALECT_FI_FI = 0x90000
        const val DIALECT_KO_KR =  0xA0000
        const val DIALECT_PL_PL =  0x110000

        // Special value for "unspecified default language"
        const val DEFAULT_UNSPECIFIED = -1

            // all supported language codes(for the settings multi-select)
        // Only languages actually linked in this build (see build_native.sh LANGS).
        val ALL_LANG_CODES = arrayOf("en", "de", "fr", "es", "it", "ja", "pl", "pt", "fi", "zh")
        val ALL_LANG_NAMES = arrayOf(
            "English", "German", "French", "Spanish", "Italian", "Japanese", "Polish", "Portuguese", "Finnish", "Chinese",
        )

            // settings key
        @Volatile private var chineseDialect = DIALECT_ZH_CN
        @Volatile private var englishDialect = DIALECT_EN_US
        @Volatile private var spanishDialect = DIALECT_ES_ES
        @Volatile private var frenchDialect = DIALECT_FR_FR
        // Detection whitelist: by default English + Japanese + Chinese
        // (zh-CN is linked in this build via the oracle synth path).
        // Languages outside the whitelist fall back to the default language when detected.
        @Volatile private var enabledLanguages: Set<String> = HashSet(listOf("en", "ja", "zh"))
            // default language:DEFAULT_UNSPECIFIED(unspecified)or a specific dialect.
            // only active when "language detection is on":digits and unrecognized text use it;if unset,follow the previous segment.
        @Volatile private var defaultLanguage = DEFAULT_UNSPECIFIED

            // whether detection is off(set to false when a specific language is chosen in settings)
        @Volatile private var detectionEnabled = true
            // fixed dialect when detection is off
        @Volatile private var fixedDialect = DIALECT_EN_US

        private val lock = Any()
        @Volatile private var linguaDetector: com.github.pemistahl.lingua.api.LanguageDetector? = null
        @Volatile private var linguaInitFailed = false
        @Volatile private var linguaPreloaded = false

        fun setChineseDialect(dialect: Int) { chineseDialect = dialect }
        fun getChineseDialect() = chineseDialect

        fun setEnglishDialect(dialect: Int) { englishDialect = dialect }
        fun getEnglishDialect() = englishDialect

        fun setSpanishDialect(dialect: Int) { spanishDialect = dialect }
        fun getSpanishDialect() = spanishDialect

        fun setFrenchDialect(dialect: Int) { frenchDialect = dialect }
        fun getFrenchDialect() = frenchDialect

        fun setEnabledLanguages(langs: Set<String>?) {
            enabledLanguages = if (langs == null || langs.isEmpty()) HashSet() else HashSet(langs)
            // rebuild the Lingua detector immediately(the language whitelist changed)
            resetLingua()
        }

        fun getEnabledLanguages(): Set<String> = enabledLanguages

        /** Whether a language code is in the detection whitelist */
        fun isLanguageEnabled(code: String): Boolean {
            val en = enabledLanguages
            if (en == null) return false
            return en.contains(code)
        }

        /** Rebuild Lingua(call when the whitelist changes;takes effect immediately,no restart needed) */
        private fun resetLingua() {
            synchronized(lock) {
                linguaDetector = null
                linguaInitFailed = false
                linguaPreloaded = false
            }
            preloadLingua()
        }

        fun setDefaultLanguage(dialect: Int) { defaultLanguage = dialect }
        fun getDefaultLanguage() = defaultLanguage

        /**
         * parse the default language into a concrete dialect.
         * unspecified -> -1(digits/unrecognized text follow the previous segment).
         */
        fun resolveDefaultLanguage(): Int {
            val dl = defaultLanguage
            if (dl >= 0) return dl
            return -1 // unspecified
        }

        fun setDetectionEnabled(enabled: Boolean) { detectionEnabled = enabled }
        fun isDetectionEnabled() = detectionEnabled

        fun setFixedDialect(dialect: Int) { fixedDialect = dialect }
        fun getFixedDialect() = fixedDialect

        /** Preload Lingua(called in the background at app startup) */
        fun preloadLingua() {
            if (linguaPreloaded || linguaInitFailed) return
            Thread {
                getLingua()
                linguaPreloaded = true
            }.start()
        }

        private fun getLingua(): com.github.pemistahl.lingua.api.LanguageDetector? {
            val cached = linguaDetector
            if (cached != null) return cached
            if (linguaInitFailed) return null
            synchronized(lock) {
                val cached2 = linguaDetector
                if (cached2 != null) return cached2
                if (linguaInitFailed) return null
                try {
                    val t0 = System.currentTimeMillis()
                    val langs = getEnabledLanguageEnums()
                    linguaDetector = LanguageDetectorBuilder
                        .fromLanguages(*langs.toTypedArray())
                        .withMinimumRelativeDistance(0.0)
                        .withPreloadedLanguageModels()
                        .build()
                    Log.i(TAG, "Lingua loaded in " + (System.currentTimeMillis() - t0) + "ms, langs=" + langs.size)
                    return linguaDetector
                } catch (e: Throwable) {
                    Log.e(TAG, "Lingua init failed", e)
                    linguaInitFailed = true
                    return null
                }
            }
        }

        private fun getEnabledLanguageEnums(): List<Language> {
            // Lingua only disambiguates Latin scripts; CJK (zh/ja/ko) use Unicode
            // rules and never go through Lingua.
            val allLatin = ArrayList<Language>()
            allLatin.add(Language.ENGLISH)
            allLatin.add(Language.GERMAN)
            allLatin.add(Language.FRENCH)
            allLatin.add(Language.SPANISH)
            allLatin.add(Language.ITALIAN)
            allLatin.add(Language.PORTUGUESE)
            allLatin.add(Language.FINNISH)
            allLatin.add(Language.POLISH)

            val en = enabledLanguages
            if (en == null) return allLatin // theoretically can't happen; defensive

            val filtered = ArrayList<Language>()
            for (l in allLatin) {
                if (en.contains(languageToCode(l))) {
                    filtered.add(l)
                }
            }
            // need at least one language or Lingua's build fails
            // Lingua requires >=2 languages to build. The default whitelist
            // {en,ja,zh} yields only ENGLISH (ja/zh are CJK-excluded), so a
            // fresh install would throw every init into the // catch above.
            // Top up neutrally; downstream gating (detectLatin) re-checks the
            // real whitelist per segment, so fillers never leak through.
            if (filtered.size < 2) {
                if (!filtered.contains(Language.ENGLISH)) filtered.add(Language.ENGLISH)
                if (filtered.size < 2) filtered.add(Language.GERMAN)
            }
            return filtered
        }

        private fun languageToCode(lang: Language): String {
            if (lang == Language.ENGLISH) return "en"
            if (lang == Language.GERMAN) return "de"
            if (lang == Language.FRENCH) return "fr"
            if (lang == Language.SPANISH) return "es"
            if (lang == Language.ITALIAN) return "it"
            if (lang == Language.POLISH) return "pl"
            if (lang == Language.PORTUGUESE) return "pt"
            if (lang == Language.FINNISH) return "fi"
            return "en"
        }

        private fun languageToDialect(lang: Language): Int {
            if (lang == Language.ENGLISH) return englishDialect
            if (lang == Language.GERMAN) return DIALECT_DE_DE
            if (lang == Language.FRENCH) return frenchDialect
            if (lang == Language.SPANISH) return spanishDialect
            if (lang == Language.ITALIAN) return DIALECT_IT_IT
            if (lang == Language.POLISH) return DIALECT_PL_PL
            if (lang == Language.PORTUGUESE) return DIALECT_PT_BR
            if (lang == Language.FINNISH) return DIALECT_FI_FI
            return englishDialect
        }

        /**
         * Main entry:split mixed text into segments.
         * if detection is off,return the whole run + fixed dialect directly.
         */
        fun segment(text: String?): List<Segment> {
            val result = ArrayList<Segment>()
            if (text == null || text.isEmpty()) return result

            // detection off:whole run uses the fixed dialect
            if (!detectionEnabled) {
                result.add(Segment(text, fixedDialect))
                return result
            }

            // pass 1:Unicode run-splitting
            var current = StringBuilder()
            var currentType = -1  // 0=Chinese, 1=kana, 2=Hangul, 3=Latin,  4=separator,,  5=digit
            var lastRealType = 3  //the last non-separator type (default Latin)
            var lastDialect = englishDialect

            for (i in text.indices) {
                val c = text[i]
                val type = classifyChar(c)

                // separator(space/punct):always follows the previous segment's language
                if (type == 4) {
                    current.append(c)
                    continue
                }

                // digit:explicit default -> use it;otherwise follow the previous segment
                if (type == 5) {
                    if (currentType != 5) {
                        if (current.length > 0) {
                            flushSegment(current, currentType, lastDialect, result)
                        }
                        currentType = 5
                        current = StringBuilder()
                    }
                    current.append(c)
                    continue
                }

                // not a separator nor digit
                if (type != currentType) {
                    if (current.length > 0) {
                        flushSegment(current, currentType, lastDialect, result)
                    }
                    currentType = type
                    current = StringBuilder()
                }
                current.append(c)

                // remember the most recent non-separator language
                if (type in 0..3) {
                    lastRealType = type
                    lastDialect = typeToDialect(type, lastDialect)
                }
            }
            if (current.length > 0) {
                flushSegment(current, currentType, lastDialect, result)
            }

            // merge consecutive same-dialect runs
            mergeConsecutive(result)

            return result
        }

        /**
         * Character classification.
         * 0=Chinese(Han),1=Japanese kana,,2=Korean Hangul,,3=Latin,,4=separator
         */
        private fun classifyChar(c: Char): Int {
            // kana
            if (c.code in 0x3040..0x309F || c.code in 0x30A0..0x30FF) return 1
            // Hangul
            if (c.code in 0xAC00..0xD7AF) return 2
            // CJK Han(no Simplified-vs-Traditional split;all treated as Chinese)
            if (c.code in 0x4E00..0x9FFF) return 0
            if (c.code in 0x3400..0x4DBF) return 0
            // Latin letters
            if (c in 'A'..'Z' || c in 'a'..'z') return 3
            if (c.code in 0x00C0..0x024F) return 3
            // space(full-width and half-width both count as separators)
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return 4
            if (c.code == 0x3000) return 4  // full-width space
            // digit(half/full-width)-> standalone type 5(default language or follows previous segment)
            if (c in '0'..'9') return 5
            if (c.code in 0xFF10..0xFF19) return 5
            // punctuation separators(ASCII + CJK + full-width)
            if (isSeparator(c)) return 4
            // other ASCII printable(operators etc)-> Latin
            if (c.code in 0x20..0x7E) return 3
            // full-width punctuation
            if (c.code in 0xFF00..0xFFEF) return 4
            // CJK punctuation
            if (c.code in 0x3000..0x303F) return 4
            // default:Latin
            return 3
        }

        /** Separator test:spaces,punct,,symbols——these follow the previous segment's language */
        private fun isSeparator(c: Char): Boolean {
            // ASCII punctuation
            if (c.code <= 0x7F) {
                return c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':'
                    || c == '-' || c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '"' || c == '\''
                    || c == '/' || c == '\\' || c == '|' || c == '~'
                    || c == '`' || c == '@' || c == '#' || c == '$' || c == '%'
                    || c == '^' || c == '&' || c == '*' || c == '+' || c == '='
                    || c == '<' || c == '>' || c == '_'
            }
            return false
        }

        /** character type -> target language code(for whitelist checks) */
        private fun typeToCode(type: Int): String? {
            return when (type) {
                0 -> "zh"
                1 -> "ja"
                2 -> "ko"
                3 -> "en" // Latin placeholder;Lingua actually decides
                else -> null
            }
        }

        /** Character type -> ECI dialect(no whitelist check here;flushSegment handles the whitelist uniformly) */
        private fun typeToDialect(type: Int, fallbackDialect: Int): Int {
            return when (type) {
                0 -> chineseDialect
                1 -> DIALECT_JA_JP
                2 -> DIALECT_KO_KR
                3 -> fallbackDialect
                else -> fallbackDialect
            }
        }

        /** Whether a non-Latin type(CJK)passes detection:only valid if the whitelist contains that language */
        private fun cjkDialectOrFallback(type: Int, fallbackDialect: Int): Int {
            val code = typeToCode(type)
            if (code != null && isLanguageEnabled(code)) {
                return typeToDialect(type, fallbackDialect)
            }
            // not in whitelist:fallback to default language(if set),else English(never across to previous CJK)
            val dl = resolveDefaultLanguage()
            return if (dl >= 0) dl else englishDialect
        }

        /** Emit the current run as a Segment;Latin runs get refined by Lingua */
        private fun flushSegment(sb: StringBuilder, type: Int, fallbackDialect: Int, out: MutableList<Segment>) {
            if (sb.length == 0) return
            val text = sb.toString()
            sb.setLength(0)

            val dialect: Int
            if (type == 3) {
                // Latin run:Lingua detection(which re-checks the whitelist inside)
                dialect = detectLatin(text, fallbackDialect)
            } else if (type == 4) {
                // spaces/punct:follow the previous segment
                dialect = fallbackDialect
            } else if (type == 5) {
                // digit:default set -> use it;unset -> follow previous segment
                val dl = resolveDefaultLanguage()
                dialect = if (dl >= 0) dl else fallbackDialect
            } else {
                // Chinese/Japanese/Korean:check the whitelist;if absent,fallback to the default language
                dialect = cjkDialectOrFallback(type, fallbackDialect)
            }
            out.add(Segment(text, dialect))
        }

        /** Latin-text detection:always try Lingua first;only use the default language when detection returns null.
        * key:Latin text must never fall back to Chinese/Korean/Japanese——that would be wrong.
        * if the detected language isn't in the whitelist,,fall back to the default language(or English). */
        private fun detectLatin(text: String, fallbackDialect: Int): Int {
            // Short runs (names, loanwords, fragments( almost always belong to
                        // the user's base language. Don't let Lingua flip the voice mid-sentence:
                        // trust the pinned default(if Latin(, else the previous segment's dialect
                        // (else English. Real phrases(>=10 chars( still get detected.


            if (text.length <	10) {
                val dl = resolveDefaultLanguage()
                if (isLatinDialect(dl)) return dl
                return if (isLatinDialect(fallbackDialect)) fallbackDialect else englishDialect



            }
            val ld = getLingua()
            if (ld == null) {
                // Lingua unavailable:default Latin language(if default is Latin;otherwise English)
                val dl = resolveDefaultLanguage()
                return if (isLatinDialect(dl)) dl else englishDialect
            }

            try {
                val lang = ld.detectLanguageOf(text)
                if (lang == null) {
                    // detection returned null -> default language(Latin only),else English
                    val dl = resolveDefaultLanguage()
                    return if (isLatinDialect(dl)) dl else englishDialect
                }
                    // detected language goes through the whitelist:if absent -> fallback to default language
                val code = languageToCode(lang)
                if (!isLanguageEnabled(code)) {
                    val dl = resolveDefaultLanguage()
                    return if (isLatinDialect(dl)) dl else englishDialect
                }
                return languageToDialect(lang)
            } catch (e: Throwable) {
                // detection exception -> default language(Latin only),else English
                val dl = resolveDefaultLanguage()
                return if (isLatinDialect(dl)) dl else englishDialect
            }
        }

        /** Whether this dialect uses the Latin alphabet (all shipped Western dialects). */
        private fun isLatinDialect(dialect: Int): Boolean {
            return dialect == DIALECT_EN_US || dialect == DIALECT_EN_GB
                || dialect == DIALECT_DE_DE
                || dialect == DIALECT_FR_FR || dialect == DIALECT_FR_CA
                || dialect == DIALECT_ES_ES || dialect == DIALECT_ES_US || dialect == DIALECT_ES_MX
                || dialect == DIALECT_IT_IT
                || dialect == DIALECT_PL_PL
                || dialect == DIALECT_PT_BR || dialect == DIALECT_FI_FI
        }

        private fun mergeConsecutive(segments: MutableList<Segment>) {
            var i = segments.size - 1
            while (i > 0) {
                val cur = segments[i]
                val prev = segments[i - 1]
                if (cur.dialect == prev.dialect) {
                    segments[i - 1] = Segment(prev.text + cur.text, prev.dialect)
                    segments.removeAt(i)
                }
                i--
            }
        }
    }
}