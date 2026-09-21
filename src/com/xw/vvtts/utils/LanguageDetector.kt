package com.xw.vvtts.utils

import android.util.Log
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder

/**
 * 多语言检测 + 文本分片器。
 *
 * 第 1 层：Unicode 规则（O(n) 单遍扫描，零延迟，100% 精准）
 *   假名 → 日文、谚文 → 韩文、汉字 → 中文、拉丁 → 拉丁块
 *   空格/标点/分隔符 → 永远跟随上一段语言（无论默认语言怎么设）
 *
 * 第 2 层：Lingua 统计（拉丁 10 语言互分，短文本回落默认语言）
 *
 * 简繁不在此判断——由用户设置中文方言（简体 zh-CN / 台湾 zh-TW）。
 */
class LanguageDetector {

    class Segment(
        val text: String,
        val dialect: Int,
    )

    companion object {
        private const val TAG = "LangDetector"

        // ECI dialect 常量
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

        // 所有支持的语言代码（用于设置多选）
        // Only languages actually linked in this build (see build_native.sh LANGS)。
        val ALL_LANG_CODES = arrayOf("en", "de", "fr", "es", "it", "ja", "pl", "pt", "fi")
        val ALL_LANG_NAMES = arrayOf(
            "English", "German", "French", "Spanish", "Italian", "Japanese", "Polish", "Portuguese", "Finnish",
        )

        // 设置项
        @Volatile private var chineseDialect = DIALECT_ZH_CN
        @Volatile private var englishDialect = DIALECT_EN_US
        @Volatile private var spanishDialect = DIALECT_ES_ES
        @Volatile private var frenchDialect = DIALECT_FR_FR
        // Detection whitelist: by default only English + Japanese
        // (zh is not linked in this build; ja-JP is the only shipped CJK dialect)。
        // Languages outside the whitelist fall back to the default language when detected。
        @Volatile private var enabledLanguages: Set<String> = HashSet(listOf("en", "ja"))
        // 默认语言：DEFAULT_UNSPECIFIED(不指定) 或 具体 dialect。
        // 仅在"语言检测开启"时生效：数字和检测不出的文本用它；不指定则跟随上一段。
        @Volatile private var defaultLanguage = DEFAULT_UNSPECIFIED

        // 是否关闭检测（语言环境选了具体语言时设为 false）
        @Volatile private var detectionEnabled = true
        // 关闭检测时的固定方言
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
            // 立即重建 Lingua 检测器（语言白名单变了）
            resetLingua()
        }

        fun getEnabledLanguages(): Set<String> = enabledLanguages

        /** 某语言代码是否在检测白名单内 */
        fun isLanguageEnabled(code: String): Boolean {
            val en = enabledLanguages
            if (en == null) return false
            return en.contains(code)
        }

        /** 重建 Lingua（白名单变化时调用，立即生效，不需重启） */
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
         * 解析默认语言为具体 dialect。
         * 不指定 → -1（数字/检测不出的文本跟随上一段）。
         */
        fun resolveDefaultLanguage(): Int {
            val dl = defaultLanguage
            if (dl >= 0) return dl
            return -1 // 不指定
        }

        fun setDetectionEnabled(enabled: Boolean) { detectionEnabled = enabled }
        fun isDetectionEnabled() = detectionEnabled

        fun setFixedDialect(dialect: Int) { fixedDialect = dialect }
        fun getFixedDialect() = fixedDialect

        /** 预加载 Lingua（APP 启动时后台调用） */
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
            // rules and never go through Lingua。
            val allLatin = ArrayList<Language>()
            allLatin.add(Language.ENGLISH)
            allLatin.add(Language.GERMAN)
            allLatin.add(Language.FRENCH)
            allLatin.add(Language.SPANISH)
            allLatin.add(Language.ITALIAN)
            allLatin.add(Language.POLISH)

            val en = enabledLanguages
            if (en == null) return allLatin // 理论上不会发生，防御

            val filtered = ArrayList<Language>()
            for (l in allLatin) {
                if (en.contains(languageToCode(l))) {
                    filtered.add(l)
                }
            }
            // 至少要有一个语言，否则 Lingua build 会失败
            if (filtered.isEmpty()) filtered.add(Language.ENGLISH)
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
         * 主入口：将混合文本分片。
         * 如果检测关闭，直接返回整段+固定方言。
         */
        fun segment(text: String?): List<Segment> {
            val result = ArrayList<Segment>()
            if (text == null || text.isEmpty()) return result

            // 检测关闭：整段用固定方言
            if (!detectionEnabled) {
                result.add(Segment(text, fixedDialect))
                return result
            }

            // 第 1 遍：Unicode 切块
            var current = StringBuilder()
            var currentType = -1  // 0=中文, 1=假名, 2=谚文, 3=拉丁, 4=分隔符, 5=数字
            var lastRealType = 3  // 上一个非分隔符类型（默认拉丁）
            var lastDialect = englishDialect

            for (i in text.indices) {
                val c = text[i]
                val type = classifyChar(c)

                // 分隔符（空格/标点）：永远跟随上一段语言
                if (type == 4) {
                    current.append(c)
                    continue
                }

                // 数字：指定默认语言→用默认语言；不指定→跟随上一段
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

                // 非分隔符、非数字
                if (type != currentType) {
                    if (current.length > 0) {
                        flushSegment(current, currentType, lastDialect, result)
                    }
                    currentType = type
                    current = StringBuilder()
                }
                current.append(c)

                // 记住最近的非分隔符语言
                if (type in 0..3) {
                    lastRealType = type
                    lastDialect = typeToDialect(type, lastDialect)
                }
            }
            if (current.length > 0) {
                flushSegment(current, currentType, lastDialect, result)
            }

            // 合并连续同方言段
            mergeConsecutive(result)

            return result
        }

