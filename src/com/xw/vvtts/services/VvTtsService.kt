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
 * Eloquence TTS service (openevv port, single engine).
 * 11 dialects are linked in this build: en-US/en-GB/de-DE/fr-FR/fr-CA/
 * es-ES/es-US/es-MX/it-IT/ja-JP/pl-PL. (zh/pt/fi/ko are not linked here.)
 */
class VvTtsService : TextToSpeechService() {
    private var engine: EloquenceEngine? = null     // openevv ECI engine (linked dialects only)
    private var voiceConfig: VoiceConfig? = null
    private var voiceProfile: VoiceProfile? = null

    override fun onCreate() {
        super.onCreate()
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        engine = EloquenceEngine(this)
        val ok = engine!!.initialize()
        // Restore the language-detection settings
        restoreLanguageSettings()
        // Preload Lingua (background thread)
        LanguageDetector.preloadLingua()
        Log.e(TAG, "onCreate engine initialized=$ok")
    }

    override fun onDestroy() {
        // No aggressive shutdown: TextToSpeechService gets created/destroyed,
        // aggressive shutdown would force the native engine to reload repeatedly (process restarts are expensive).)
        // Let GC reclaim; a leaked engine handle is acceptable (the handle lives as long as the service process etc.).
        try {
            if (engine != null) engine!!.stop()
        } catch (ignore: Throwable) {
        }
        super.onDestroy()
    }

    override fun onGetLanguage(): Array<String> {
        // Keep in sync with onGetVoices / onIsLanguageAvailable /
        // onGetDefaultVoiceNameFor (ISO 639-1); only dialects actually
        // linked in this build are listed.

        return arrayOf(
            "en", "de", "fr", "es", "it", "ja", "pl", "pt", "fi", "zh"
        )
    }

    override fun onGetDefaultVoiceNameFor(language: String, country: String, variant: String): String {
        val lang = (language ?: "").lowercase()
        val c = (country ?: "").uppercase()
        if (lang.startsWith("en")) return if ("GB" == c) "en-GB" else "en-US"
        if (lang.startsWith("de")) return "de-DE"
        if (lang.startsWith("fr")) return if ("CA" == c) "fr-CA" else "fr-FR"
        if (lang.startsWith("es")) return when (c) { "US" -> "es-US"; "MX" -> "es-MX"; else -> "es-ES" }
        if (lang.startsWith("it")) return "it-IT"
        if (lang.startsWith("ja")) return "ja-JP"
        if (lang.startsWith("pl")) return "pl-PL"
        if (lang.startsWith("pt")) return if ("BR" == c) "pt-BR" else "pt-PT"
        if (lang.startsWith("fi")) return "fi-FI"
        if (lang.startsWith("zh")) return "zh-CN"
        return "en-US"
    }

