package com.xw.vvtts.utils;

import android.util.Log;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
public class LanguageDetector {
    private static final String TAG = "LangDetector";

    public static class Segment {
        public final String text;
        public final int dialect;
        public Segment(String t, int d) { text = t; dialect = d; }
    }

    // ECI dialect 常量
    public static final int DIALECT_EN_US = 0x10000;
    public static final int DIALECT_EN_GB = 0x10001;
    public static final int DIALECT_ES_ES = 0x20000;
    public static final int DIALECT_ES_MX = 0x20001;
    public static final int DIALECT_FR_FR = 0x30000;
    public static final int DIALECT_FR_CA = 0x30001;
    public static final int DIALECT_DE_DE = 0x40000;
    public static final int DIALECT_IT_IT = 0x50000;
    public static final int DIALECT_ZH_CN = 0x60000;
    public static final int DIALECT_ZH_TW = 0x60001;
    public static final int DIALECT_PT_BR = 0x70000;
    public static final int DIALECT_JA_JP = 0x80000;
    public static final int DIALECT_FI_FI = 0x90000;
    public static final int DIALECT_KO_KR = 0xA0000;

    // 默认语言特殊值
    public static final int DEFAULT_UNSPECIFIED = -1;   // 不指定（数字/检测不出的文本跟随上一段）

    // 所有支持的语言代码（用于设置多选）
    public static final String[] ALL_LANG_CODES = {
        "en", "de", "fr", "es", "it", "pt", "fi", "zh", "ja", "ko"
    };
    public static final String[] ALL_LANG_NAMES = {
        "English", "Deutsch", "Français", "Español", "Italiano",
        "Português", "Suomi", "中文", "日本語", "한국어"
    };

    // 设置项
    private static volatile int chineseDialect = DIALECT_ZH_CN;
    private static volatile int englishDialect = DIALECT_EN_US;
    private static volatile int spanishDialect = DIALECT_ES_ES;
    private static volatile int frenchDialect = DIALECT_FR_FR;
    // 检测的语言白名单：默认只检测中英文。不在白名单里的语言即使被识别也 fallback 默认语言。
    private static volatile Set<String> enabledLanguages = new HashSet<>(Arrays.asList("en", "zh"));
    // 默认语言：DEFAULT_UNSPECIFIED(不指定) 或 具体 dialect。
    // 仅在"语言检测开启"时生效：数字和检测不出的文本用它；不指定则跟随上一段。
    private static volatile int defaultLanguage = DEFAULT_UNSPECIFIED;

    // 是否关闭检测（语言环境选了具体语言时设为 false）
    private static volatile boolean detectionEnabled = true;
    // 关闭检测时的固定方言
    private static volatile int fixedDialect = DIALECT_EN_US;

    public static void setChineseDialect(int dialect) { chineseDialect = dialect; }
    public static int getChineseDialect() { return chineseDialect; }

    public static void setEnglishDialect(int dialect) { englishDialect = dialect; }
    public static int getEnglishDialect() { return englishDialect; }

    public static void setSpanishDialect(int dialect) { spanishDialect = dialect; }
    public static int getSpanishDialect() { return spanishDialect; }

    public static void setFrenchDialect(int dialect) { frenchDialect = dialect; }
    public static int getFrenchDialect() { return frenchDialect; }

    public static void setEnabledLanguages(Set<String> langs) {
        enabledLanguages = (langs == null || langs.isEmpty()) ? new HashSet<>() : new HashSet<>(langs);
        // 立即重建 Lingua 检测器（语言白名单变了）
        resetLingua();
    }
    public static Set<String> getEnabledLanguages() { return enabledLanguages; }

    /** 某语言代码是否在检测白名单内 */
    public static boolean isLanguageEnabled(String code) {
        Set<String> en = enabledLanguages;
        if (en == null) return false;
        return en.contains(code);
    }

    /** 重建 Lingua（白名单变化时调用，立即生效，不需重启） */
    private static void resetLingua() {
        synchronized (LanguageDetector.class) {
            linguaDetector = null;
            linguaInitFailed = false;
            linguaPreloaded = false;
        }
        preloadLingua();
    }

    public static void setDefaultLanguage(int dialect) { defaultLanguage = dialect; }
    public static int getDefaultLanguage() { return defaultLanguage; }