        /**
         * 字符分类。
         * 0=中文(汉字), 1=日文假名, 2=韩文谚文, 3=拉丁, 4=分隔符
         */
        private fun classifyChar(c: Char): Int {
            // 假名
            if (c.code in 0x3040..0x309F || c.code in 0x30A0..0x30FF) return 1
            // 谚文
            if (c.code in 0xAC00..0xD7AF) return 2
            // CJK 汉字（简繁不分，统一归中文）
            if (c.code in 0x4E00..0x9FFF) return 0
            if (c.code in 0x3400..0x4DBF) return 0
            // 拉丁字母
            if (c in 'A'..'Z' || c in 'a'..'z') return 3
            if (c.code in 0x00C0..0x024F) return 3
            // 空格（全角半角都算分隔符）
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return 4
            if (c.code == 0x3000) return 4  // 全角空格
            // 数字（半角和全角）→ 独立类型 5（默认语言或跟随上一段）
            if (c in '0'..'9') return 5
            if (c.code in 0xFF10..0xFF19) return 5
            // 标点分隔符（ASCII + CJK + 全角）
            if (isSeparator(c)) return 4
            // 其他 ASCII 可打印（运算符等）→ 拉丁
            if (c.code in 0x20..0x7E) return 3
            // 全角标点
            if (c.code in 0xFF00..0xFFEF) return 4
            // CJK 标点
            if (c.code in 0x3000..0x303F) return 4
            // 默认归拉丁
            return 3
        }

        /** 分隔符判断：空格、标点、符号——这些跟随上一段语言 */
        private fun isSeparator(c: Char): Boolean {
            // ASCII 标点
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

        /** 字符类型 → 目标语言代码（用于白名单判断） */
        private fun typeToCode(type: Int): String? {
            return when (type) {
                0 -> "zh"
                1 -> "ja"
                2 -> "ko"
                3 -> "en" // 拉丁占位，实际由 Lingua 决定
                else -> null
            }
        }

        /** 字符类型 → ECI dialect（不查白名单，白名单在 flushSegment 统一处理） */
        private fun typeToDialect(type: Int, fallbackDialect: Int): Int {
            return when (type) {
                0 -> chineseDialect
                1 -> DIALECT_JA_JP
                2 -> DIALECT_KO_KR
                3 -> fallbackDialect
                else -> fallbackDialect
            }
        }

        /** 判断非拉丁类型（CJK）是否检测通过：白名单含该语言才有效 */
        private fun cjkDialectOrFallback(type: Int, fallbackDialect: Int): Int {
            val code = typeToCode(type)
            if (code != null && isLanguageEnabled(code)) {
                return typeToDialect(type, fallbackDialect)
            }
            // 不在白名单：fallback 默认语言（指定时），否则英文（不跨到上一段 CJK）
            val dl = resolveDefaultLanguage()
            return if (dl >= 0) dl else englishDialect
        }

        /** 输出当前块为 Segment，拉丁块用 Lingua 精修 */
        private fun flushSegment(sb: StringBuilder, type: Int, fallbackDialect: Int, out: MutableList<Segment>) {
            if (sb.length == 0) return
            val text = sb.toString()
            sb.setLength(0)

            val dialect: Int
            if (type == 3) {
                // 拉丁块：Lingua 检测（内部再查白名单）
                dialect = detectLatin(text, fallbackDialect)
            } else if (type == 4) {
                // 空格标点：跟随上一段
                dialect = fallbackDialect
            } else if (type == 5) {
                // 数字：默认语言指定→用默认；不指定→跟随上一段
                val dl = resolveDefaultLanguage()
                dialect = if (dl >= 0) dl else fallbackDialect
            } else {
                // 中文/日文/韩文：查白名单，不在白名单则 fallback 默认语言
                dialect = cjkDialectOrFallback(type, fallbackDialect)
            }
            out.add(Segment(text, dialect))
        }

        /** 拉丁文本检测：永远先 Lingua 检测，检测不出（null）才用默认语言。
         * 关键：拉丁文本绝不能 fallback 到中文/韩文/日文——那是错误的。
         * 检测出的语言若不在白名单，fallback 默认语言（否则英文）。 */
        private fun detectLatin(text: String, fallbackDialect: Int): Int {
            val ld = getLingua()
            if (ld == null) {
                // Lingua 不可用：默认拉丁语言（默认语言若拉丁，否则英文）
                val dl = resolveDefaultLanguage()
                return if (isLatinDialect(dl)) dl else englishDialect
            }

            try {
                val lang = ld.detectLanguageOf(text)
                if (lang == null) {
                    // 检测不出 → 默认语言（仅拉丁），否则英文
                    val dl = resolveDefaultLanguage()
                    return if (isLatinDialect(dl)) dl else englishDialect
                }
                // 检测出的语言查白名单：不在白名单 → fallback 默认语言
                val code = languageToCode(lang)
                if (!isLanguageEnabled(code)) {
                    val dl = resolveDefaultLanguage()
                    return if (isLatinDialect(dl)) dl else englishDialect
                }
                return languageToDialect(lang)
            } catch (e: Throwable) {
                // 检测异常 → 默认语言（仅拉丁），否则英文
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