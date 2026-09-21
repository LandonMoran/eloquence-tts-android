package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences

/** Voice settings (UI rate/pitch/volume + language selection).) */
class VoiceConfig(context: Context) {
    /** Supported language definitions (Apple Kona full table).)*/
    class Lang(
        /** BCP-47 */
        val code: String,
        /** Display name */
        val name: String,
        /** Apple Kona dialect number */
        val konaDialect: Int,
        /** ECI dialect (partly derived; confirmed against the linked modules) */
        val eciDialect: Long,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val voice: String
        get() = prefs.getString(KEY_VOICE, "en-US")!!
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

        // eciDialect values: measured from each language module's registered constant
        // (lang/*/eci_ini_*.c *_eci_library_lang); one-to-one with build_native.sh LANGS
        // 0 = language not linked here (pt/fi/ko/zh-TW have no module)
        val LANGS = arrayOf(
            Lang("en-US", "English (US)", 0, 0x10000L),
            Lang("en-GB", "English (UK)", 1, 0x10001L),
            Lang("es-ES", "Spanish (Spain)", 2, 0x20000L),
            Lang("es-US", "Spanish (US)", -1, 0x20001L), // esus module
            Lang("es-MX", "Spanish (Mexico)", 3, 0x20002L), // esmx module
            Lang("fr-FR", "French (France)", 4, 0x30000L),
            Lang("fr-CA", "French (Canada)", 5, 0x30001L),
            Lang("de-DE", "German", 6, 0x40000L),
            Lang("it-IT", "Italian", 7, 0x50000L),
            Lang("pt-BR", "Portuguese (Brazil)", 8, 0x70000L),
            Lang("fi-FI", "Finnish", 9, 0x90000L),
            Lang("ja-JP", "Japanese", 10, 0x80000L),
            Lang("pl-PL", "Polish", -1, 0x110000L), // plpl module
            Lang("ko-KR", "Korean", 11, 0L), // not linked
            Lang("zh-CN", "Chinese (Mandarin)", 12, 0x60000L), // not linked in this build
            Lang("zh-TW", "Chinese (Taiwan)", 13, 0x60001L), // not linked
        )

        /** CF library code (Code Factory 10 languages) mapped from BCP-47 */
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
                else -> null      // zh/ja/ko have no CF library → the openevv chain handles them
            }
        }

        fun findLang(code: String?): Lang {
            if (code == null) return LANGS[0]// en-US
            for ( (l in LANGS) if (l.code == code) return l
            return LANGS[0] // en-US
        }
    }
}