    /**
     * 解析默认语言为具体 dialect。
     * 不指定 → -1（数字/检测不出的文本跟随上一段）。
     */
    public static int resolveDefaultLanguage() {
        int dl = defaultLanguage;
        if (dl >= 0) return dl;
        return -1; // 不指定
    }

    public static void setDetectionEnabled(boolean enabled) { detectionEnabled = enabled; }
    public static boolean isDetectionEnabled() { return detectionEnabled; }

    public static void setFixedDialect(int dialect) { fixedDialect = dialect; }
    public static int getFixedDialect() { return fixedDialect; }

    private static volatile com.github.pemistahl.lingua.api.LanguageDetector linguaDetector = null;
    private static volatile boolean linguaInitFailed = false;
    private static volatile boolean linguaPreloaded = false;

    /** 预加载 Lingua（APP 启动时后台调用） */
    public static void preloadLingua() {
        if (linguaPreloaded || linguaInitFailed) return;
        new Thread(() -> {
            getLingua();
            linguaPreloaded = true;
        }).start();
    }

    private static com.github.pemistahl.lingua.api.LanguageDetector getLingua() {
        if (linguaDetector != null) return linguaDetector;
        if (linguaInitFailed) return null;
        synchronized (LanguageDetector.class) {
            if (linguaDetector != null) return linguaDetector;
            if (linguaInitFailed) return null;
            try {
                long t0 = System.currentTimeMillis();
                List<Language> langs = getEnabledLanguageEnums();
                linguaDetector = LanguageDetectorBuilder
                        .fromLanguages(langs.toArray(new Language[0]))
                        .withMinimumRelativeDistance(0.0)
                        .withPreloadedLanguageModels()
                        .build();
                Log.i(TAG, "Lingua loaded in " + (System.currentTimeMillis() - t0) + "ms, langs=" + langs.size());
                return linguaDetector;
            } catch (Throwable e) {
                Log.e(TAG, "Lingua init failed", e);
                linguaInitFailed = true;
                return null;
            }
        }
    }

    private static List<Language> getEnabledLanguageEnums() {
        // Lingua 只负责拉丁语言互分。CJK（zh/ja/ko）走 Unicode 规则，不进 Lingua。
        List<Language> allLatin = new ArrayList<>();
        allLatin.add(Language.ENGLISH);
        allLatin.add(Language.GERMAN);
        allLatin.add(Language.FRENCH);
        allLatin.add(Language.SPANISH);
        allLatin.add(Language.ITALIAN);
        allLatin.add(Language.PORTUGUESE);
        allLatin.add(Language.FINNISH);

        Set<String> en = enabledLanguages;
        if (en == null) return allLatin; // 理论上不会发生，防御

        List<Language> filtered = new ArrayList<>();
        for (Language l : allLatin) {
            if (en.contains(languageToCode(l))) {
                filtered.add(l);
            }
        }
        // 至少要有一个语言，否则 Lingua build 会失败
        if (filtered.isEmpty()) filtered.add(Language.ENGLISH);
        return filtered;
    }

    private static String languageToCode(Language lang) {
        if (lang == Language.ENGLISH) return "en";
        if (lang == Language.GERMAN) return "de";
        if (lang == Language.FRENCH) return "fr";
        if (lang == Language.SPANISH) return "es";
        if (lang == Language.ITALIAN) return "it";
        if (lang == Language.PORTUGUESE) return "pt";
        if (lang == Language.FINNISH) return "fi";
        if (lang == Language.CHINESE) return "zh";
        if (lang == Language.JAPANESE) return "ja";
        if (lang == Language.KOREAN) return "ko";
        return "en";
    }

    private static int languageToDialect(Language lang) {
        if (lang == Language.ENGLISH) return englishDialect;
        if (lang == Language.GERMAN) return DIALECT_DE_DE;
        if (lang == Language.FRENCH) return frenchDialect;
        if (lang == Language.SPANISH) return spanishDialect;
        if (lang == Language.ITALIAN) return DIALECT_IT_IT;
        if (lang == Language.PORTUGUESE) return DIALECT_PT_BR;
        if (lang == Language.FINNISH) return DIALECT_FI_FI;
        if (lang == Language.CHINESE) return chineseDialect;
        if (lang == Language.JAPANESE) return DIALECT_JA_JP;
        if (lang == Language.KOREAN) return DIALECT_KO_KR;
        return englishDialect;
    }