    override fun onGetVoices(): List<Voice> {
        val voices = ArrayList<Voice>()
        // Voice names use BCP-47; Locale matches the dialect; feature=null = plain
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
        voices.add(Voice("es-US", Locale("es", "US"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("es-MX", Locale("es", "MX"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("it-IT", Locale.ITALY,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("ja-JP", Locale.JAPAN,
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("pl-PL", Locale("pl", "PL"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("pt-BR", Locale("pt", "BR"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("fi-FI", Locale("fi", "FI"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        voices.add(Voice("zh-CN", Locale("zh", "CN"),
            Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null))
        // Only advertise dialects actually linked in this build (build_native.sh LANGS)。
        return voices
    }

    override fun onIsLanguageAvailable(language: String, country: String, variant: String): Int {
        if (language == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val lang = language.lowercase()
        val supported = lang.startsWith("en") || lang.startsWith("de")
                || lang.startsWith("fr") || lang.startsWith("es") || lang.startsWith("it")
                || lang.startsWith("ja") || lang.startsWith("pl") || lang.startsWith("pt") || lang.startsWith("fi")
                || lang.startsWith("zh")
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED

        // has country/variant -> COUNTRY_VAR_AVAILABLE; language only -> AVAILABLE
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
        Log.d("VvTtsService", "synth voice='" + request.voiceName + "' lang=" + request.language)

        // The system TTS language picker passes the chosen voice in request.voiceName.

        // A zh picker row iso honored per-utterance (and reverted in finally):the
                // engine speaks zh via its oracle bank, so a zh voice must be pinned for
                // that utterance;the app's own detection/default stays untouched。
        val savedDefault = LanguageDetector.getDefaultLanguage()
        val savedLangs = LanguageDetector.getEnabledLanguages()
        val savedFixed = LanguageDetector.getFixedDialect()
        val voiceName = request.voiceName
        val appVoice = voiceConfig?.voice
        // A zh voice from the *system picker* always pins zh (explicit user
        // choice(;the app's own "Voice" row only pins zh when detection is OFF —
        // with Auto ON the spoken-voice locale must follow the detected text,
        // otherwise choosing "Auto detect" after a zh voice pick would read
        // every language as Chinese (the reported bug).
        val autoDetect = LanguageDetector.isDetectionEnabled()
        val zhRequested = (voiceName != null && voiceName.lowercase().startsWith("zh"))
            || (voiceName.isNullOrBlank() && !autoDetect && appVoice != null && appVoice.lowercase().startsWith("zh"))
        if (zhRequested) {

            LanguageDetector.setDefaultLanguage(EloquenceEngine.DIALECT_ZH_CN)


            LanguageDetector.setEnabledLanguages(LanguageDetector.getEnabledLanguages() + "zh")


            LanguageDetector.setFixedDialect(EloquenceEngine.DIALECT_ZH_CN)


        }

        // Pin user voice rows into base dialect (system picker first;else app row.
        if (!zhRequested) {
            val pv = voiceName ?: appVoice

            if (pv != null) {
                val pd = VoiceConfig.findLang(pv).eciDialect

                if (pd != 0L) {
                    LanguageDetector.setDefaultLanguage(pd.toInt())
                    if (!LanguageDetector.isDetectionEnabled()) LanguageDetector.setFixedDialect(pd.toInt())
                }
            }
        }

        var started = false
        try {
            if (text == null || text.isEmpty()) {
                return  // finally emits the start+done pair for an empty utterance
            }

            // emoji -> we ask the speech engine to say what the symbol means.
            // The ECI engine has zero emoji support, so expand before anything else.
            text = EmojiExpander.expand(text!!)
            if (text == null || text.isEmpty()) {
                return  // finally emits the start+done pair for an empty utterance
            }

            // Auto-detect + chunk
            val segments = LanguageDetector.segment(text)


            if (engine == null || !engine!!.isInitialized()) {
                return  // finally emits the start+done pair for an uninitialized engine
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
            val rate = clamp(Math.round(voiceConfig!!.rate * (sysRate / 100.0f)).toInt(),1,300)
            // 100% (normal) -> engine-neutral 50; TalkBack pitch slider
            // 50-200 -> 25-100 (spans the engine's full +/-30 kona range).
            val pitch = clamp(voiceConfig!!.pitch.coerceIn(0,100) + (sysPitch - 100) / 2, 0, 100)
            val volume = voiceConfig!!.volume

            for (seg in segments) {
                if (seg.text == null || seg.text!!.trim().isEmpty()) continue
                var segText: String = seg.text!!
                Log.i("VvTtsService", "seg 0x" + Integer.toHexString(seg.dialect) + " '" + segText + "'")
                // The Apple CJK libs skip plain digits and many symbols;the
                // bundled TextNormalizer fixes exactly that for CJK segments.
                // (For en/de/etc. the ECI libs already read digits fine.)
                if (isCjkDialect(seg.dialect)) {
                    segText = TextNormalizer.normalizeForChinese(segText)
                }
                val pcm = engine!!.synthesizeCore(segText, seg.dialect, volume, preset, pitch, rate)
                if (pcm != null && pcm.size > 0) {
                    if (!started) {
                        callback.start(engine!!.getCoreSampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)
                        started = true
                    }
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
                    // Revert the per-utterance override (preserve app-pref state)。
                    LanguageDetector.setDefaultLanguage(savedDefault)
                    LanguageDetector.setEnabledLanguages(savedLangs)
                    LanguageDetector.setFixedDialect(savedFixed)
                    try {
                        // Playback contract: start() must precede done((),the framework throws
                        // otherwise. One pair per utterance — every path funnels here, so
                        // all silent/empty/early returns get the pair exactly once。
                        if (!started) {
                            callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        }
                        callback.done()
                    } catch (ignore: Throwable) {
                    }
                }
    }

    private fun isCjkDialect(dialect: Int): Boolean {
        // TextNormalizer's symbol/number readings are Mandarin (it is
        // documented "for Chinese (Simplified/Traditional)"), so apply it to zh only — feeding
        // Chinese readings to ja/ko voices would be wrong.
        return dialect == EloquenceEngine.DIALECT_ZH_CN
                || dialect == EloquenceEngine.DIALECT_ZH_TW
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int {
        return if (v < lo) lo else Math.min(v, hi)
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

    // Restore language-detection settings from SharedPreferences
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