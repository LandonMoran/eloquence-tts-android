package com.xw.vvtts.services;

import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import com.xw.vvtts.engine.EloquenceEngine;
import com.xw.vvtts.utils.LanguageDetector;
import com.xw.vvtts.utils.VoiceConfig;
import com.xw.vvtts.utils.VoiceProfile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 双引擎系统 TTS：
 * - CF 引擎：deu/eng/enu/esm/esn/fra/frc/ita/ptb/fin 10 语言（完整 ECI，支持角色 annotation）
 * - 广荣引擎：zh-CN / zh-TW / ja / ko 等未覆盖语言兜底（中英确认可用）
 */
public class VvTtsService extends TextToSpeechService {
    private static final String TAG = "VvTtsService";

    private EloquenceEngine engine;      // 广荣（中英）
    private VoiceConfig voiceConfig;
    private VoiceProfile voiceProfile;

    // CF 实例按语言缓存（每个 so 一个实例，切换语言时懒加载）
    private final Set<String> cfReady = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        voiceConfig = new VoiceConfig(this);
        voiceProfile = new VoiceProfile(this);
        engine = new EloquenceEngine(this);
        engine.setVoiceProfile(voiceProfile);
        boolean ok = engine.initialize();
        // 恢复语言检测设置
        restoreLanguageSettings();
        // 预加载 Lingua
        LanguageDetector.preloadLingua();
        Log.e(TAG, "onCreate engine initialized=" + ok);
    }

    @Override
    public void onDestroy() {
        // 不激进 shutdown：TextToSpeechService 会被系统频繁创建/销毁，
        // 激进 shutdown 会导致 native 引擎反复重载、进程重启。
        // 让系统 GC 回收，引擎 handle 泄漏可接受（Service 进程生命周期内复用）。
        try {
            if (engine != null) engine.stop();
        } catch (Throwable ignore) {}
        super.onDestroy();
    }

    @Override
    public String[] onGetLanguage() {
        // 与 onGetVoices / onIsLanguageAvailable / onGetDefaultVoiceNameFor 完全对齐（ISO639-1）
        return new String[]{
            "en", "de", "fr", "es", "it", "pt", "fi", "zh", "ja", "ko"
        };
    }

    @Override
    public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        String lang = (language == null ? "" : language).toLowerCase();
        String c = (country == null ? "" : country).toUpperCase();
        if (lang.startsWith("en")) return "GB".equals(c) ? "en-GB" : "en-US";
        if (lang.startsWith("de")) return "de-DE";
        if (lang.startsWith("fr")) return "CA".equals(c) ? "fr-CA" : "fr-FR";
        if (lang.startsWith("es")) return "MX".equals(c) ? "es-MX" : "es-ES";
        if (lang.startsWith("it")) return "it-IT";
        if (lang.startsWith("pt")) return "pt-BR";
        if (lang.startsWith("fi")) return "fi-FI";
        if (lang.startsWith("zh")) return "TW".equals(c) ? "zh-TW" : "zh-CN";
        if (lang.startsWith("ja")) return "ja-JP";
        if (lang.startsWith("ko")) return "ko-KR";
        return "en-US";
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> voices = new ArrayList<>();
        // 每个 Voice 的 name 用 BCP-47，Locale 用对应 Locale，feature=null 表示普通
        voices.add(new Voice("en-US", Locale.US,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("en-GB", Locale.UK,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("de-DE", Locale.GERMANY,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("fr-FR", Locale.FRANCE,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("fr-CA", Locale.CANADA_FRENCH,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("es-ES", new Locale("es", "ES"),
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("es-MX", new Locale("es", "MX"),
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("it-IT", Locale.ITALY,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("pt-BR", new Locale("pt", "BR"),
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("fi-FI", new Locale("fi", "FI"),
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("ja-JP", Locale.JAPAN,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("ko-KR", Locale.KOREA,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("zh-CN", Locale.SIMPLIFIED_CHINESE,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        voices.add(new Voice("zh-TW", Locale.TRADITIONAL_CHINESE,
                Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, null));
        return voices;
    }

    @Override
    public int onIsLanguageAvailable(String language, String country, String variant) {
        if (language == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        String lang = language.toLowerCase();
        boolean supported = lang.startsWith("zh") || lang.startsWith("en") || lang.startsWith("de")
                || lang.startsWith("fr") || lang.startsWith("es") || lang.startsWith("it")
                || lang.startsWith("pt") || lang.startsWith("fi")
                || lang.startsWith("ja") || lang.startsWith("ko");
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED;

        // 有国别/变体 → COUNTRY_AVAILABLE；仅语言 → AVAILABLE
        boolean hasCountry = country != null && !country.isEmpty();
        boolean hasVariant = variant != null && !variant.isEmpty();
        if (hasCountry || hasVariant) return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
        if (hasCountry) return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    public int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }

    @Override
    public void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getText();

        try {
            if (text == null || text.isEmpty()) {
                callback.start(EloquenceEngine.SAMPLE_RATE,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, 1);
                callback.done();
                return;
            }

            // 自动检测 + 分片
            List<LanguageDetector.Segment> segments = LanguageDetector.segment(text);

            callback.start(EloquenceEngine.SAMPLE_RATE,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, 1);

            if (engine == null || !engine.isInitialized()) {
                callback.done();
                return;
            }

            int preset = voiceProfile != null ? voiceProfile.getPreset() : 1;
            int rate = voiceConfig.getRate();
            int pitch = voiceConfig.getPitch();
            int volume = voiceConfig.getVolume();

            for (LanguageDetector.Segment seg : segments) {
                if (seg.text == null || seg.text.trim().isEmpty()) continue;
                short[] pcm = engine.synthesizeCore(seg.text, seg.dialect, volume, preset, pitch, rate);
                if (pcm != null && pcm.length > 0) {
                    byte[] bytes = shortsToBytes(pcm);
                    int max = callback.getMaxBufferSize();
                    int offset = 0;
                    while (offset < bytes.length) {
                        int len = Math.min(max, bytes.length - offset);
                        callback.audioAvailable(bytes, offset, len);
                        offset += len;
                    }
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "onSynthesizeText failed", e);
        } finally {
            try { callback.done(); } catch (Throwable ignore) {}
        }
    }

    private static int bcpToDialect(String bcp) {
        if (bcp == null) return EloquenceEngine.DIALECT_EN_US;
        if (bcp.startsWith("en")) return "GB".equals(bcp.substring(3)) ? EloquenceEngine.DIALECT_EN_GB : EloquenceEngine.DIALECT_EN_US;
        if (bcp.startsWith("de")) return EloquenceEngine.DIALECT_DE_DE;
        if (bcp.startsWith("fr")) return "ca".equalsIgnoreCase(bcp.substring(3)) ? EloquenceEngine.DIALECT_FR_CA : EloquenceEngine.DIALECT_FR_FR;
        if (bcp.startsWith("es")) return "mx".equalsIgnoreCase(bcp.substring(3)) ? EloquenceEngine.DIALECT_ES_MX : EloquenceEngine.DIALECT_ES_ES;
        if (bcp.startsWith("it")) return EloquenceEngine.DIALECT_IT_IT;
        if (bcp.startsWith("pt")) return EloquenceEngine.DIALECT_PT_BR;
        if (bcp.startsWith("fi")) return EloquenceEngine.DIALECT_FI_FI;
        if (bcp.startsWith("zh")) return "tw".equalsIgnoreCase(bcp.substring(3)) ? EloquenceEngine.DIALECT_ZH_TW : EloquenceEngine.DIALECT_ZH_CN;
        if (bcp.startsWith("ja")) return EloquenceEngine.DIALECT_JA_JP;
        if (bcp.startsWith("ko")) return EloquenceEngine.DIALECT_KO_KR;
        return EloquenceEngine.DIALECT_EN_US;
    }

    private static String detectBcp47(SynthesisRequest request, String text) {
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) return "zh-CN";
            }
        }
        String lang = (request.getLanguage() == null ? "" : request.getLanguage()).toLowerCase();
        String c = (request.getCountry() == null ? "" : request.getCountry()).toUpperCase();
        if (lang.startsWith("en")) return "GB".equals(c) ? "en-GB" : "en-US";
        if (lang.startsWith("de")) return "de-DE";
        if (lang.startsWith("fr")) return "CA".equals(c) ? "fr-CA" : "fr-FR";
        if (lang.startsWith("es")) return "MX".equals(c) ? "es-MX" : "es-ES";
        if (lang.startsWith("it")) return "it-IT";
        if (lang.startsWith("pt")) return "pt-BR";
        if (lang.startsWith("fi")) return "fi-FI";
        return "en-US";
    }

    // ===== 苹果引擎链路（14 语言全走 synthesizeCore）=====
    private void synthesizeGr(String text, int dialect, SynthesisCallback callback) {
        callback.start(EloquenceEngine.SAMPLE_RATE,
                android.media.AudioFormat.ENCODING_PCM_16BIT, 1);
        if (engine == null || !engine.isInitialized()) {
            Log.e(TAG, "engine not initialized");
            callback.done();
            return;
        }
        if (text == null || text.isEmpty()) { callback.done(); return; }

        int preset = voiceProfile != null ? voiceProfile.getPreset() : 1;
        // 14 语言全走自研桥接（苹果完整引擎），角色由 8 个 eciSetVoiceParam 注入音色差异
        int rate = voiceConfig.getRate();     // UI 1-300
        int pitch = voiceConfig.getPitch();   // UI 0-100
        short[] pcm = engine.synthesizeCore(text, dialect, voiceConfig.getVolume(), preset, pitch, rate);
        Log.e(TAG, "core synthesize dialect=" + Integer.toHexString(dialect)
                + " preset=" + preset + " pitch=" + pitch + " rate=" + rate
                + " pcm=" + (pcm == null ? "null" : pcm.length));

        if (pcm != null && pcm.length > 0) {
            byte[] bytes = shortsToBytes(pcm);
            int max = callback.getMaxBufferSize();
            int offset = 0;
            while (offset < bytes.length) {
                int len = Math.min(max, bytes.length - offset);
                callback.audioAvailable(bytes, offset, len);
                offset += len;
            }
        }
        callback.done();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (Math.min(v, hi));
    }

    // 界面预设 → 苹果 CSV eciVoiceNumber
    private static int presetEciVoice(int n) {
        switch (n) {
            case 1: return 1;  // Reed
            case 2: return 2;  // Shelley
            case 3: return 3;  // Sandy
            case 4: return 4;  // Rocko
            case 5: return 6;  // Flo
            case 6: return 7;  // Grandma
            case 7: return 8;  // Grandpa
            case 8: return 9;  // Eddy
            default: return 1;
        }
    }

    // 界面预设 → 苹果 CSV {breathiness, headSize, roughness, pitchFluctuation, speed}
    private static int[] presetCsvParams(int n) {
        switch (n) {
            case 2: return new int[]{20, 30, 5, 30, 50};   // Shelley
            case 3: return new int[]{61, 31, 18, 44, 50};  // Sandy
            case 4: return new int[]{0, 50, 45, 25, 48};   // Rocko
            case 5: return new int[]{35, 35, 10, 40, 52};  // Flo
            case 6: return new int[]{45, 40, 20, 35, 45};  // Grandma
            case 7: return new int[]{30, 45, 28, 22, 44};  // Grandpa
            case 8: return new int[]{10, 55, 8, 35, 50};   // Eddy
            case 1:
            default: return new int[]{0, 50, 0, 30, 50};   // Reed
        }
    }

    private static byte[] shortsToBytes(short[] pcm) {
        byte[] out = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            short s = pcm[i];
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    @Override
    public void onStop() {
        if (engine != null) engine.stop();
    }

    // SharedPreferences 恢复语言检测设置
    private static final String PREFS_NAME = "vvtts_lang_settings";
    private void restoreLanguageSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        LanguageDetector.setDetectionEnabled(prefs.getBoolean("detection_enabled", true));
        LanguageDetector.setFixedDialect(prefs.getInt("fixed_dialect", LanguageDetector.DIALECT_EN_US));
        LanguageDetector.setChineseDialect(prefs.getInt("chinese_dialect", LanguageDetector.DIALECT_ZH_CN));
        LanguageDetector.setEnglishDialect(prefs.getInt("english_dialect", LanguageDetector.DIALECT_EN_US));
        LanguageDetector.setSpanishDialect(prefs.getInt("spanish_dialect", LanguageDetector.DIALECT_ES_ES));
        LanguageDetector.setFrenchDialect(prefs.getInt("french_dialect", LanguageDetector.DIALECT_FR_FR));
        LanguageDetector.setDefaultLanguage(prefs.getInt("default_language", LanguageDetector.DEFAULT_UNSPECIFIED));
        LanguageDetector.setEnabledLanguages(prefs.getStringSet("enabled_langs", null));
    }
}