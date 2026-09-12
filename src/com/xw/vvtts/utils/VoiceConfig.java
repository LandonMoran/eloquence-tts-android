package com.xw.vvtts.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class VoiceConfig {
    private static final String PREFS = "vvtts_prefs";
    public static final String KEY_VOICE = "voice";
    public static final String KEY_RATE = "rate";
    public static final String KEY_PITCH = "pitch";
    public static final String KEY_VOLUME = "volume";
    public static final String KEY_AUTO_DETECT = "auto_detect";

    /** 支持的语言定义（苹果 Kona 全语言表） */
    public static class Lang {
        public final String code;    // BCP-47
        public final String name;    // 中文名
        public final int konaDialect;// 苹果 Kona dialect 编号
        public final long eciDialect;// ECI dialect（部分为推测值，接入库时实测修正）
        public Lang(String code, String name, int konaDialect, long eciDialect) {
            this.code = code; this.name = name;
            this.konaDialect = konaDialect; this.eciDialect = eciDialect;
        }
    }

    // 已知锚点：en-US kona0=0x10000，zh-CN kona12=0x60000（广荣实测）
    // 其余为对照 Eloquence 官方 dialect 家族的推测值，接入对应语言库时逐一验证
    public static final Lang[] LANGS = {
        new Lang("en-US", "英语（美式）", 0,  0x10000),
        new Lang("en-GB", "英语（英式）", 1,  0x20000),
        new Lang("es-ES", "西班牙语（西班牙）", 2, 0x30000),
        new Lang("es-MX", "西班牙语（墨西哥）", 3, 0x30000),
        new Lang("fr-FR", "法语（法国）", 4,  0x0C0000),
        new Lang("fr-CA", "法语（加拿大）", 5, 0x0C0000),
        new Lang("de-DE", "德语", 6,  0x70000),
        new Lang("it-IT", "意大利语", 7, 0x100000),
        new Lang("pt-BR", "葡萄牙语（巴西）", 8, 0x160000),
        new Lang("fi-FI", "芬兰语", 9, 0x0B0000),
        new Lang("ja-JP", "日语", 10, 0x110000),
        new Lang("ko-KR", "韩语", 11, 0x120000),
        new Lang("zh-CN", "中文（普通话）", 12, 0x60000),
        new Lang("zh-TW", "中文（台湾）", 13, 0x61000),
    };

    // CF 语言库代码（Code Factory 10 语言）与 BCP-47 对应
    public static String cfCodeFor(String bcp47) {
        if (bcp47 == null) return null;
        switch (bcp47) {
            case "de-DE": return "deu";
            case "en-US": return "enu";
            case "en-GB": return "eng";
            case "es-ES": return "esn";  // Castilian Spanish
            case "es-MX": return "esm";  // Latin American / Mexican Spanish
            case "fr-FR": return "fra";
            case "fr-CA": return "frc";
            case "it-IT": return "ita";
            case "pt-BR": return "ptb";
            case "fi-FI": return "fin";
            default: return null;      // zh/ja/ko 无 CF 库 → 广荣链路
        }
    }

    public static Lang findLang(String code) {
        if (code == null) return LANGS[12];
        for (Lang l : LANGS) if (l.code.equals(code)) return l;
        return LANGS[12];
    }

    public static Lang findLangByKona(int kona) {
        for (Lang l : LANGS) if (l.konaDialect == kona) return l;
        return LANGS[12];
    }

    private final SharedPreferences prefs;

    public VoiceConfig(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getVoice() { return prefs.getString(KEY_VOICE, "zh-CN"); }
    public int getRate() { return prefs.getInt(KEY_RATE, 100); }
    public int getPitch() { return prefs.getInt(KEY_PITCH, 50); }
    public int getVolume() { return prefs.getInt(KEY_VOLUME, 100); }
    public boolean isAutoDetect() { return prefs.getBoolean(KEY_AUTO_DETECT, true); }

    public void setVoice(String v) { prefs.edit().putString(KEY_VOICE, v).commit(); }
    public void setRate(int r) { prefs.edit().putInt(KEY_RATE, r).commit(); }
    public void setPitch(int p) { prefs.edit().putInt(KEY_PITCH, p).commit(); }
    public void setVolume(int v) { prefs.edit().putInt(KEY_VOLUME, v).commit(); }
    public void setAutoDetect(boolean b) { prefs.edit().putBoolean(KEY_AUTO_DETECT, b).commit(); }
}