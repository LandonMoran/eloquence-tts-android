package com.xw.vvtts.utils

/**
 * Text preprocessing(ported from Guangrong's TextNormalizer + DefaultPunctuationNormalizer).
 *
 * Fixes the three big issues in Apple's Eloquence CJK libraries:
 *   1. Pure ASCII digits aren't spoken -> convert to Chinese number reading(≤4 digits read as a whole;≥5 digit-by-digit)
 *   2. Symbols aren't spoken ->139 symbols mapped to Chinese readings
 *   3. Full-width/half-width normalization(so dates,times,,digits are recognized correctly)
 *
 * Guangrong's algorithm(reverse-engineered from grtts TextNormalizer.kt):
 *   DIGIT_CHARS ＝ zero,one,,two,,three,,four,,five,,six,,seven,,eight,,nine
 *   PLACE_CHARS ＝ ["",ten,,hundred,,thousand]"
 *   GROUP_UNITS ＝ [10^4, 10^8,,10^12,,...](powers of 10)
 *   short rule:≤4 digits -> NUMERIC;long rule:≥5 digits -> DIGIT
 */
class TextNormalizer {

    companion object {
        private val DIGIT_CHARS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        private val PLACE_CHARS = arrayOf("", "十", "百", "千")
        private val GROUP_UNITS = arrayOf(
            "", "万", "亿", "兆", "京", "垓", "秭", "穰", "沟", "涧",
            "正", "载", "极", "恒河沙", "阿僧祇", "那由他", "不可思议", "无量数",
        )

        /** Symbol -> Chinese-reading mapping table(Guangrong's DefaultPunctuationNormalizer;139 entries) */
        private val SYMBOL_NAMES: Map<Char, String> = buildSymbolTable()

        private fun buildSymbolTable(): Map<Char, String> {
            val m = HashMap<Char, String>()
            m['\n'] = "换行符号"
            m[' '] = "空格"
            m['!'] = "感叹号"
            m['"'] = "引号"
            m['#'] = "井号"
            m['$'] = "美元符号"
            m['%'] = "百分比符号"
            m['&'] = "和符号"
            m['\''] = "单引号"
            m['('] = "左括号"
            m[')'] = "右括号"
            m['*'] = "星号"
            m['+'] = "加号"
            m[','] = "英文逗号"
            m['-'] = "减号"
            m['.'] = "英文句点"
            m['/'] = "斜线"
            m[':'] = "冒号"
            m[';'] = "分号"
            m['<'] = "左尖括号"
            m['='] = "等号"
            m['>'] = "右尖括号"
            m['?'] = "问号"
            m['@'] = "艾特符号"
            m['['] = "左方括号"
            m['\\'] = "反斜杠"
            m[']'] = "右方括号"
            m['^'] = "脱字符号"
            m['_'] = "下划线"
            m['`'] = "重音符号"
            m['{'] = "左花括号"
            m['|'] = "竖线"
            m['}'] = "右花括号"
            m['~'] = "波浪号"

            // full-width symbols
            m['、'] = "顿号"
            m['。'] = "句号"
            m['，'] = "逗号"
            m['；'] = "分号"
            m['！'] = "感叹号"
            m['？'] = "问号"
            m['：'] = "冒号"
            m['“'] = "左双引号"
            m['”'] = "右双引号"
            m['‘'] = "左单引号"
            m['’'] = "右单引号"
            m['（'] = "左括号"
            m['）'] = "右括号"
            m['【'] = "左方括号"
            m['】'] = "右方括号"
            m['—'] = "破折号"
            m['…'] = "省略号"
            m['·'] = "间隔号"
            m['《'] = "左书名号"
            m['》'] = "右书名号"
            m['％'] = "百分号"
            m['￥'] = "人民币符号"

            // currency/unit/math symbols
            m['€'] = "欧元符号"
            m['£'] = "英镑符号"
            m['¥'] = "人民币符号"
            m['°'] = "度"
            m['℃'] = "摄氏度"
            m['±'] = "正负号"
            m['×'] = "乘号"
            m['÷'] = "除号"
            m['＝'] = "等号"
            m['≠'] = "不等号"
            m['≈'] = "约等号"
            m['√'] = "平方根符号"
            m['∞'] = "无穷大"
            m['∑'] = "求和符号"
            m['∏'] = "求积符号"
            m['→'] = "向右箭头"
            m['←'] = "向左箭头"
            m['↑'] = "向上箭头"
            m['↓'] = "向下箭头"
            m['™'] = "商标符号"
            m['®'] = "注册商标"
            m['©'] = "版权符号"
            return m
        }

        /** Normalization for Chinese(Simplified/Traditional). ORDER MATTERS:
         *  1) width-normalize first (full-width digits/punct -> ASCII) so date/number
         *     runs are recognizable, 2) number readings while '-'/'/'/':' boundaries
         *     are still intact, 3) symbol readings last.  Running symbols first would
         *     replace '-' with 减号 before hasDateTimeBoundaries ran, mangling every
         *     date (2024-03-15 -> 二千零二十四减号三减号十五). */
        fun normalizeForChinese(input: String): String {
            if (input.isEmpty()) return input
            var s = normalizeWidth(input)
            s = normalizeNumberReading(s)
            s = normalizeSymbols(s)
            return s
        }

        /** Full-width (U+FF01..U+FF5E) -> half-width ASCII; U+3000 ideographic space -> ' '. */
        fun normalizeWidth(input: String): String {
            val sb = StringBuilder(input.length)
            for (i in input.indices) {
                val c = input[i]
                when {
                    c == '\u3000' -> sb.append(' ')
                    c.code in 0xFF01..0xFF5E -> sb.append((c.code - 0xFEE0).toChar())
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /** Symbols -> Chinese readings */
        fun normalizeSymbols(input: String): String {
            val sb = StringBuilder(input.length)
            for (i in input.indices) {
                val c = input[i]
                val name = SYMBOL_NAMES[c]
                if (name != null) {
                    sb.append(name)
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        /** Number reading:recognize ASCII digit runs and convert to Chinese by length rules */
        fun normalizeNumberReading(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            val n = input.length
            while (i < n) {
                val c = input[i]
                if (isAsciiDigit(c)) {
                    // collect consecutive digits
                    val start = i
                    while (i < n && isAsciiDigit(input[i])) i++
                    val digits = input.substring(start, i)
                    // check whether part of a date/time(avoid mangling 2024-03-15 / 14:30)
                    val isDateTime = hasDateTimeBoundaries(input, start, i)
                    if (isDateTime) {
                        sb.append(digits)
                    } else {
                        sb.append(convertNumber(digits))
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        /** Convert by digit-count rules:≤4 digits read as a whole;≥5 digit-by-digit */
        fun convertNumber(digits: String): String {
            var t = digits
            while (t.length > 1 && t[0] == '0') t = t.substring(1)
            if (t.isEmpty()) return DIGIT_CHARS[0]
            if (t.length <= 4) return toChineseNumeric(t)
            return toChineseDigits(t)
        }

        /** Digit-by-digit reading:12345 -> one two three four five */
        fun toChineseDigits(digits: String): String {
            val sb = StringBuilder()
            for (i in digits.indices) {
                val d = digits[i] - '0'
                sb.append(DIGIT_CHARS[d])
            }
            return sb.toString()
        }

        /** Whole reading:1234 -> one-thousand-two-hundred-thirty-four */
        fun toChineseNumeric(digits: String): String {
            var t = digits
            while (t.length > 1 && t[0] == '0') t = t.substring(1)
            if (t.isEmpty()) return DIGIT_CHARS[0]

            // group every 4 digits(right to left)
            val groups = ArrayList<String>()
            var rest = t
            while (rest.isNotEmpty()) {
                val take = minOf(4, rest.length)
                groups.add(0, rest.substring(rest.length - take))
                rest = rest.substring(0, rest.length - take)
            }

            val sb = StringBuilder()
            var emitted = false
            for (g in groups.indices) {
                var group = groups[g]
                val unitIdx = groups.size - 1 - g  // group-unit index
                var gb = convertGroup(group)
                if (gb.isNotEmpty()) {
                    // zero-pad between groups:leading zeros between the previous non-zero group and this one
                    if (emitted && group[0] == '0') {
                        sb.append(DIGIT_CHARS[0])
                    }
                    // collapse the leading "one-ten" -> "ten"(teens read without the initial one)
                    if (gb.startsWith("一十")) {
                        gb = gb.substring(1)
                    }
                    sb.append(gb)
                    sb.append(GROUP_UNITS[unitIdx])
                    emitted = true
                }
            }
            return sb.toString()
        }

        /** 4-digit group -> Chinese(with zero handling) */
        private fun convertGroup(group: String): String {
            val padded = StringBuilder("0000")
            padded.replace(4 - group.length, 4, group)
            val g = padded.toString()
            val sb = StringBuilder()
            var hasZero = false
            var hasContent = false
            for (i in 0 until 4) {
                val d = g[i] - '0'
                val place = 3 - i
                if (d == 0) {
                    hasZero = true
                } else {
                    if (hasZero && hasContent) {
                        sb.append(DIGIT_CHARS[0])
                    }
                    sb.append(DIGIT_CHARS[d])
                    sb.append(PLACE_CHARS[place])
                    hasZero = false
                    hasContent = true
                }
            }
            return sb.toString()
        }

        /** Detect whether a digit run is part of a date/time(avoid wrongly converting 2024/03/15,,14:30) */
        private fun hasDateTimeBoundaries(input: String, start: Int, end: Int): Boolean {
            // followed by year/month/day,,'-' or '/',,dot/min/sec,,or ':'
            if (end < input.length) {
                val c = input[end]
                if (c == '年' || c == '月' || c == '日' || c == '点' || c == '分' || c == '秒'
                    || c == ':' || c == '：' || c == '/' || c == '-'
                ) {
                    return true
                }
            }
            // preceded by a digit(multi-part date like 2024-03-15)
            if (start > 0) {
                val c = input[start - 1]
                                if (c == '年' || c == '月' || c == '日' || c == '点' || c == '分' || c == '秒'
                                    || c == ':' || c == '：' || c == '/' || c == '-'
                                ) {
                                    return true
                                }
                            }
            return false
        }

        private fun isAsciiDigit(c: Char): Boolean {
            return c in '0'..'9'
        }
    }
}