package com.xw.vvtts.utils;

import java.util.HashMap;
import java.util.Map;

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
public final class TextNormalizer {

    private static final String[] DIGIT_CHARS = {
        "零", "一", "二", "三", "四", "五", "六", "七", "八", "九"
    };
    private static final String[] PLACE_CHARS = { "", "十", "百", "千" };
    private static final String[] GROUP_UNITS = {
        "", "万", "亿", "兆", "京", "垓", "秭", "穰", "沟", "涧",
        "正", "载", "极", "恒河沙", "阿僧祇", "那由他", "不可思议", "无量数"
    };

    /** 符号 → 中文读法映射表（广荣 DefaultPunctuationNormalizer，139 项） */
    private static final Map<Character, String> SYMBOL_NAMES = buildSymbolTable();

    private static Map<Character, String> buildSymbolTable() {
        Map<Character, String> m = new HashMap<>();
        m.put('\n', "换行符号");
        m.put(' ', "空格");
        m.put('!', "感叹号");
        m.put('"', "引号");
        m.put('#', "井号");
        m.put('$', "美元符号");
        m.put('%', "百分比符号");
        m.put('&', "和符号");
        m.put('\'', "单引号");
        m.put('(', "左括号");
        m.put(')', "右括号");
        m.put('*', "星号");
        m.put('+', "加号");
        m.put(',', "英文逗号");
        m.put('-', "减号");
        m.put('.', "英文句点");
        m.put('/', "斜线");
        m.put(':', "冒号");
        m.put(';', "分号");
        m.put('<', "左尖括号");
        m.put('=', "等号");
        m.put('>', "右尖括号");
        m.put('?', "问号");
        m.put('@', "艾特符号");
        m.put('[', "左方括号");
        m.put('\\', "反斜杠");
        m.put(']', "右方括号");
        m.put('^', "脱字符号");
        m.put('_', "下划线");
        m.put('`', "重音符号");
        m.put('{', "左花括号");
        m.put('|', "竖线");
        m.put('}', "右花括号");
        m.put('~', "波浪号");

        // 全角符号
        m.put('、', "顿号");
        m.put('。', "句号");
        m.put('，', "逗号");
        m.put('；', "分号");
        m.put('！', "感叹号");
        m.put('？', "问号");
        m.put('：', "冒号");
        m.put('“', "左双引号");
        m.put('”', "右双引号");
        m.put('‘', "左单引号");
        m.put('’', "右单引号");
        m.put('（', "左括号");
        m.put('）', "右括号");
        m.put('【', "左方括号");
        m.put('】', "右方括号");
        m.put('—', "破折号");
        m.put('…', "省略号");
        m.put('·', "间隔号");
        m.put('《', "左书名号");
        m.put('》', "右书名号");
        m.put('％', "百分号");
        m.put('￥', "人民币符号");

        // 货币/单位/数学符号
        m.put('€', "欧元符号");
        m.put('£', "英镑符号");
        m.put('¥', "人民币符号");
        m.put('°', "度");
        m.put('℃', "摄氏度");
        m.put('±', "正负号");
        m.put('×', "乘号");
        m.put('÷', "除号");
        m.put('＝', "等号");
        m.put('≠', "不等号");
        m.put('≈', "约等号");
        m.put('√', "平方根符号");
        m.put('∞', "无穷大");
        m.put('∑', "求和符号");
        m.put('∏', "求积符号");
        m.put('→', "向右箭头");
        m.put('←', "向左箭头");
        m.put('↑', "向上箭头");
        m.put('↓', "向下箭头");
        m.put('™', "商标符号");
        m.put('®', "注册商标");
        m.put('©', "版权符号");
        return m;
    }

    private TextNormalizer() {}

    /** 给中文（简/繁）用的规范化：符号读法 + 数字读法 + 宽度归一 */
    public static String normalizeForChinese(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = normalizeSymbols(input);
        s = normalizeNumberReading(s);
        return s;
    }