    /**
     * 主入口：将混合文本分片。
     * 如果检测关闭，直接返回整段+固定方言。
     */
    public static List<Segment> segment(String text) {
        List<Segment> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        // 检测关闭：整段用固定方言
        if (!detectionEnabled) {
            result.add(new Segment(text, fixedDialect));
            return result;
        }

        // 第 1 遍：Unicode 切块
        StringBuilder current = new StringBuilder();
        int currentType = -1;  // 0=中文, 1=假名, 2=谚文, 3=拉丁, 4=分隔符, 5=数字
        int lastRealType = 3;  // 上一个非分隔符类型（默认拉丁）
        int lastDialect = englishDialect;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int type = classifyChar(c);

            // 分隔符（空格/标点）：永远跟随上一段语言
            if (type == 4) {
                current.append(c);
                continue;
            }

            // 数字：指定默认语言→用默认语言；不指定→跟随上一段
            if (type == 5) {
                if (currentType != 5) {
                    if (current.length() > 0) {
                        flushSegment(current, currentType, lastDialect, result);
                    }
                    currentType = 5;
                    current.setLength(0);
                }
                current.append(c);
                continue;
            }

            // 非分隔符、非数字
            if (type != currentType) {
                if (current.length() > 0) {
                    flushSegment(current, currentType, lastDialect, result);
                }
                currentType = type;
                current.setLength(0);
            }
            current.append(c);

            // 记住最近的非分隔符语言
            if (type >= 0 && type <= 3) {
                lastRealType = type;
                lastDialect = typeToDialect(type, lastDialect);
            }
        }
        if (current.length() > 0) {
            flushSegment(current, currentType, lastDialect, result);
        }

        // 合并连续同方言段
        mergeConsecutive(result);

