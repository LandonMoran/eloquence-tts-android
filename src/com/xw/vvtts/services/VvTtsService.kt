package com.xw.vvtts.services

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.xw.vvtts.engine.EloquenceEngine
import com.xw.vvtts.utils.EmojiExpander
import com.xw.vvtts.utils.LanguageDetector
import com.xw.vvtts.utils.TextNormalizer
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.VoiceProfile
import java.util.Locale

/**
 * 双引擎系统 TTS：
 * - CF 引擎：deu/eng/enu/esm/esn/fra/frc/ita/ptb/fin 10 语言（完整 ECI，支持角色 annotation）
 * - 广荣引擎：zh-CN / zh-TW / ja / ko 等未覆盖语言兜底（中英确认可用）
 */
class VvTtsService : TextToSpeechService() {
    private var engine: EloquenceEngine? = null     // 广荣（中英）
    private var voiceConfig: VoiceConfig? = null
    private var voiceProfile: VoiceProfile? = null

    override fun onCreate() {
        super.onCreate()
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        engine = EloquenceEngine(this)
        engine!!.setVoiceProfile(voiceProfile)
        val ok = engine!!.initialize()
        // 恢复语言检测设置
        restoreLanguageSettings()
        // 预加载 Lingua
        LanguageDetector.preloadLingua()
        Log.e(TAG, "onCreate engine initialized=$ok")
    }

    override fun onDestroy() {
        // 不激进 shutdown：TextToSpeechService 会被系统频繁创建/销毁，
        // 激进 shutdown 会导致 native 引擎反复重载、进程重启。
        // 让系统 GC 回收，引擎 handle 泄漏可接受（Service 进程生命周期内复用）。
        try {
            if (engine != null) engine!!.stop()
        } catch (ignore: Throwable) {
        }
        super.onDestroy()
    }

    override fun onGetLanguage(): Array<String> {
        // 与 onGetVoices / onIsLanguageAvailable / onGetDefaultVoiceNameFor 完全对齐（ISO639-1）
        return arrayOf(
            "en", "de", "fr", "es", "it", "pt", "fi", "zh", "ja", "ko"
        )
    }

    override fun onGetDefaultVoiceNameFor(language: String, country: String, variant: String): String {
        val lang = (language ?: "").lowercase()
        val c = (country ?: "").uppercase()
        if (lang.startsWith("en")) return if ("GB" == c) "en-GB" else "en-US"
        if (lang.startsWith("de")) return "de-DE"
        if (lang.startsWith("fr")) return if ("CA" == c) "fr-CA" else "fr-FR"
        if (lang.startsWith("es")) return if ("MX" == c) "es-MX" else "es-ES"
        if (lang.startsWith("it")) return "it-IT"
        if (lang.startsWith("pt")) return "pt-BR"
        if (lang.startsWith("fi")) return "fi-FI"
        if (lang.startsWith("zh")) return if ("TW" == c) "zh-TW" else "zh-CN"
        if (lang.startsWith("ja")) return "ja-JP"
        if (lang.startsWith("ko")) return "ko-KR"
        return "en-US"
    }