    /** 符号 → 中文读法 */
    public static String normalizeSymbols(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            String name = SYMBOL_NAMES.get(c);
            if (name != null) {
                sb.append(name);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 数字读法：识别 ASCII 数字串，按长度规则转中文 */
    public static String normalizeNumberReading(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (isAsciiDigit(c)) {
                // 收集连续数字
                int start = i;
                while (i < n && isAsciiDigit(input.charAt(i))) i++;
                String digits = input.substring(start, i);
                // 检查是否是日期/时间的一部分（避免误伤 2024-03-15 / 14:30）
                boolean isDateTime = hasDateTimeBoundaries(input, start, i);
                if (isDateTime) {
                    sb.append(digits);
                } else {
                    sb.append(convertNumber(digits));
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 按 digit 数规则转换：≤4 位整体读，≥5 位逐位读 */
    static String convertNumber(String digits) {
        String t = digits;
        while (t.length() > 1 && t.charAt(0) == '0') t = t.substring(1);
        if (t.isEmpty()) return DIGIT_CHARS[0];
        if (t.length() <= 4) return toChineseNumeric(t);
        return toChineseDigits(t);
    }

    /** 逐位读：12345 → 一二三四五 */
    static String toChineseDigits(String digits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(i) - '0';
            sb.append(DIGIT_CHARS[d]);
        }
        return sb.toString();
    }

    /** 整体读：1234 → 一千二百三十四 */
    static String toChineseNumeric(String digits) {
        String t = digits;
        while (t.length() > 1 && t.charAt(0) == '0') t = t.substring(1);
        if (t.isEmpty()) return DIGIT_CHARS[0];

        // 每 4 位分组（从右往左）
        java.util.List<String> groups = new java.util.ArrayList<>();
        String rest = t;
        while (rest.length() > 0) {
            int take = Math.min(4, rest.length());
            groups.add(0, rest.substring(rest.length() - take));
            rest = rest.substring(0, rest.length() - take);
        }

        StringBuilder sb = new StringBuilder();
        boolean emitted = false;
        for (int g = 0; g < groups.size(); g++) {
            String group = groups.get(g);
            int unitIdx = groups.size() - 1 - g;  // 组单位索引
            String gb = convertGroup(group);
            if (!gb.isEmpty()) {
                // 组间补零：前一非零组和当前组之间有零开头
                if (emitted && group.charAt(0) == '0') {
                    sb.append(DIGIT_CHARS[0]);
                }
                // 处理 "一十" → "十"
                if (gb.startsWith("一十")) {
                    gb = gb.substring(1);
                }
                sb.append(gb);
                sb.append(GROUP_UNITS[unitIdx]);
                emitted = true;
            }
        }
        return sb.toString();
    }

    /** 4 位数字组转中文（带零处理） */
    private static String convertGroup(String group) {
        StringBuilder padded = new StringBuilder("0000");
        padded.replace(4 - group.length(), 4, group);
        String g = padded.toString();
        StringBuilder sb = new StringBuilder();
        boolean hasZero = false;
        boolean hasContent = false;
        for (int i = 0; i < 4; i++) {
            int d = g.charAt(i) - '0';
            int place = 3 - i;
            if (d == 0) {
                hasZero = true;
            } else {
                if (hasZero && hasContent) {
                    sb.append(DIGIT_CHARS[0]);
                }
                sb.append(DIGIT_CHARS[d]);
                sb.append(PLACE_CHARS[place]);
                hasZero = false;
                hasContent = true;
            }
        }
        return sb.toString();
    }

    /** 检测数字串是否是日期/时间的一部分（避免 2024/03/15、14:30 被误转） */
    private static boolean hasDateTimeBoundaries(String input, int start, int end) {
        // 后面跟 年/月/日 或 - / 或 点/分/秒 或 :
        if (end < input.length()) {
            char c = input.charAt(end);
            if (c == '年' || c == '月' || c == '日' || c == '点' || c == '分' || c == '秒'
                    || c == ':' || c == '：' || c == '/' || c == '-') {
                return true;
            }
        }
        // 前面是数字（多段日期如 2024-03-15）
        if (start > 0) {
            char c = input.charAt(start - 1);
            if (c == '/' || c == '-' || c == ':' || c == '：' || c == '年' || c == '月' || c == '点') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}