        return result;
    }

    /**
     * 字符分类。
     * 0=中文(汉字), 1=日文假名, 2=韩文谚文, 3=拉丁, 4=分隔符
     */
    private static int classifyChar(char c) {
        // 假名
        if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) return 1;
        // 谚文
        if (c >= 0xAC00 && c <= 0xD7AF) return 2;
        // CJK 汉字（简繁不分，统一归中文）
        if (c >= 0x4E00 && c <= 0x9FFF) return 0;
        if (c >= 0x3400 && c <= 0x4DBF) return 0;
        // 拉丁字母
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return 3;
        if (c >= 0x00C0 && c <= 0x024F) return 3;
        // 空格（全角半角都算分隔符）
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return 4;
        if (c == 0x3000) return 4;  // 全角空格
        // 数字（半角和全角）→ 独立类型 5（默认语言或跟随上一段）
        if (c >= '0' && c <= '9') return 5;
        if (c >= 0xFF10 && c <= 0xFF19) return 5;
        // 标点分隔符（ASCII + CJK + 全角）
        if (isSeparator(c)) return 4;
        // 其他 ASCII 可打印（运算符等）→ 拉丁
        if (c >= 0x20 && c <= 0x7E) return 3;
        // 全角标点
        if (c >= 0xFF00 && c <= 0xFFEF) return 4;
        // CJK 标点
        if (c >= 0x3000 && c <= 0x303F) return 4;
        // 默认归拉丁
        return 3;
    }

    /** 分隔符判断：空格、标点、符号——这些跟随上一段语言 */
    private static boolean isSeparator(char c) {
        // ASCII 标点
        if (c <= 0x7F) {
            return c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':'
                || c == '-' || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == '"' || c == '\''
                || c == '/' || c == '\\' || c == '|' || c == '~'
                || c == '`' || c == '@' || c == '#' || c == '$' || c == '%'
                || c == '^' || c == '&' || c == '*' || c == '+' || c == '='
                || c == '<' || c == '>' || c == '_';
        }
        return false;
    }

    /** 字符类型 → 目标语言代码（用于白名单判断） */
    private static String typeToCode(int type) {
        switch (type) {
            case 0: return "zh";
            case 1: return "ja";
            case 2: return "ko";
            case 3: return "en"; // 拉丁占位，实际由 Lingua 决定
            default: return null;
        }
    }

    /** 字符类型 → ECI dialect（不查白名单，白名单在 flushSegment 统一处理） */
    private static int typeToDialect(int type, int fallbackDialect) {
        switch (type) {
            case 0: return chineseDialect;
            case 1: return DIALECT_JA_JP;
            case 2: return DIALECT_KO_KR;
            case 3: return fallbackDialect;
            default: return fallbackDialect;
        }
    }

    /** 判断非拉丁类型（CJK）是否检测通过：白名单含该语言才有效 */
    private static int cjkDialectOrFallback(int type, int fallbackDialect) {
        String code = typeToCode(type);
        if (code != null && isLanguageEnabled(code)) {
            return typeToDialect(type, fallbackDialect);
        }
        // 不在白名单：fallback 默认语言（指定时），否则英文（不跨到上一段 CJK）
        int dl = resolveDefaultLanguage();
        return (dl >= 0) ? dl : englishDialect;
    }

    /** 输出当前块为 Segment，拉丁块用 Lingua 精修 */
    private static void flushSegment(StringBuilder sb, int type, int fallbackDialect,
                                     List<Segment> out) {
        if (sb.length() == 0) return;
        String text = sb.toString();
        sb.setLength(0);

        int dialect;
        if (type == 3) {
            // 拉丁块：Lingua 检测（内部再查白名单）
            dialect = detectLatin(text, fallbackDialect);
        } else if (type == 4) {
            // 空格标点：跟随上一段
            dialect = fallbackDialect;
        } else if (type == 5) {
            // 数字：默认语言指定→用默认；不指定→跟随上一段
            int dl = resolveDefaultLanguage();
            dialect = (dl >= 0) ? dl : fallbackDialect;
        } else {
            // 中文/日文/韩文：查白名单，不在白名单则 fallback 默认语言
            dialect = cjkDialectOrFallback(type, fallbackDialect);
        }
        out.add(new Segment(text, dialect));
    }

    /** 拉丁文本检测：永远先 Lingua 检测，检测不出（null）才用默认语言。
     * 关键：拉丁文本绝不能 fallback 到中文/韩文/日文——那是错误的。
     * 检测出的语言若不在白名单，fallback 默认语言（否则英文）。 */
    private static int detectLatin(String text, int fallbackDialect) {
        com.github.pemistahl.lingua.api.LanguageDetector ld = getLingua();
        if (ld == null) {
            // Lingua 不可用：默认拉丁语言（默认语言若拉丁，否则英文）
            int dl = resolveDefaultLanguage();
            return isLatinDialect(dl) ? dl : englishDialect;
        }

        try {
            Language lang = ld.detectLanguageOf(text);
            if (lang == null) {
                // 检测不出 → 默认语言（仅拉丁），否则英文
                int dl = resolveDefaultLanguage();
                return isLatinDialect(dl) ? dl : englishDialect;
            }
            // 检测出的语言查白名单：不在白名单 → fallback 默认语言
            String code = languageToCode(lang);
            if (!isLanguageEnabled(code)) {
                int dl = resolveDefaultLanguage();
                return isLatinDialect(dl) ? dl : englishDialect;
            }
            return languageToDialect(lang);
        } catch (Throwable e) {
            // 检测异常 → 默认语言（仅拉丁），否则英文
            int dl = resolveDefaultLanguage();
            return isLatinDialect(dl) ? dl : englishDialect;
        }
    }

    /** 判断 dialect 是否是拉丁字母语言（英/德/法/西/意/葡/芬） */
    private static boolean isLatinDialect(int dialect) {
        return dialect == DIALECT_EN_US || dialect == DIALECT_EN_GB
            || dialect == DIALECT_DE_DE
            || dialect == DIALECT_FR_FR || dialect == DIALECT_FR_CA
            || dialect == DIALECT_ES_ES || dialect == DIALECT_ES_MX
            || dialect == DIALECT_IT_IT
            || dialect == DIALECT_PT_BR
            || dialect == DIALECT_FI_FI;
    }

    private static void mergeConsecutive(List<Segment> segments) {
        for (int i = segments.size() - 1; i > 0; i--) {
            Segment cur = segments.get(i);
            Segment prev = segments.get(i - 1);
            if (cur.dialect == prev.dialect) {
                segments.set(i - 1, new Segment(prev.text + cur.text, prev.dialect));
                segments.remove(i);
            }
        }
    }
}