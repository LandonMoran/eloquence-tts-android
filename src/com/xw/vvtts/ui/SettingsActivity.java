package com.xw.vvtts.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xw.vvtts.engine.EloquenceEngine;
import com.xw.vvtts.R;
import com.xw.vvtts.utils.LanguageDetector;
import com.xw.vvtts.utils.VoiceConfig;
import com.xw.vvtts.utils.VoiceProfile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends Activity {
    private VoiceConfig voiceConfig;
    private VoiceProfile voiceProfile;
    private EloquenceEngine engine;
    private TextView rateVal, pitchVal, volumeVal;
    private Button langBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        voiceConfig = new VoiceConfig(this);
        voiceProfile = new VoiceProfile(this);
        engine = new EloquenceEngine(this);
        engine.setVoiceProfile(voiceProfile); // 让合成读取角色自定义覆盖
        engine.initialize();
        // 预加载 Lingua 语言检测器（后台线程，避免首次合成卡顿）
        LanguageDetector.preloadLingua();
        // 从 SharedPreferences 恢复语言检测设置
        restoreLanguageSettings();
        // 自动测试钩子：am start --es autotest chinese
        String autotest = getIntent() != null ? getIntent().getStringExtra("autotest") : null;
        if ("chinese".equals(autotest)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::testSpeech, 3000);
        } else if ("german".equals(autotest)) {
            // 兼容旧钩子：英文三角色测试，用于验证角色参数
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::testGerman, 3000);
        }
        // 自动应用当前角色配置
        engine.applyVoiceProfile(EloquenceEngine.DIALECT_ZH_CN, voiceProfile);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Eloquence TTS");
        title.setTextSize(22);
        root.addView(title);

        // 语言环境（融合成一个按钮，按钮文案即当前选择，不带独立标题）
        Button langBtnLocal = new Button(this);
        langBtn = langBtnLocal;
        langBtnLocal.setContentDescription(getString(R.string.lang_header)); // 无障碍 description
        langBtnLocal.setOnClickListener(v -> showLanguageDialog());
        root.addView(langBtnLocal);
        refreshLangButton(langBtnLocal);

        // 发音角色入口（纯中文，点选即进）
        Button voiceProfileBtn = new Button(this);
        voiceProfileBtn.setText(getString(R.string.voice_profile));
        LinearLayout.LayoutParams vpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vpLp.setMargins(0, dp(12), 0, 0);
        voiceProfileBtn.setLayoutParams(vpLp);
        voiceProfileBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, VoiceProfileActivity.class));
        });
        root.addView(voiceProfileBtn);

        // 语言检测设置
        Button langDetectBtn = new Button(this);
        langDetectBtn.setText("语言检测设置");
        LinearLayout.LayoutParams ldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ldLp.setMargins(0, dp(4), 0, 0);
        langDetectBtn.setLayoutParams(ldLp);
        langDetectBtn.setOnClickListener(v -> showDetectionSettingsDialog());
        root.addView(langDetectBtn);

        // 语速
        rateVal = addSeekBar(root, getString(R.string.rate), voiceConfig.getRate(), 1, 300, v -> {
            voiceConfig.setRate(v);
            rateVal.setText(getString(R.string.rate_fmt, v));
        });

        // 音调
        pitchVal = addSeekBar(root, getString(R.string.pitch), voiceConfig.getPitch(), 0, 100, v -> {
            voiceConfig.setPitch(v);
            pitchVal.setText(getString(R.string.pitch_fmt, v));
        });

        // 音量
        volumeVal = addSeekBar(root, getString(R.string.volume), voiceConfig.getVolume(), 0, 100, v -> {
            voiceConfig.setVolume(v);
            volumeVal.setText(getString(R.string.volume_fmt, v));
        });

        // 测试按钮（单一，按当前语言播放对应测试文本）
        Button testBtn = new Button(this);
        testBtn.setText(getString(R.string.test));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(20), 0, 0);
        testBtn.setLayoutParams(lp);
        testBtn.setOnClickListener(v -> testSpeech());
        root.addView(testBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView addSeekBar(LinearLayout root, String label, int initial, int min, int max, java.util.function.IntConsumer cb) {
        TextView tv = new TextView(this);
        // label 已经是资源字符串，这里直接拼接 %d%%
        tv.setText(label + ": " + initial + "%");
        tv.setTextSize(14);
        tv.setPadding(0, dp(12), 0, dp(4));
        root.addView(tv);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean f) {
                if (f || true) cb.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(bar);
        return tv;
    }

    private void testSpeech() {
        if (engine == null || !engine.isInitialized()) {
            Toast.makeText(this, "引擎未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        int preset = voiceProfile != null ? voiceProfile.getPreset() : 1;
        // 根据当前语言环境选测试文本 + 对应 dialect
        String text;
        int dialect;
        if (voiceConfig.isAutoDetect()) {
            text = "这是一个语音合成测试。";
            dialect = EloquenceEngine.DIALECT_ZH_CN;
        } else {
            String code = voiceConfig.getVoice();
            VoiceConfig.Lang lang = VoiceConfig.findLang(code);
            text = sampleTextFor(lang.code);
            dialect = bcpToDialect(lang.code);
        }
        short[] pcm = engine.synthesizeCore(text, dialect,
                voiceConfig.getVolume(), preset,
                voiceConfig.getPitch(), voiceConfig.getRate());
        if (pcm != null && pcm.length > 0) {
            playPcm(pcm, EloquenceEngine.SAMPLE_RATE);
            Toast.makeText(this, "已发音", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "合成失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 各语言的测试文本 */
    private static String sampleTextFor(String code) {
        if (code == null) return "Hello, this is a speech test.";
        if (code.startsWith("zh")) return "这是一个语音合成测试。";
        if (code.startsWith("en")) return "Hello, this is a speech synthesis test.";
        if (code.startsWith("de")) return "Hallo, das ist ein Sprachsynthesetest.";
        if (code.startsWith("fr")) return "Bonjour, ceci est un test de synthèse vocale.";
        if (code.startsWith("es")) return "Hola, esta es una prueba de síntesis de voz.";
        if (code.startsWith("it")) return "Ciao, questo è un test di sintesi vocale.";
        if (code.startsWith("pt")) return "Olá, este é um teste de síntese de voz.";
        if (code.startsWith("fi")) return "Hei, tämä on puhesynteesitesti.";
        if (code.startsWith("ja")) return "これは音声合成のテストです。";
        if (code.startsWith("ko")) return "이것은 음성 합성 테스트입니다.";
        return "Hello, this is a speech synthesis test.";
    }

    /** bcp47 → ECI dialect */
    private static int bcpToDialect(String code) {
        if (code == null) return EloquenceEngine.DIALECT_EN_US;
        if (code.startsWith("zh")) return EloquenceEngine.DIALECT_ZH_CN;
        if (code.startsWith("en")) return EloquenceEngine.DIALECT_EN_US;
        if (code.startsWith("de")) return EloquenceEngine.DIALECT_DE_DE;
        if (code.startsWith("fr")) return EloquenceEngine.DIALECT_FR_FR;
        if (code.startsWith("es")) return EloquenceEngine.DIALECT_ES_ES;
        if (code.startsWith("it")) return EloquenceEngine.DIALECT_IT_IT;
        if (code.startsWith("pt")) return EloquenceEngine.DIALECT_PT_BR;
        if (code.startsWith("fi")) return EloquenceEngine.DIALECT_FI_FI;
        if (code.startsWith("ja")) return EloquenceEngine.DIALECT_JA_JP;
        if (code.startsWith("ko")) return EloquenceEngine.DIALECT_KO_KR;
        return EloquenceEngine.DIALECT_EN_US;
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

    // 界面预设 → 苹果 CSV {breathiness, headSize, roughness, pitchFluctuation, speed}（第5位speed）
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

    private void testGerman() {
        // 英文角色对比测试：Reed(1) → Sandy(2) → Grandpa(8)
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            short[] pcm1 = engine.synthesizeCore("Hello, this is a voice test.",
                    EloquenceEngine.DIALECT_EN_US, voiceConfig.getVolume(), 1);
            short[] pcm2 = engine.synthesizeCore("Hello, this is a voice test.",
                    EloquenceEngine.DIALECT_EN_US, voiceConfig.getVolume(), 2);
            short[] pcm3 = engine.synthesizeCore("Hello, this is a voice test.",
                    EloquenceEngine.DIALECT_EN_US, voiceConfig.getVolume(), 8);
            long ms = System.currentTimeMillis() - t0;
            int total = (pcm1==null?0:pcm1.length)+(pcm2==null?0:pcm2.length)+(pcm3==null?0:pcm3.length);
            Log.e("RoleTest", "three roles total=" + total + " shorts in " + ms + "ms");
            runOnUiThread(() -> {
                if (total > 0) {
                    Toast.makeText(this, "3 roles OK total=" + total, Toast.LENGTH_SHORT).show();
                    // 顺序连播（一个播完再播下一个，避免重叠）
                    short[][] pcms = {pcm1, pcm2, pcm3};
                    playSequential(pcms, 0, EloquenceEngine.SAMPLE_RATE);
                } else {
                    Toast.makeText(this, "角色测试失败（看日志）", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
    private void playPcm(short[] pcm, int sampleRate) {
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            short s = pcm[i];
            bytes[i * 2] = (byte) (s & 0xFF);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bytes.length, AudioTrack.MODE_STATIC);
        track.write(bytes, 0, bytes.length);
        track.play();
    }
    /** 顺序播放多段 PCM（后台线程依次播，每段按时长 sleep 等待播完） */
    private void playSequential(short[][] pcms, int idx, int sampleRate) {
        new Thread(() -> {
            for (int i = idx; i < pcms.length; i++) {
                short[] p = pcms[i];
                if (p == null || p.length == 0) continue;
                playPcmAndWait(p, sampleRate);
            }
        }).start();
    }
    /** 播放并阻塞等待播完（时长 = samples / rate） */
    private void playPcmAndWait(short[] pcm, int sampleRate) {
        byte[] bytes = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            short s = pcm[i];
            bytes[i * 2] = (byte) (s & 0xFF);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bytes.length, AudioTrack.MODE_STATIC);
        track.write(bytes, 0, bytes.length);
        track.play();
        try {
            Thread.sleep((long) (pcm.length * 1000.0 / sampleRate) + 120);
        } catch (InterruptedException ignore) {}
        track.stop();
        track.release();
    }

    private int dp(int px) { return (int) (px * getResources().getDisplayMetrics().density); }

    private void refreshLangButton(Button btn) {
        if (LanguageDetector.isDetectionEnabled()) {
            btn.setText("自动检测");
        } else {
            int d = LanguageDetector.getFixedDialect();
            String name = dialectName(d);
            btn.setText(name);
        }
    }

    private static String dialectName(int dialect) {
        if (dialect == LanguageDetector.DIALECT_EN_US) return "English (US)";
        if (dialect == LanguageDetector.DIALECT_EN_GB) return "English (UK)";
        if (dialect == LanguageDetector.DIALECT_DE_DE) return "Deutsch";
        if (dialect == LanguageDetector.DIALECT_FR_FR) return "Français";
        if (dialect == LanguageDetector.DIALECT_ES_ES) return "Español";
        if (dialect == LanguageDetector.DIALECT_IT_IT) return "Italiano";
        if (dialect == LanguageDetector.DIALECT_PT_BR) return "Português";
        if (dialect == LanguageDetector.DIALECT_FI_FI) return "Suomi";
        if (dialect == LanguageDetector.DIALECT_ZH_CN) return "中文（简体）";
        if (dialect == LanguageDetector.DIALECT_ZH_TW) return "中文（台湾）";
        if (dialect == LanguageDetector.DIALECT_JA_JP) return "日本語";
        if (dialect == LanguageDetector.DIALECT_KO_KR) return "한국어";
        return "English (US)";
    }

    private void showLanguageDialog() {
        String[] items = {
            "🔄 自动检测",
            "English (US)",
            "English (UK)",
            "Deutsch",
            "Français",
            "Español",
            "Italiano",
            "Português",
            "Suomi",
            "中文（简体）",
            "中文（台湾）",
            "日本語",
            "한국어",
        };
        int[] dialects = {
            -1, // auto
            LanguageDetector.DIALECT_EN_US,
            LanguageDetector.DIALECT_EN_GB,
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.DIALECT_FR_FR,
            LanguageDetector.DIALECT_ES_ES,
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_PT_BR,
            LanguageDetector.DIALECT_FI_FI,
            LanguageDetector.DIALECT_ZH_CN,
            LanguageDetector.DIALECT_ZH_TW,
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_KO_KR,
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("语言环境")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        LanguageDetector.setDetectionEnabled(true);
                    } else {
                        LanguageDetector.setDetectionEnabled(false);
                        LanguageDetector.setFixedDialect(dialects[which]);
                    }
                    saveLanguageSettings();
                    refreshLangButton(langBtn);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 语言检测设置对话框 */
    private void showDetectionSettingsDialog() {
        String[] items = {
            "默认语言",
            "检测的语言",
            "中文口音",
            "英文口音",
            "西班牙语口音",
            "法语口音",
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("语言检测设置")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showDefaultLanguageDialog(); break;
                        case 1: showDetectionLanguagesDialog(); break;
                        case 2: showChineseDialectDialog(); break;
                        case 3: showEnglishAccentDialog(); break;
                        case 4: showSpanishDialectDialog(); break;
                        case 5: showFrenchDialectDialog(); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 默认语言（单选，选择后立即返回）。语言只到"英语/中文"级别，
     *  英美/简繁等口音由口音设置控制，此处不展开。 */
    private void showDefaultLanguageDialog() {
        String[] items = {
            "不指定",
            "英语",
            "德语",
            "法语",
            "西班牙语",
            "意大利语",
            "葡萄牙语",
            "芬兰语",
            "中文",
            "日语",
            "韩语",
        };
        // values[i] 对应 items[i] 的 dialect（英语/中文用当前口音方言）
        final int[] values = {
            LanguageDetector.DEFAULT_UNSPECIFIED,
            LanguageDetector.getEnglishDialect(),     // 英语（口音设置决定英美）
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.getFrenchDialect(),      // 法语（口音设置决定法加法）
            LanguageDetector.getSpanishDialect(),     // 西班牙语（口音设置决定西墨）
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_PT_BR,
            LanguageDetector.DIALECT_FI_FI,
            LanguageDetector.getChineseDialect(),     // 中文（口音设置决定简繁）
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_KO_KR,
        };

        int cur = LanguageDetector.getDefaultLanguage();
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (cur == values[i]) { checked = i; break; }
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("默认语言")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    LanguageDetector.setDefaultLanguage(values[which]);
                    saveLanguageSettings();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 检测的语言（多选） */
    private void showDetectionLanguagesDialog() {
        // 当前白名单；默认只选中英文、中文
        Set<String> enabled = LanguageDetector.getEnabledLanguages();
        boolean[] checked = new boolean[LanguageDetector.ALL_LANG_CODES.length];
        for (int i = 0; i < LanguageDetector.ALL_LANG_CODES.length; i++) {
            String code = LanguageDetector.ALL_LANG_CODES[i];
            checked[i] = (enabled != null && enabled.contains(code));
        }
        String[] names = LanguageDetector.ALL_LANG_NAMES;
        // 实时同步勾选状态到临时数组
        final boolean[] finalChecked = checked.clone();
        new android.app.AlertDialog.Builder(this)
                .setTitle("检测的语言")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                    finalChecked[which] = isChecked;
                })
                .setPositiveButton("确定", (d, w) -> {
                    Set<String> newEnabled = new HashSet<>();
                    for (int i = 0; i < finalChecked.length; i++) {
                        if (finalChecked[i]) {
                            newEnabled.add(LanguageDetector.ALL_LANG_CODES[i]);
                        }
                    }
                    // 立即生效（setEnabledLanguages 内部会重建 Lingua）
                    LanguageDetector.setEnabledLanguages(newEnabled);
                    saveLanguageSettings();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 中文口音 */
    private void showChineseDialectDialog() {
        String[] items = {"简体（zh-CN）", "台湾（zh-TW）"};
        int cur = LanguageDetector.getChineseDialect();
        int checked = (cur == LanguageDetector.DIALECT_ZH_TW) ? 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("中文口音")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    LanguageDetector.setChineseDialect(
                            which == 1 ? LanguageDetector.DIALECT_ZH_TW : LanguageDetector.DIALECT_ZH_CN);
                    saveLanguageSettings();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 英文口音 */
    private void showEnglishAccentDialog() {
        String[] items = {"美式英语 (en-US)", "英式英语 (en-GB)"};
        int cur = LanguageDetector.getEnglishDialect();
        int checked = (cur == LanguageDetector.DIALECT_EN_GB) ? 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("英文口音")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    LanguageDetector.setEnglishDialect(
                            which == 1 ? LanguageDetector.DIALECT_EN_GB : LanguageDetector.DIALECT_EN_US);
                    saveLanguageSettings();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 西班牙语口音 */
    private void showSpanishDialectDialog() {
        String[] items = {"西班牙西班牙语 (es-ES)", "墨西哥西班牙语 (es-MX)"};
        int cur = LanguageDetector.getSpanishDialect();
        int checked = (cur == LanguageDetector.DIALECT_ES_MX) ? 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("西班牙语口音")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    LanguageDetector.setSpanishDialect(
                            which == 1 ? LanguageDetector.DIALECT_ES_MX : LanguageDetector.DIALECT_ES_ES);
                    saveLanguageSettings();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 法语口音 */
    private void showFrenchDialectDialog() {
        String[] items = {"法国法语 (fr-FR)", "加拿大法语 (fr-CA)"};
        int cur = LanguageDetector.getFrenchDialect();
        int checked = (cur == LanguageDetector.DIALECT_FR_CA) ? 1 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("法语口音")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    LanguageDetector.setFrenchDialect(
                            which == 1 ? LanguageDetector.DIALECT_FR_CA : LanguageDetector.DIALECT_FR_FR);
                    saveLanguageSettings();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // SharedPreferences 持久化
    private static final String PREFS_NAME = "vvtts_lang_settings";

    private void saveLanguageSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        android.content.SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("detection_enabled", LanguageDetector.isDetectionEnabled());
        e.putInt("fixed_dialect", LanguageDetector.getFixedDialect());
        e.putInt("chinese_dialect", LanguageDetector.getChineseDialect());
        e.putInt("english_dialect", LanguageDetector.getEnglishDialect());
        e.putInt("spanish_dialect", LanguageDetector.getSpanishDialect());
        e.putInt("french_dialect", LanguageDetector.getFrenchDialect());
        e.putInt("default_language", LanguageDetector.getDefaultLanguage());
        Set<String> enabled = LanguageDetector.getEnabledLanguages();
        if (enabled != null) {
            e.putStringSet("enabled_langs", enabled);
        } else {
            e.remove("enabled_langs");
        }
        e.commit();
    }

    private void restoreLanguageSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        LanguageDetector.setDetectionEnabled(prefs.getBoolean("detection_enabled", true));
        LanguageDetector.setFixedDialect(prefs.getInt("fixed_dialect", LanguageDetector.DIALECT_EN_US));
        LanguageDetector.setChineseDialect(prefs.getInt("chinese_dialect", LanguageDetector.DIALECT_ZH_CN));
        LanguageDetector.setEnglishDialect(prefs.getInt("english_dialect", LanguageDetector.DIALECT_EN_US));
        LanguageDetector.setSpanishDialect(prefs.getInt("spanish_dialect", LanguageDetector.DIALECT_ES_ES));
        LanguageDetector.setFrenchDialect(prefs.getInt("french_dialect", LanguageDetector.DIALECT_FR_FR));
        LanguageDetector.setDefaultLanguage(prefs.getInt("default_language", LanguageDetector.DEFAULT_UNSPECIFIED));
        // 检测语言白名单：默认只中英文（首次安装或未设置过）
        Set<String> enabled;
        if (prefs.contains("enabled_langs")) {
            enabled = prefs.getStringSet("enabled_langs", null);
        } else {
            enabled = new HashSet<>(Arrays.asList("en", "zh"));
        }
        LanguageDetector.setEnabledLanguages(enabled);
    }

}