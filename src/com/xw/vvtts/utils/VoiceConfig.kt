package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences

/** 发音配置（UI 语速/音调/音量 + 语言选择）。 */
class VoiceConfig(context: Context) {
    /** 支持的语言定义（苹果 Kona 全语言表）。 */
    class Lang(
        /** BCP-47 */
        val code: String,
        /** 中文名 */
        val name: String,
        /** 苹果 Kona dialect 编号 */
        val konaDialect: Int,
        /** ECI dialect（部分为推测值，接入库时实测修正） */
        val eciDialect: Long,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val voice: String
        get() = prefs.getString(KEY_VOICE, "zh-CN")!!
    val rate: Int
        get() = prefs.getInt(KEY_RATE, 100)
    val pitch: Int
        get() = prefs.getInt(KEY_PITCH, 50)
    val volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100)
    /** DSP mode: 0 = standard (raw engine output), 1 = enhanced (de-hiss + limiter). Default: standard. */
    val dspMode: Int
        get() = prefs.getInt(KEY_DSP_MODE, 0)
    val isAutoDetect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT, true)

    fun setVoice(v: String) { prefs.edit().putString(KEY_VOICE, v).commit() }
    fun setRate(r: Int) { prefs.edit().putInt(KEY_RATE, r).commit() }
    fun setPitch(p: Int) { prefs.edit().putInt(KEY_PITCH, p).commit() }
    fun setVolume(v: Int) { prefs.edit().putInt(KEY_VOLUME, v).commit() }
    fun setDspMode(m: Int) { prefs.edit().putInt(KEY_DSP_MODE, m).commit() }
    fun setAutoDetect(b: Boolean) { prefs.edit().putBoolean(KEY_AUTO_DETECT, b).commit() }

    companion object {
        private const val PREFS = "vvtts_prefs"
        const val KEY_VOICE = "voice"
        const val KEY_RATE = "rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val KEY_DSP_MODE = "dsp_mode"
        const val KEY_AUTO_DETECT = "auto_detect"

        // eciDialect 实测值：来自 openevv 各语言模块注册常量
        // （lang/*/eci_ini_*.c 的 *_eci_library_lang），与 build_native.sh LANGS 一一对应。
        // 0 = 本构建未链接该语言（pt/fi/ko/zh-TW 无模块）。
        val LANGS = arrayOf(
            Lang("en-US", "英语（美式）", 0, 0x10000L),
            Lang("en-GB", "英语（英式）", 1, 0x10001L),
            Lang("es-ES", "西班牙语（西班牙）", 2, 0x20000L),
            Lang("es-MX", "西班牙语（墨西哥）", 3, 0x20002L), // esmx 模块
            Lang("fr-FR", "法语（法国）", 4, 0x30000L),
            Lang("fr-CA", "法语（加拿大）", 5, 0x30001L),
            Lang("de-DE", "德语", 6, 0x40000L),
            Lang("it-IT", "意大利语", 7, 0x50000L),
            Lang("pt-BR", "葡萄牙语（巴西）", 8, 0L),       // 未链接
            Lang("fi-FI", "芬兰语", 9, 0L),                 // 未链接
            Lang("ja-JP", "日语", 10, 0x80000L),
            Lang("ko-KR", "韩语", 11, 0L),                  // 未链接
            Lang("zh-CN", "中文（普通话）", 12, 0x60000L),  // 未链接本构建
            Lang("zh-TW", "中文（台湾）", 13, 0x60001L),    // 未链接
        )

        /** CF 语言库代码（Code Factory 10 语言）与 BCP-47 对应 */
        fun cfCodeFor(bcp47: String?): String? {
            if (bcp47 == null) return null
            return when (bcp47) {
                "de-DE" -> "deu"
                "en-US" -> "enu"
                "en-GB" -> "eng"
                "es-ES" -> "esn"  // Castilian Spanish
                "es-MX" -> "esm"  // Latin American / Mexican Spanish
                "fr-FR" -> "fra"
                "fr-CA" -> "frc"
                "it-IT" -> "ita"
                "pt-BR" -> "ptb"
                "fi-FI" -> "fin"
                else -> null      // zh/ja/ko 无 CF 库 → 广荣链路
            }
        }

        fun findLang(code: String?): Lang {
            if (code == null) return LANGS[12]
            for (l in LANGS) if (l.code == code) return l
            return LANGS[12]
        }

        fun findLangByKona(kona: Int): Lang {
            for (l in LANGS) if (l.konaDialect == kona) return l
            return LANGS[12]
        }
    }
}