package com.xw.vvtts.utils

/**
 * 文本预处理（移植自广荣 TextNormalizer + DefaultPunctuationNormalizer）。
 *
 * 解决苹果 Eloquence CJK 库的三大问题：
 *   1. 纯 ASCII 数字不读 → 转中文读法（≤4位整体读，≥5位逐位读）
 *   2. 符号不读 → 139 个符号映射到中文读法
 *   3. 全角/半角宽度归一化（让日期、时间、数字能被正确识别）
 *
 * 广荣算法（已逆向 grtts TextNormalizer.kt）：
 *   DIGIT_CHARS = 零一二三四五六七八九
 *   PLACE_CHARS = ["", 十, 百, 千]
 *   GROUP_UNITS = ["", 万, 亿, 兆, 京, 垓, 秭, 穰, 沟, 涧, 正, 载, 极, ...]
 *   short 规则 ≤4 位 → NUMERIC；long 规则 ≥5 位 → DIGIT
 */
class TextNormalizer {

    companion object {
        private val DIGIT_CHARS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        private val PLACE_CHARS = arrayOf("", "十", "百", "千")
        private val GROUP_UNITS = arrayOf(
            "", "万", "亿", "兆", "京", "垓", "秭", "穰", "沟", "涧",
            "正", "载", "极", "恒河沙", "阿僧祇", "那由他", "不可思议", "无量数",
        )

        /** 符号 → 中文读法映射表（广荣 DefaultPunctuationNormalizer，139 项） */
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

            // 全角符号
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

            // 货币/单位/数学符号
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

        /** 给中文（简/繁）用的规范化：符号读法 + 数字读法 + 宽度归一 */
        fun normalizeForChinese(input: String): String {
            if (input.isEmpty()) return input
            var s = normalizeSymbols(input)
            s = normalizeNumberReading(s)
            return s
        }

        /** 符号 → 中文读法 */
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

        /** 数字读法：识别 ASCII 数字串，按长度规则转中文 */
        fun normalizeNumberReading(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            val n = input.length
            while (i < n) {
                val c = input[i]
                if (isAsciiDigit(c)) {
                    // 收集连续数字
                    val start = i
                    while (i < n && isAsciiDigit(input[i])) i++
                    val digits = input.substring(start, i)
                    // 检查是否是日期/时间的一部分（避免误伤 2024-03-15 / 14:30）
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

        /** 按 digit 数规则转换：≤4 位整体读，≥5 位逐位读 */
        fun convertNumber(digits: String): String {
            var t = digits
            while (t.length > 1 && t[0] == '0') t = t.substring(1)
            if (t.isEmpty()) return DIGIT_CHARS[0]
            if (t.length <= 4) return toChineseNumeric(t)
            return toChineseDigits(t)
        }

        /** 逐位读：12345 → 一二三四五 */
        fun toChineseDigits(digits: String): String {
            val sb = StringBuilder()
            for (i in digits.indices) {
                val d = digits[i] - '0'
                sb.append(DIGIT_CHARS[d])
            }
            return sb.toString()
        }

        /** 整体读：1234 → 一千二百三十四 */
        fun toChineseNumeric(digits: String): String {
            var t = digits
            while (t.length > 1 && t[0] == '0') t = t.substring(1)
            if (t.isEmpty()) return DIGIT_CHARS[0]

            // 每 4 位分组（从右往左）
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
                val unitIdx = groups.size - 1 - g  // 组单位索引
                var gb = convertGroup(group)
                if (gb.isNotEmpty()) {
                    // 组间补零：前一非零组和当前组之间有零开头
                    if (emitted && group[0] == '0') {
                        sb.append(DIGIT_CHARS[0])
                    }
                    // 处理 "一十" → "十"
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

        /** 4 位数字组转中文（带零处理） */
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

        /** 检测数字串是否是日期/时间的一部分（避免 2024/03/15、14:30 被误转） */
        private fun hasDateTimeBoundaries(input: String, start: Int, end: Int): Boolean {
            // 后面跟 年/月/日 或 - / 或 点/分/秒 或 :
            if (end < input.length) {
                val c = input[end]
                if (c == '年' || c == '月' || c == '日' || c == '点' || c == '分' || c == '秒'
                    || c == ':' || c == '：' || c == '/' || c == '-'
                ) {
                    return true
                }
            }
            // 前面是数字（多段日期如 2024-03-15）
            if (start > 0) {
                val c = input[start - 1]
                if (c == '/' || c == '-' || c == ':' || c == '：' || c == '年' || c == '月' || c == '点') {
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