    override fun onGetVoices(): List<Voice> {
        val voices = ArrayList<Voice>()
        // 每个 Voice 的 name 用 BCP-47，Locale 用对应 Locale，feature=null 表示普通
        voices.add(Voice("en-US", Locale.US,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("en-GB", Locale.UK,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("de-DE", Locale.GERMANY,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("fr-FR", Locale.FRANCE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("fr-CA", Locale.CANADA_FRENCH,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("es-ES", Locale("es", "ES"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("es-MX", Locale("es", "MX"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("it-IT", Locale.ITALY,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("pt-BR", Locale("pt", "BR"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("fi-FI", Locale("fi", "FI"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("ja-JP", Locale.JAPAN,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("ko-KR", Locale.KOREA,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("zh-CN", Locale.SIMPLIFIED_CHINESE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("zh-TW", Locale.TRADITIONAL_CHINESE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        return voices
    }

    override fun onIsLanguageAvailable(language: String, country: String, variant: String): Int {
        if (language == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val lang = language.lowercase()
        val supported = lang.startsWith("zh") || lang.startsWith("en") || lang.startsWith("de")
                || lang.startsWith("fr") || lang.startsWith("es") || lang.startsWith("it")
                || lang.startsWith("pt") || lang.startsWith("fi")
                || lang.startsWith("ja") || lang.startsWith("ko")
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED

        // 有国别/变体 → COUNTRY_AVAILABLE；仅语言 → AVAILABLE
        val hasCountry = country != null && country.isNotEmpty()
        val hasVariant = variant != null && variant.isNotEmpty()
        if (hasCountry || hasVariant) return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (hasCountry) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(language: String, country: String, variant: String): Int {
        return onIsLanguageAvailable(language, country, variant)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        var text: String? = request.text
        try {
            if (text == null || text.isEmpty()) {
                callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }

            // emoji -> we ask the speech engine to say what the symbol means.
            // The ECI engine has zero emoji support, so expand before anything else.
            text = EmojiExpander.expand(text!!)
            if (text == null || text.isEmpty()) {
                callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }

            // 自动检测 + 分片
            val segments = LanguageDetector.segment(text)

            callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)

            if (engine == null || !engine!!.isInitialized()) {
                callback.done()
                return
            }

            val preset = if (voiceProfile != null) voiceProfile!!.preset else 1
            // Android passes speech rate/pitch as PERCENTS where 100 = normal
            // (SynthesisRequest.getSpeechRate()/getPitch()). The framework
            // already folds the user's system TTS setting into these values,
            // and the engine's own scale is likewise 100 = neutral
            // (rate 1-300, pitch 0-100 with 50 neutral), so pass the
            // request through directly: TalkBack's speed/pitch sliders now
            // map 1:1 onto the engine (old code multiplied 100x100=10000,
            // which clamped to max speed and ignored the sliders).
            var sysRate = request.speechRate
            var sysPitch = request.pitch
            if (sysRate <= 0) sysRate = 100
            if (sysPitch <= 0) sysPitch = 100
            val rate = clamp(sysRate, 1, 300)
            // 100% (normal) -> engine-neutral 50; TalkBack pitch slider
            // 50-200 -> 25-100 (spans the engine's full +/-30 kona range).
            val pitch = clamp(50 + (sysPitch - 100) / 2, 0, 100)
            val volume = voiceConfig!!.volume

            for (seg in segments) {
                if (seg.text == null || seg.text!!.trim().isEmpty()) continue
                var segText: String = seg.text!!
                // The Apple CJK libs skip plain digits and many symbols; the
                // bundled TextNormalizer fixes exactly that for CJK segments.
                // (For en/de/etc. the ECI libs already read digits fine.)
                if (isCjkDialect(seg.dialect)) {
                    segText = TextNormalizer.normalizeForChinese(segText)
                }
                val pcm = engine!!.synthesizeCore(segText, seg.dialect, volume, preset, pitch, rate)
                if (pcm != null && pcm.size > 0) {
                    val bytes = shortsToBytes(pcm)
                    val max = callback.maxBufferSize
                    var offset = 0
                    while (offset < bytes.size) {
                        val len = Math.min(max, bytes.size - offset)
                        callback.audioAvailable(bytes, offset, len)
                        offset += len
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onSynthesizeText failed", e)
        } finally {
            try {
                callback.done()
            } catch (ignore: Throwable) {
            }
        }
    }

    private fun isCjkDialect(dialect: Int): Boolean {
        // TextNormalizer's symbol/number readings are Mandarin (it is
        // documented "给中文（简/繁）用"), so apply it to zh only — feeding
        // Chinese readings to ja/ko voices would be wrong.
        return dialect == EloquenceEngine.DIALECT_ZH_CN
                || dialect == EloquenceEngine.DIALECT_ZH_TW
    }

    private fun bcpToDialect(bcp: String): Int {
        if (bcp == null) return EloquenceEngine.DIALECT_EN_US
        if (bcp.startsWith("en")) return if ("GB" == bcp.substring(3)) EloquenceEngine.DIALECT_EN_GB else EloquenceEngine.DIALECT_EN_US
        if (bcp.startsWith("de")) return EloquenceEngine.DIALECT_DE_DE
        if (bcp.startsWith("fr")) return if ("ca".equals(bcp.substring(3), ignoreCase = true)) EloquenceEngine.DIALECT_FR_CA else EloquenceEngine.DIALECT_FR_FR
        if (bcp.startsWith("es")) return if ("mx".equals(bcp.substring(3), ignoreCase = true)) EloquenceEngine.DIALECT_ES_MX else EloquenceEngine.DIALECT_ES_ES
        if (bcp.startsWith("it")) return EloquenceEngine.DIALECT_IT_IT
        if (bcp.startsWith("pt")) return EloquenceEngine.DIALECT_PT_BR
        if (bcp.startsWith("fi")) return EloquenceEngine.DIALECT_FI_FI
        if (bcp.startsWith("zh")) return if ("tw".equals(bcp.substring(3), ignoreCase = true)) EloquenceEngine.DIALECT_ZH_TW else EloquenceEngine.DIALECT_ZH_CN
        if (bcp.startsWith("ja")) return EloquenceEngine.DIALECT_JA_JP
        if (bcp.startsWith("ko")) return EloquenceEngine.DIALECT_KO_KR
        return EloquenceEngine.DIALECT_EN_US
    }

    private fun detectBcp47(request: SynthesisRequest, text: String): String {
        if (text != null) {
            for (i in 0 until text.length) {
                val c = text[i]
                if (c.code >= 0x4E00 && c.code <= 0x9FFF) return "zh-CN"
            }
        }
        val lang = (request.language ?: "").lowercase()
        val c = (request.country ?: "").uppercase()
        if (lang.startsWith("en")) return if ("GB" == c) "en-GB" else "en-US"
        if (lang.startsWith("de")) return "de-DE"
        if (lang.startsWith("fr")) return if ("CA" == c) "fr-CA" else "fr-FR"
        if (lang.startsWith("es")) return if ("MX" == c) "es-MX" else "es-ES"
        if (lang.startsWith("it")) return "it-IT"
        if (lang.startsWith("pt")) return "pt-BR"
        if (lang.startsWith("fi")) return "fi-FI"
        return "en-US"
    }

    // ===== 苹果引擎链路（14 语言全走 synthesizeCore）=====
    private fun synthesizeGr(text: String?, dialect: Int, callback: SynthesisCallback) {
        callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (engine == null || !engine!!.isInitialized()) {
            Log.e(TAG, "engine not initialized")
            callback.done()
            return
        }
        if (text == null || text.isEmpty()) {
            callback.done()
            return
        }

        val preset = if (voiceProfile != null) voiceProfile!!.preset else 1
        // 14 语言全走自研桥接（苹果完整引擎），角色由 8 个 eciSetVoiceParam 注入音色差异
        val rate = voiceConfig!!.rate     // UI 1-300
        val pitch = voiceConfig!!.pitch   // UI 0-100
        val pcm = engine!!.synthesizeCore(text, dialect, voiceConfig!!.volume, preset, pitch, rate)
        Log.e(TAG, "core synthesize dialect=" + Integer.toHexString(dialect)
                + " preset=" + preset + " pitch=" + pitch + " rate=" + rate
                + " pcm=" + if (pcm == null) "null" else pcm.size)

        if (pcm != null && pcm.size > 0) {
            val bytes = shortsToBytes(pcm)
            val max = callback.maxBufferSize
            var offset = 0
            while (offset < bytes.size) {
                val len = Math.min(max, bytes.size - offset)
                callback.audioAvailable(bytes, offset, len)
                offset += len
            }
        }
        callback.done()
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int {
        return if (v < lo) lo else Math.min(v, hi)
    }

    // 界面预设 → 苹果 CSV eciVoiceNumber
    private fun presetEciVoice(n: Int): Int {
        return when (n) {
            1 -> 1  // Reed
            2 -> 2  // Shelley
            3 -> 3  // Sandy
            4 -> 4  // Rocko
            5 -> 6  // Flo
            6 -> 7  // Grandma
            7 -> 8  // Grandpa
            8 -> 9  // Eddy
            else -> 1
        }
    }

    // 界面预设 → 苹果 CSV {breathiness, headSize, roughness, pitchFluctuation, speed}
    private fun presetCsvParams(n: Int): IntArray {
        return when (n) {
            2 -> intArrayOf(20, 30, 5, 30, 50)   // Shelley
            3 -> intArrayOf(61, 31, 18, 44, 50)  // Sandy
            4 -> intArrayOf(0, 50, 45, 25, 48)   // Rocko
            5 -> intArrayOf(35, 35, 10, 40, 52)  // Flo
            6 -> intArrayOf(45, 40, 20, 35, 45)  // Grandma
            7 -> intArrayOf(30, 45, 28, 22, 44)  // Grandpa
            8 -> intArrayOf(10, 55, 8, 35, 50)   // Eddy
            else -> intArrayOf(0, 50, 0, 30, 50) // Reed
        }
    }

    private fun shortsToBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = pcm[i]
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun onStop() {
        if (engine != null) engine!!.stop()
    }

    // SharedPreferences 恢复语言检测设置
    private fun restoreLanguageSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        LanguageDetector.setDetectionEnabled(prefs.getBoolean("detection_enabled", true))
        LanguageDetector.setFixedDialect(prefs.getInt("fixed_dialect", LanguageDetector.DIALECT_EN_US))
        LanguageDetector.setChineseDialect(prefs.getInt("chinese_dialect", LanguageDetector.DIALECT_ZH_CN))
        LanguageDetector.setEnglishDialect(prefs.getInt("english_dialect", LanguageDetector.DIALECT_EN_US))
        LanguageDetector.setSpanishDialect(prefs.getInt("spanish_dialect", LanguageDetector.DIALECT_ES_ES))
        LanguageDetector.setFrenchDialect(prefs.getInt("french_dialect", LanguageDetector.DIALECT_FR_FR))
        LanguageDetector.setDefaultLanguage(prefs.getInt("default_language", LanguageDetector.DEFAULT_UNSPECIFIED))
        LanguageDetector.setEnabledLanguages(prefs.getStringSet("enabled_langs", null))
    }

    companion object {
        private const val TAG = "VvTtsService"
        private const val PREFS_NAME = "vvtts_lang_settings"
    }
}