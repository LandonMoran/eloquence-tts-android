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
        // Detection whitelist: by default every shipped language is enabled,so a
        // fresh install works with zero configuration (seamless multi-language TTS(. Users
        // can prune languages in settings to speed up/steady detection;pruned languages
        // fall back to the default language when detected.
        @Volatile private var enabledLanguages: Set<String> = ALL_LANG_CODES.toSet()
            // default language:DEFAULT_UNSPECIFIED(unspecified)or a specific dialect.
            // only active when "language detection is on":digits and unrecognized text use it;if unset,follow the previous segment.
        @Volatile private var defaultLanguage = DEFAULT_UNSPECIFIED

            // whether detection is off(set to false when a specific language is chosen in settings)
        @Volatile private var detectionEnabled = true
            // fixed dialect when detection is off
        @Volatile private var fixedDialect = DIALECT_EN_US

        private val lock = Any()
        /** Serializes the transient per-utterance pin block in runSynthesis() against detection-config setters. */
        val stateLock = Any()
        @Volatile private var linguaDetector: com.github.pemistahl.lingua.api.LanguageDetector? = null
        @Volatile private var linguaInitFailed = false
        @Volatile private var linguaPreloaded = false
        // Single-flight guard for the current generation. Resetting the whitelist
        // allows a fresh preload; older workers must not update its flags.
        @Volatile private var linguaInitInFlight = false
        @Volatile private var linguaPreloadThread: Thread? = null
        private val linguaBuildLock = Any()
        private var linguaGeneration = 0L // Guarded by lock.

        // Memo of recent Lingua-decided dialects. The n-gram scan is the largest single
        // per-segment cost on the delivery thread, and TalkBack constantly re-announces
        // the same strings (labels, names, widgets) on every swipe. Caching just the
        // successful Lingua decisions (text-deterministic,context-free) makes those
        // repeats zero-cost. Bounded;cleared whenever detection config that affects
        // results changes (whitelist / en/es/fr dialect pins). The fallback/ASCII
        // short-run paths are context-dependent and already cheap,so they never
        // touch this cache.
        private val latinCache = LinkedHashMap<String, Int>(128)
        private const val LATIN_CACHE_MAX = 512
        private fun invalidateLatinCache() { synchronized(latinCache) { latinCache.clear() } }

        fun setChineseDialect(dialect: Int) { synchronized(stateLock) { chineseDialect = dialect } }
        fun getChineseDialect() = chineseDialect

        fun setEnglishDialect(dialect: Int) { synchronized(stateLock) { englishDialect = dialect; invalidateLatinCache() } }
        fun getEnglishDialect() = englishDialect

        fun setSpanishDialect(dialect: Int) { synchronized(stateLock) { spanishDialect = dialect; invalidateLatinCache() } }
        fun getSpanishDialect() = spanishDialect

        fun setFrenchDialect(dialect: Int) { synchronized(stateLock) { frenchDialect = dialect; invalidateLatinCache() } }
        fun getFrenchDialect() = frenchDialect

        private val transientEnabled = ThreadLocal<Set<String>?>()

        fun setEnabledLanguages(langs: Set<String>?) {
            synchronized(stateLock) {
            val en = enabledLanguages
            if (langs == null || langs.isEmpty()) {
                // Clear the whitelist, but reuse an already-empty set.
                if (en.isEmpty()) return
                enabledLanguages = HashSet()
                resetLingua()
                return
            }
            // Identity/equality shortcut:refreshSettings() re-pushes the same set
            // object on every utterance;skip the copy+rebuild entirely when nothing
            // changed. (The old path allocated a fresh HashSet every utterance.)
            if (langs == en) {
                enabledLanguages = en
                return
            }
            enabledLanguages = HashSet(langs)
            // whitelist changed:rebuild Lingua immediately,no restart needed
            resetLingua()
            }
        }

        fun getEnabledLanguages(): Set<String> = java.util.Collections.unmodifiableSet(enabledLanguages)

        /** Per-utterance pin (e.g. zh from a picker row(,applied on top of the whitelist
         * without rebuilding Lingua. Cleared from the finally block. */
        fun setTransientEnabledLangs(langs: Set<String>?) {
            transientEnabled.set(langs)
        }

        /** Whether a language code is in the detection whitelist */
        fun isLanguageEnabled(code: String): Boolean {
            val te = transientEnabled.get()
            if (te != null && te.contains(code)) return true
            return enabledLanguages.contains(code)
        }

        /** Rebuild Lingua(call when the whitelist changes;takes effect immediately,no restart needed) */
        private fun resetLingua() {
            invalidateLatinCache()
            synchronized(lock) {
                linguaPreloadThread?.interrupt()
                linguaPreloadThread = null
                linguaGeneration++
                linguaDetector = null
                linguaInitFailed = false
                linguaPreloaded = false
                // A whitelist change invalidates any in-flight preload (its result would
                // be stale(;allow the next preloadLingua()/detection to build fresh.
                linguaInitInFlight = false
            }
            preloadLingua()
        }

        fun setDefaultLanguage(dialect: Int) {
            synchronized(stateLock) {
                if (dialect == defaultLanguage) return
                defaultLanguage = dialect
                invalidateLatinCache()
            }
        }
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

        fun setDetectionEnabled(enabled: Boolean) {
            synchronized(stateLock) {
                if (enabled == detectionEnabled) return
                detectionEnabled = enabled
                invalidateLatinCache()
            }
        }
        fun isDetectionEnabled() = detectionEnabled

        fun setFixedDialect(dialect: Int) {
            synchronized(stateLock) {
                if (dialect == fixedDialect) return
                fixedDialect = dialect
                invalidateLatinCache()
            }
        }
        fun getFixedDialect() = fixedDialect

        /** Owned daemon worker. Model loading is lazy, so cancellation never waits
         * for a preload of every n-gram model inside the third-party builder. */
        fun preloadLingua() {
            synchronized(lock) {
                if (linguaPreloaded || linguaInitFailed || linguaInitInFlight) return
                linguaInitInFlight = true
                val generation = linguaGeneration
                linguaPreloadThread = Thread {
                    try {
                        if (!Thread.currentThread().isInterrupted) getLingua()
                    } finally {
                        synchronized(lock) {
                            if (generation == linguaGeneration) {
                                linguaInitInFlight = false
                                linguaPreloaded = linguaDetector != null
                                linguaPreloadThread = null
                            }
                        }
                    }
                }.apply { name = "lingua-preload"; isDaemon = true; start() }
            }
        }

        /** Interrupt the owned preload and invalidate its generation so stale work cannot publish. */
        fun cancelPreload() {
            synchronized(lock) {
                linguaGeneration++
                linguaPreloadThread?.interrupt()
                linguaPreloadThread = null
                linguaInitInFlight = false
            }
        }

        /** Lazily build a detector for enabled languages, returning null on failure or obsolete work. */
        private fun getLingua(): com.github.pemistahl.lingua.api.LanguageDetector? {
            linguaDetector?.let { return it }
            if (linguaInitFailed || Thread.currentThread().isInterrupted) return null
            synchronized(linguaBuildLock) {
                linguaDetector?.let { return it }
                val generation = synchronized(lock) { linguaGeneration }
                if (Thread.currentThread().isInterrupted) return null
                return try {
                    val langs = getEnabledLanguageEnums()
                    if (langs.size < 2) return null
                    var builder = LanguageDetectorBuilder.fromLanguages(*langs.toTypedArray())
                        .withMinimumRelativeDistance(0.0)
                    if (langs.size >= 4) builder = builder.withLowAccuracyMode()
                    val detector = builder.build()
                    synchronized(lock) {
                        if (generation != linguaGeneration || Thread.currentThread().isInterrupted) null
                        else { linguaDetector = detector; detector }
                    }
                } catch (e: Exception) {
                    synchronized(lock) {
                        if (generation == linguaGeneration && !Thread.currentThread().isInterrupted) linguaInitFailed = true
                    }
                    Log.e(TAG, "Lingua init failed", e)
                    null
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

            val filtered = ArrayList<Language>()
            for (l in allLatin) {
                if (en.contains(languageToCode(l))) {
                    filtered.add(l)
                }
            }
            // No filler candidates: Lingua requires >=2 languages to build, but
            // padding the candidate set with disabled languages would let those
            // fillers skew classifications (#182). When the user's real whitelist has
            // fewer than two Latin languages, getLingua() skips building entirely
            // and the caller returns the sole enabled language directly.
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
            synchronized(stateLock) {
            val result = ArrayList<Segment>()
            if (text == null || text.isEmpty()) return result

            // detection off:whole run uses the fixed dialect
            if (!detectionEnabled) {
                result.add(Segment(text, fixedDialect))
                return result
            }

            // pass 1:Unicode run-splitting
            var current = StringBuilder()
            var currentType = -1  // 0=Chinese, 1=kana, 2=Hangul, 3=Latin,  4=separator,   5=digit
            // Leading digit/date runs flush with the RUN's dialect; start from the
            // user's default language when set (e.g. zh) instead of hard English so a
            // leading timestamp doesn't get voiced in the wrong language.
            var lastDialect = resolveDefaultLanguage().takeIf { it >= 0 }
                ?: localeLatinDialect().takeIf { it >= 0 } ?: englishDialect

            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                val type = classifyCodePoint(cp)
                val unitEnd = i + Character.charCount(cp)

                // separator(space/punct):always follows the previous segment's language
                if (type == 4) {
                    current.append(text, i, unitEnd)
                    i = unitEnd
                    continue
                }

                // digit:explicit default -> use it;otherwise follow the previous segment
                if (type == 5) {
                    if (currentType != 5) {
                        if (current.length > 0) {
                            flushSegment(current, currentType, lastDialect, result)
                        }
                        currentType = 5
                    }
                    current.append(text, i, unitEnd)
                    i = unitEnd
                    continue
                }

                // not a separator nor digit
                if (type != currentType) {
                    if (current.length > 0) {
                        flushSegment(current, currentType, lastDialect, result)
                    }
                    currentType = type
                }
                current.append(text, i, unitEnd)

                // remember the most recent non-separator language
                if (type in 0..3) {
                    lastDialect = typeToDialect(type, lastDialect)
                }
                i = unitEnd
            }
            if (current.length > 0) {
                flushSegment(current, currentType, lastDialect, result)
            }

            // merge consecutive same-dialect runs
            mergeConsecutive(result)

            return result
            }
        }

        /**
         * Character classification.
         * 0=Chinese(Han),1=Japanese kana, 2=Korean Hangul, 3=Latin, 4=separator
         */
        private fun classifyCodePoint(cp: Int): Int {
            // kana
            if (cp in 0x3040..0x309F || cp in 0x30A0..0x30FF) return 1
            // Hangul
            if (cp in 0xAC00..0xD7AF) return 2
            // CJK Han(no Simplified-vs-Traditional split;all treated as Chinese)
            if (cp in 0x4E00..0x9FFF) return 0
            if (cp in 0x3400..0x4DBF) return 0
            // CJK Extension B-H and Compatibility Ideographs (surrogate pairs)
            if (cp in 0x20000..0x2EBEF) return 0
            if (cp in 0x30000..0x3134F) return 0
            if (cp in 0xF900..0xFAFF) return 0
            // Latin letters
            if (cp in 0x41..0x5A || cp in 0x61..0x7A) return 3
            if (cp in 0x00C0..0x024F) return 3
            // space(full-width and half-width both count as separators)
            if (cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0D) return 4
            if (cp == 0x3000) return 4  // full-width space
            // digit(half/full-width)-> standalone type 5(default language or follows previous segment)
            if (cp in 0x30..0x39) return 5
            if (cp in 0xFF10..0xFF19) return 5
            // punctuation separators(ASCII + CJK + full-width)
            if (isSeparator(cp)) return 4
            // other ASCII printable(operators etc)-> Latin
            if (cp in 0x20..0x7E) return 3
            // full-width punctuation
            if (cp in 0xFF00..0xFFEF) return 4
            // CJK punctuation
            if (cp in 0x3000..0x303F) return 4
            // default:Latin
            return 3
        }

        /** Separator test:spaces,punct,symbols——these follow the previous segment's language */
        private fun isSeparator(cp: Int): Boolean {
            // ASCII punctuation
            if (cp <= 0x7F) {
                return cp == 0x2C || cp == 0x2E || cp == 0x21 || cp == 0x3F || cp == 0x3B || cp == 0x3A
                    || cp == 0x2D || cp == 0x28 || cp == 0x29 || cp == 0x5B || cp == 0x5D
                    || cp == 0x7B || cp == 0x7D || cp == 0x22 || cp == 0x27
                    || cp == 0x2F || cp == 0x5C || cp == 0x7C || cp == 0x7E
                    || cp == 0x60 || cp == 0x40 || cp == 0x23 || cp == 0x24 || cp == 0x25
                    || cp == 0x5E || cp == 0x26 || cp == 0x2A || cp == 0x2B || cp == 0x3D
                    || cp == 0x3C || cp == 0x3E || cp == 0x5F
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

        /** Numeric runs must not feed the engine an unshipped dialect (Hangul/Kana\n raw seeds crash the native voice-table walk). */
        private fun isShippedDialect(d: Int): Boolean {
            return when (d) {
                DIALECT_EN_US, DIALECT_EN_GB, DIALECT_ES_ES, DIALECT_ES_US, DIALECT_ES_MX,
                DIALECT_FR_FR, DIALECT_FR_CA, DIALECT_DE_DE, DIALECT_IT_IT, DIALECT_ZH_CN,
                DIALECT_PT_BR, DIALECT_JA_JP, DIALECT_FI_FI, DIALECT_PL_PL -> true
                else -> false
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

        /** True when the segment has at least one letter and every letter belongs to a
         *  Latin script block (Basic Latin, Latin-1 Supplement, Latin Extended A/B,
         *  Latin Extended Additional, Combining Diacritical Marks). CJK/Hangul/
         *  Hiragana/Katakana runs return false so genuine CJK text is left alone.
         *  Pure-ASCII runs like "release" return true. */
        private fun isAllLatinRun(text: String): Boolean {
            var hasLetter = false
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetter(cp)) {
                    hasLetter = true
                    val cpIsLatin = (cp in  0x0000..0x024F) || (cp in  0x1E00..0x1EFF)
                    if (!cpIsLatin) {
                        return false
                    }
                }
                i += Character.charCount(cp)
            }
            return hasLetter
        }

        /** True when the run contains stray General-Punctuation-block characters that
         *  Lingua's n-grams can't make sense of (em/en-dashes, curly quotes, bullets,
         *  ellipses(. Spanish/Portuguese inverted marks(¿¡( and guillemets(«»( are
         *  excluded:they're genuine language markers outside the General Punctuation block. */
        private fun hasStrayGeneralPunct(text: String): Boolean {
            var i = text.length
            while (i > 0) {
                val cp = text.codePointBefore(i)
                if (cp in  0x2010..0x202F) return true
                i -= Character.charCount(cp)
            }
            return false
        }

        /** True when non-letter noise (emoji, ASCII/General punctuation, digits,
         *  symbols( outweighs the letters in a Latin run. Lingua's n-grams can't
         *  make sense of stretched ad-copy like \"TB Storage 🚀, 💬 Comment
         *  \"Gemini\" and DM u/Top_Deal'\" — pure ASCII/emoji clutter on short words —
         *  and hasStrayGeneralPunct() misses it because emoji live far outside
         *  U+2010..U+202F and ASCII punct/digits aren't in that block either. Treat
         *  noise-dominated runs the same as dash-cluttered ones:floor them to the
         *  pinned Latin default. Genuine language markers outside the General-Punctuation
         *  block are NOT noise:Spanish/Portuguese inverted marks,guillemets,and
         *  combining diacritics that belong to neighboring letters. */
        private fun isNoiseCluttered(text: String): Boolean {
            var letters = 0
            var noise =  0
            var i =  0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetter(cp)) {
                    letters++
                } else if (!Character.isWhitespace(cp)) {
                    val isLangMarker = (cp ==  0x00A1 || cp ==  0x00BF || cp ==  0x00AB || cp ==   0x00BB) ||
                        cp in  0x0300..0x036F
                    if (!isLangMarker) noise++
                }
                i += Character.charCount(cp)
            }
            return letters >  0 && noise *  5 >= letters
        }

        /** Emit the current run as a Segment;Latin runs get refined by Lingua */
        private fun flushSegment(sb: StringBuilder, type: Int, fallbackDialect: Int, out: MutableList<Segment>) {
            if (sb.length == 0) return
            val text = sb.toString()
            sb.setLength(0)

            var dialect: Int
            if (type == 3) {
                // Latin run:Lingua detection(which re-checks the whitelist inside)
                dialect = detectLatin(text, fallbackDialect)
            } else if (type == 4) {
                // spaces/punct:follow the previous segment
                dialect = fallbackDialect
            } else if (type == 5) {
                // Digits with detection on follow the surrounding language (numbers,dates,
                // years in names and sentences belong to the neighboring text, never to a
                // stale default like zh). The fixed-language path (detection off) never
                // reaches here: segment() returns the whole run with the fixed dialect directly.

                val dl = resolveDefaultLanguage()
                val fb = if (isShippedDialect(fallbackDialect)) fallbackDialect else englishDialect
                dialect = if (dl >= 0 && isLatinDialect(dl)) dl else fb

            } else {
                // Chinese/Japanese/Korean:check the whitelist;if absent,fallback to the default language
                dialect = cjkDialectOrFallback(type, fallbackDialect)
            }
            // HARD INVARIANT: a run whose letters are ALL Latin must never be spoken with a
            // CJK dialect. "release" (pure A-Z) was once spoken as Chinese because
            // detectLatin()/resolveDefaultLanguage() fall back to defaultDialect=zh on
            // uncertain single-word runs and digit-run fallbacks, and a CJK branch can
            // otherwise route Latin text into a CJK dialect. Force the en-US Latin floor
            // whenever the resolved dialect is not itself Latin. The only intentional
            // exception — fixed-dialect mode consciously pinned zh by the user — never
            // reaches here: the fixed-language path short-circuits in segment() to emit the
            // whole run with fixedDialect directly, so flushSegment always runs with detection
            // ON.
            val dl = resolveDefaultLanguage()
            if (isAllLatinRun(text)) {
                if (!isLatinDialect(dialect)) {
                    // non-Latin guess on an all-Latin run ("release" -> zh):hard English floor
                    dialect = englishDialect
                } else if (hasStrayGeneralPunct(text) || isNoiseCluttered(text)) {
                    // Lingua's n-grams are unreliable on runs cluttered with stray
                    // General-Punctuation-block chars("That em-dash —(U+" scored pt-BR;
                    // "minutes copied — pure ASCII check" scored Italian(). The old floor
                    // only covered non-Latin dialects,so these Latin misdetects passed.

                    // Noise-dominated runs (emoji/digit/ASCII-punct-heavy ad-copy like
                    // "TB Storage 🚀, 💬 Comment "Gemini" and DM u/Top_Deal'" scored
                    // Italian live on-device) now floor the same way as dash-cluttered ones.

                    // Floor to the user's pinned Latin default when set(their voice),else
                    // English. Finnish/Portuguese/etc-default users keep their own voice;
                    // English-default users get clean en-US.no more random accents.

                    val latinFloor = if (dl >= 0 && isLatinDialect(dl)) dl else englishDialect

                    if (dialect != latinFloor) dialect = latinFloor
                }
            }
            out.add(Segment(text, dialect))
        }

        /** Cheap certainty:letters that exist in exactly one shipped language
         * (ñ→Spanish, ß→German,and the Polish ogonek/acute set) pin the
         * language of a run immediately——no n-gram scan. -1 = no hint. */
        private fun accentHint(text: String): Int {
            var i = text.length
            while (i > 0) {
                val cp = text.codePointBefore(i)
                when (cp) {
                    // Spanish: ñ/Ñ are unique among shipped languages
                    0x00F1, 0x00D1 ->
                        if (isLanguageEnabled("es")) return spanishDialect
                    // German: ß is unique among shipped languages
                    0x00DF ->
                        if (isLanguageEnabled("de")) return DIALECT_DE_DE
                    // Polish: Ąą Ęę Ćć Śś Źź Żż Ńń (ogonek/acutes) are Polish-only among shipped languages
                    0x0105, 0x0104, 0x0119, 0x0118, 0x0107, 0x0106,
                    0x015A, 0x015B, 0x0179, 0x017A, 0x017B, 0x017C, 0x0143, 0x0144 ->
                        if (isLanguageEnabled("pl")) return DIALECT_PL_PL
                }
                i -= Character.charCount(cp)
            }
            return -1
        }

        /** Latin-text detection:always try Lingua first;only use the default language when detection returns null.
        * key:Latin text must never fall back to Chinese/Korean/Japanese——that would be wrong.
        * if the detected language isn't in the whitelist, fall back to the default language(or English). */
        private fun latinFallback(dl: Int, fallbackDialect: Int): Int = when {
            isLatinDialect(dl) -> dl
            isLatinDialect(fallbackDialect) -> fallbackDialect
            else -> englishDialect
        }

        /** Choose a Latin dialect using short-text/context shortcuts before statistical detection. */
        private fun detectLatin(text: String, fallbackDialect: Int): Int {
            // Short runs (names, loanwords, fragments( almost always belong to
                        // the user's base language. Don't let Lingua flip the voice mid-sentence:
                        // trust the pinned default(if Latin(, else the previous segment's dialect
                        // (else English. Real phrases(>=10 chars( still get detected.


            if (text.length <	10) {
                val dl = resolveDefaultLanguage()
                if (isLatinDialect(dl)) return dl
                val accentHinted = accentHint(text)
                if (accentHinted >=  0) return accentHinted
                if (isLatinDialect(fallbackDialect)) return fallbackDialect
                // Pure-ASCII short words are English (loanwords(: they must never
                // follow a non-Latin context nor a pinned zh/ja/ko default: "release"
                // was read as Chinese behind a Chinese run (and when the zh pin leaked(.
                // Latin contexts (de/fr/... keep following the previous segment as before.
                                return englishDialect



            }
                        // Fast path: pure-ASCII runs are unmistakably English when the context is
            // English (OS UI locale is English, no non-English default pinned,and the
            // previous segment was English()). Lingua's n-gram scoring is the biggest
            // single per-segment cost on the delivery thread,so skip it for the commonest
            // case (TalkBack/English UI on en-locale devices(). Accented, mixed-locale,
            // or non-English-context runs still go through Lingua as before.

                        val dl1 = resolveDefaultLanguage()
            val localeEn = java.util.Locale.getDefault().language.startsWith("en")
            val enContext = localeEn && (dl1 < 0 || dl1 == englishDialect) &&
                fallbackDialect == englishDialect

            if (enContext) {
                // Pure-ASCII runs are obviously English——but so are runs whose only
                // non-ASCII chars are non-letters: emoji, arrows, symbols. Two live
                // misdetects prove the point:“TB Storage (rocket(, (speech-bubble( Comment “Gemini” and DM
                // u/Top_Deal'” scored Italian,and“…ready. (warning( [Coding task changes are
                // ready,but delivery needs attention](“ scored German——both were en-context
                // runs whose letters are ALL Basic Latin. Lingua's n-grams can't digest
                // the clutter,so it invents a random European language. Their letters
                // being all Basic Latin means the noise cannot belong to a different Latin
                // orthography——English is the only sane read. A non-ASCII LETTER ( ä, ß,
                // é( still means real foreign text and falls through to Lingua as before.
                var nonAsciiLetter = false
                var i = text.length
                while (i > 0) {
                    val cp = text.codePointBefore(i)
                    if (cp > 0x7F && Character.isLetter(cp)) { nonAsciiLetter = true; break }
                    i -= Character.charCount(cp)
                }
                if (!nonAsciiLetter) return englishDialect
            }
            // Unambiguous accent markers (ñ=Spanish, ß=German,and the Polish
            // ogonek/acute letters) nail the language for accented runs——skip Lingua's
            // n-gram scan entirely. Runs without these markers (or with ambiguous
            // diacritics like ä/é) are scored by Lingua exactly as before,so accuracy
            // elsewhere is untouched. The whitelist is honored:if the hint's language
            // isn't enabled, fall through to normal detection.

             val accentHinted = accentHint(text)
            if (accentHinted >= 0) return accentHinted
            // Primary path:in-RAM unigram/bigram tables from the shipped 66KB
            // asset. The 10% margin gate inside NgramScorer.detect() sends
            // ambiguous runs back here -- only then does Lingua's heavier n-gram
            // scan run. Both paths share the same LRU cache:re-announcing a seen
            // UI string short-circuits before either scan runs.
            if (text.length <= 256 && transientEnabled.get() == null) {
                synchronized(latinCache) {
                    val hit = latinCache[text]
                    if (hit != null) return hit
                }
            }
            val effEnabled = transientEnabled.get()?.let { enabledLanguages + it } ?: enabledLanguages
            val ngramId = NgramScorer.detect(text, effEnabled)

            if (ngramId >= 0) {
                val dialect = when (ngramId) {
                    0 -> englishDialect
                    1 -> DIALECT_DE_DE
                    2 -> frenchDialect
                    3 -> spanishDialect
                    4 -> DIALECT_IT_IT
                    5 -> DIALECT_PT_BR
                    6 -> DIALECT_FI_FI
                    7 -> DIALECT_PL_PL
                    else -> -1
                }
                if (dialect >=0) {
                    synchronized(latinCache) {
                        if (text.length <= 256 && transientEnabled.get() == null) {
                            if (latinCache.size >= LATIN_CACHE_MAX) latinCache.remove(latinCache.keys.first())
                            latinCache[text] = dialect
                        }
                    }
                    return dialect
                }
            }
            val ld = getLingua()
            if (ld == null) {
                // Lingua unavailable OR fewerk than two enabled Latin languages (#182(:
                // fillers must never score the text — return the sole enabled one directly.
                val dl = resolveDefaultLanguage()
                val real = getEnabledLanguageEnums()
                if (real.size == 1) return languageToDialect(real[0])
                return latinFallback(dl, fallbackDialect)
            }

            // Lingua still handles ambiguous text;its decisions land in the same
            // cache as the n-gram path above.

            try {
                val lang = ld.detectLanguageOf(text)
                // Route detections through the whitelist; disabled languages use the configured fallback.
                val code = languageToCode(lang)
                if (!isLanguageEnabled(code)) {
                    val dl = resolveDefaultLanguage()
                    return latinFallback(dl, fallbackDialect)
                }
                val detectedDialect = languageToDialect(lang)
                synchronized(latinCache) {
                    if (text.length <= 256 && transientEnabled.get() == null) {
                        if (latinCache.size >= LATIN_CACHE_MAX) latinCache.remove(latinCache.keys.first())
                        latinCache[text] = detectedDialect
                    }
                }
                return detectedDialect
            } catch (e: Throwable) {
                // detection exception -> default language(Latin only),else English
                val dl = resolveDefaultLanguage()
                return latinFallback(dl, fallbackDialect)
            }
        }

        /** Base Latin dialect from the OS UI locale - the fresh-install default until the
         *  user pins a default or a previous segment establishes the language.
         *  Returns -1 when the OS locale maps to no shipped Latin voice((zh/ja/ko/etc)). */
        private fun localeLatinDialect(): Int {
            return when (java.util.Locale.getDefault().language) {
                "en" -> englishDialect
                "de" -> DIALECT_DE_DE
                "fr" -> frenchDialect
                "es" -> spanishDialect
                "it" -> DIALECT_IT_IT
                "pl" -> DIALECT_PL_PL
                "pt" -> DIALECT_PT_BR
                "fi" -> DIALECT_FI_FI
                else -> -1
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
