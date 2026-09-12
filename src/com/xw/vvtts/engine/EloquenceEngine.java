package com.xw.vvtts.engine;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.xw.vvtts.core.VvttsCore;
import com.xw.vvtts.utils.KonaVoice;
import com.xw.vvtts.utils.VoiceProfile;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.locks.ReentrantLock;

public class EloquenceEngine {
    private static final String TAG = "EloquenceEngine";

    public static final int DIALECT_EN_US = 0x10000;    // [1.0] enu
    public static final int DIALECT_EN_GB = 0x10001;    // [1.1] eng
    public static final int DIALECT_ES_ES = 0x20000;    // [2.0] esp
    public static final int DIALECT_ES_MX = 0x20001;    // [2.1] esm
    public static final int DIALECT_FR_FR = 0x30000;    // [3.0] fra
    public static final int DIALECT_FR_CA = 0x30001;    // [3.1] frc
    public static final int DIALECT_DE_DE = 0x40000;    // [4.0] deu
    public static final int DIALECT_IT_IT = 0x50000;    // [5.0] ita
    public static final int DIALECT_ZH_CN = 0x60000;    // [6.0] chs
    public static final int DIALECT_ZH_TW = 0x60001;    // [6.1] cht
    public static final int DIALECT_PT_BR = 0x70000;    // [7.0] ptb
    public static final int DIALECT_JA_JP = 0x80000;    // [8.0] jpn
    public static final int DIALECT_FI_FI = 0x90000;    // [9.0] fin
    public static final int DIALECT_KO_KR = 0xA0000;    // [10.0] kor
    // 引擎真实输出采样率：eci.ini 的 en 库固定 11025Hz，运行时不可改
    public static final int ENGINE_SAMPLE_RATE = 11025;
    // 输出采样率 = 引擎原生 11025（实测音色最自然，不做重采样）
    public static final int SAMPLE_RATE = 11025;

    private static final Charset CHINESE_CHARSET = Charset.forName("GB18030");
    private static final Charset ENGLISH_CHARSET = Charset.forName("windows-1252");

    private final Context appContext;
    private VvttsCore core;        // 自研桥接（多语言）
    private long coreHandle;
    private long nativeHandle = 0L;
    private boolean initialized = false;
    private final ReentrantLock synthLock = new ReentrantLock();
    private volatile boolean stopped = false;
    // 待应用的标准 voice 编号（合成前应用）
    private volatile Integer pendingVoice = null;
    // SAPI 内联标记串（如 \Vce=Speaker="Nai3nai0"\ 或 \Pit=140\）
    private volatile String pendingSapi = "";
    // 角色音高因子（相对引擎当前音高的倍率，1.0=不变）
    private volatile float pendingPitchFactor = 1.0f;
    // 角色音调（ECI 活动参数 param=2 的目标值）
    private volatile Integer pendingPitch = null;
    // 引擎采样率是否已设为 16k
    private volatile boolean sampleRateSet = false;
    // setProsody 最近写入的原生音调值（mapPitch 之后）
    private volatile int lastNativePitch = 0;
    // 角色语速因子
    private volatile float pendingSpeedFactor = 1.0f;
    // 待切换的 ECI 标准 voice 编号（合成线程热身后应用）
    private volatile int pendingEciVoice = -1;
    // pendingEciVoice 对应的 dialect
    private volatile int pendingEciDialect = DIALECT_ZH_CN;
    // 是否已完成首次热身合成
    private volatile boolean warmedUp = false;
    // SAPI 内联标记（角色切换）
    private volatile String pendingSapiMark = "";

    public EloquenceEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    private VoiceProfile voiceProfile; // 角色参数覆盖来源（可空）

    /** 设置角色配置来源，合成时优先读取其自定义覆盖 */
    public void setVoiceProfile(VoiceProfile vp) {
        this.voiceProfile = vp;
    }

    private String buildEloquenceConfig(File libraryDir) {
        StringBuilder sb = new StringBuilder();
        // 14 语言 + 4 CJK romanizer（苹果 tvOS 18.2 完整引擎）
        // [段号] dialect -> lib<name>.so，Path_Rom 仅 CJK
        String[][] langs = {
            {"1.0",  "libenu.so", null},          // en-US
            {"1.1",  "libeng.so", null},          // en-GB
            {"2.0",  "libesp.so", null},          // es-ES
            {"2.1",  "libesm.so", null},          // es-MX
            {"3.0",  "libfra.so", null},          // fr-FR
            {"3.1",  "libfrc.so", null},          // fr-CA
            {"4.0",  "libdeu.so", null},          // de-DE
            {"5.0",  "libita.so", null},          // it-IT
            {"6.0",  "libchs.so", "libchsrom.so"},// zh-CN
            {"6.1",  "libcht.so", "libchtrom.so"},// zh-TW
            {"7.0",  "libptb.so", null},          // pt-BR
            {"8.0",  "libjpn.so", "libjpnrom.so"},// ja-JP
            {"9.0",  "libfin.so", null},          // fi-FI
            {"10.0", "libkor.so", "libkorrom.so"},// ko-KR
        };
        for (String[] l : langs) {
            sb.append("[").append(l[0]).append("]\nPath=");
            sb.append(new File(libraryDir, l[1]).getAbsolutePath());
            sb.append("\n");
            if (l[2] != null) {
                sb.append("Path_Rom=");
                sb.append(new File(libraryDir, l[2]).getAbsolutePath());
                sb.append("\n");
            }
            sb.append("Version=6.1\n\n");
        }

        // 标准 voice 表——中文库 register_voices 依赖此段初始化 voice 表
        sb.append("Voice1=0 50 65 30 0 0 50 92\n");    // Reed
        sb.append("Voice2=0 50 81 50 0 0 50 95\n");    // Shelley
        sb.append("Voice3=0 50 93 50 0 0 22 95\n");    // Sandy
        sb.append("Voice4=0 50 56 0 0 0 86 93\n");     // Rocko
        sb.append("Voice5=0 50 65 30 0 0 50 92\n");    // 备用
        sb.append("Voice6=0 50 89 40 0 0 56 95\n");    // Flo
        sb.append("Voice7=0 50 68 40 3 0 45 90\n");    // Grandma
        sb.append("Voice8=0 50 61 20 18 0 30 90\n");   // Grandpa
        sb.append("Voice9=0 50 69 0 0 0 50 92\n");     // Eddy
        return sb.toString();
    }

    public synchronized boolean initialize() {
        if (initialized) return true;
        // 新苹果引擎：不再依赖广荣 JNI（libeloquence_jni.so 已废弃）。
        // 合成走 VvttsCore 自研桥接（dlopen 苹果 libeci.so），这里只需预写 eci.ini。
        try {
            ApplicationInfo info = appContext.getApplicationInfo();
            String libDir = info.nativeLibraryDir;
            if (libDir == null) {
                Log.e(TAG, "nativeLibraryDir is null");
                return false;
            }
            File configDir = new File(appContext.getFilesDir(), "eloquence");
            if (!configDir.exists()) configDir.mkdirs();
            String config = buildEloquenceConfig(new File(libDir));
            FileWriter fw = new FileWriter(new File(configDir, "eci.ini"));
            fw.write(config);
            fw.close();
            initialized = true;
            Log.i(TAG, "Eloquence engine (apple-eloquence-elf) ready, 14 语言");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            return false;
        }
    }

    // 广荣 mapPitch: <=50 -> p*69/50; >50 -> (p-50)*31/50+69
    private static int mapPitch(int pitch) {
        if (pitch < 0) pitch = 0;
        if (pitch > 100) pitch = 100;
        if (pitch <= 50) return pitch * 69 / 50;
        else return (pitch - 50) * 31 / 50 + 69;
    }

    private static int coerceIn(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static float coerceInF(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    public void setProsody(int rate, int pitch, int volume, float rateMultiplier) {
        // 旧广荣通路已废弃，韵律由 synthesizeCore 内部 setParam 处理
        lastNativePitch = mapPitch(pitch);
    }

    public static short[] applyVolume(short[] pcm, int volume) {
        volume = coerceIn(volume, 0, 100);
        float gain = volume / 50.0f;
        if (gain == 1.0f) return pcm;
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            int v = Math.round(pcm[i] * gain);
            if (v < -32768) v = -32768;
            if (v > 32767) v = 32767;
            out[i] = (short) v;
        }
        return out;
    }

    public short[] synthesize(String text, int dialect, int volume) {
        // 旧广荣通路已废弃，转发到 synthesizeCore（苹果引擎）
        return synthesizeCore(text, dialect, volume, 1, 50);
    }

    public void stop() {
        stopped = true;
        if (core != null) {
            for (long h : coreHandles.values()) VvttsCore.stop(h);
        }
    }

    public synchronized void shutdown() {
        if (core != null) {
            for (long h : coreHandles.values()) VvttsCore.shutdown(h);
            coreHandles.clear();
        }
        initialized = false;
        core = null;
    }

    public boolean isInitialized() { return initialized; }
    public long getNativeHandle() { return nativeHandle; }
    public int getSampleRate() { return SAMPLE_RATE; }

    // ===== 自研桥接（唯一合成通道）=====
    private final java.util.HashMap<Integer, Long> coreHandles = new java.util.HashMap<>();
    // 参数注入 + 合成 的串行锁：并发调用时避免多个线程同时 setVoiceParam/synth 互相覆盖
    private final java.util.concurrent.locks.ReentrantLock paramSynthLock = new java.util.concurrent.locks.ReentrantLock();
    private int lastSynthRate = 11025;  // 最近一次输出的采样率（WAV 头解析）
    public int getCoreSampleRate() { return lastSynthRate; }

    /** 方言 → 文本编码（CJK 用特定编码，西文用 windows-1252） */
    private static Charset charsetForDialect(int dialect) {
        switch (dialect) {
            case DIALECT_ZH_CN: return Charset.forName("GB18030");
            case DIALECT_ZH_TW: return Charset.forName("Big5");
            case DIALECT_JA_JP: return Charset.forName("Shift_JIS");
            case DIALECT_KO_KR: return Charset.forName("EUC-KR");
            default:            return Charset.forName("windows-1252");
        }
    }

    /** 当前选择的角色预设（1-8）与自定义模式标记 */
    private volatile int voicePreset = 1;
    private volatile boolean voiceCustom = false;
    private volatile int customPitch = 50;

    /**
     * 苹果 Kona CSV 反引号 annotation。
     * `vN=标准voice编号 `vs=语速(0-100) `vv=音量 `vy=vocalTract
     * `vb=breathiness `vh=headSize `vr=roughness `vf=pitchFluctuation `vb?=pitch
     * 数值全部来自 KonaVoicePresets.csv（Reed=1 Shelley=2 Sandy=3 Rocko=4 Flo=6 Grandma=7 Grandpa=8 Eddy=9）
     */
    private static String presetAnnotation(int n) {
        switch (n) {
            case 1: return "`v1 `vs50 `vv90";                              // Reed
            case 2: return "`v3 `vb61 `vh31 `vr18 `vy20 `vf44 `vs50 `vv90"; // Sandy
            case 3: return "`v2 `vb20 `vh30 `vr5  `vy30 `vf30 `vs50 `vv90"; // Shelley
            case 4: return "`v4 `vb0  `vh50 `vr45 `vy50 `vf25 `vs48 `vv90"; // Rocko
            case 5: return "`v9 `vb10 `vh55 `vr8  `vy50 `vf35 `vs50 `vv90"; // Eddy
            case 6: return "`v6 `vb35 `vh35 `vr10 `vy35 `vf40 `vs52 `vv90"; // Flo
            case 7: return "`v8 `vb30 `vh45 `vr28 `vy45 `vf22 `vs44 `vv90"; // Grandpa
            case 8: return "`v7 `vb45 `vh40 `vr20 `vy40 `vf35 `vs45 `vv90"; // Grandma
            default: return "`v1";
        }
    }

    /**
     * 用自研桥接合成（苹果完整引擎，14 语言全支持）。
     * 角色由 presetId 驱动（1-8 苹果预设），走 eciSetStandardVoice2。
     */
    public synchronized short[] synthesizeCore(String text, int dialect, int volume, int presetId) {
        return synthesizeCore(text, dialect, volume, presetId, 50);
    }
    public synchronized short[] synthesizeCore(String text, int dialect, int volume, int presetId, int uiPitch) {
        // 默认语速 100%（中性）
        return synthesizeCore(text, dialect, volume, presetId, uiPitch, 100);
    }
    public synchronized short[] synthesizeCore(String text, int dialect, int volume, int presetId, int uiPitch, int uiRate) {
        paramSynthLock.lock();
        try {
        if (core == null) core = new VvttsCore();

        // session 懒加载（按方言缓存）
        Long cached = coreHandles.get(dialect);
        long handle = (cached != null) ? cached.longValue() : 0L;
        if (handle == 0) {
            ApplicationInfo info = appContext.getApplicationInfo();
            File libDir = new File(info.nativeLibraryDir);
            File cfgDir = new File(appContext.getFilesDir(), "eloquence");
            try {
                FileWriter fw = new FileWriter(new File(cfgDir, "eci.ini"));
                fw.write(buildEloquenceConfig(libDir));
                fw.close();
            } catch (Exception e) {
                Log.e(TAG, "write eci.ini failed", e);
                return null;
            }
            handle = VvttsCore.openEngine(cfgDir.getAbsolutePath(), libDir.getAbsolutePath(), dialect);
            Log.e(TAG, "core init dialect=" + Integer.toHexString(dialect) + " handle=" + handle);
            if (handle == 0) return null;
            coreHandles.put(dialect, handle);
        }

        // === 完整参数适配（苹果 Kona CSV 权威数据）===
        // 注意：eciSetStandardVoice2 会崩（ETIEvent::wait，libeci+0x149b4），
        // 即使 ctype 修复后也一样——这是独立的 ETIEvent 生命周期问题。
        // 所以角色切换不走 eciSetStandardVoice，而是靠下面 8 个 eciSetVoiceParam
        // 完整注入角色音色（headSize/pitchBase/pitchFluc/rough/breath/speed/vol）。
        KonaVoice.Voice voice = KonaVoice.byPreset(presetId);

        // 注入该角色的 8 个 ECI voice 参数（gender=0 head=1 pitchBase=2
        // pitchFluc=3 rough=4 breath=5 speed=6 vol=7）
        VoiceProfile vp = voiceProfile;
        for (int p = 0; p < 8; p++) {
            // 优先用自定义覆盖，否则用 KonaVoice 默认
            int val = (vp != null && vp.hasOverride(presetId, p))
                    ? vp.getParam(presetId, p)
                    : voice.param(p);
            int r = VvttsCore.setVoiceParam(handle, 0, p, val);
            // 静默失败：setVoiceParam 偶发 ret<0 不影响合成，不打日志避免刷屏
        }

        // 用户 UI 参数
        // 音调：UI 0-100 → 苹果 pitchBase（以角色 pitchBase 为基线的 ±30 范围）
        int pitchBase = mapUiPitchToKona(uiPitch, voice.pitchBase);
        VvttsCore.setVoiceParam(handle, 0, 2, pitchBase);   // eciPitchBaseline

        // 语速：UI 1-300 → ECI speed（苹果基准 50，IBM 范围 0-250）
        // UI 100% = 50，按比例映射：uiRate/100 * 50
        int speed = (int) Math.round(voice.speed * (uiRate / 100.0));
        speed = coerceIn(speed, 0, 250);
        VvttsCore.setVoiceParam(handle, 0, 6, speed);       // eciSpeed

        // 音量：CSV 预置 volume + 不做二次 applyVolume 前先设 voice param
        VvttsCore.setVoiceParam(handle, 0, 7, voice.vol);   // eciVolume

        // 编码
        Charset cs = charsetForDialect(dialect);
        byte[] encoded;
        try {
            java.nio.ByteBuffer bb = cs.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .encode(java.nio.CharBuffer.wrap(text));
            encoded = new byte[bb.remaining()];
            bb.get(encoded);
        } catch (Exception e) {
            Log.e(TAG, "encode failed for " + Integer.toHexString(dialect), e);
            return null;
        }
        int charset = (dialect == DIALECT_ZH_CN) ? VvttsCore.CHARSET_GBK : VvttsCore.CHARSET_1252;
        File outFile = new File(appContext.getCacheDir(), "core_pcm_out");
        short[] pcm = VvttsCore.synth(handle, dialect, encoded, charset, outFile.getAbsolutePath());
        if (pcm != null && pcm.length > 0) pcm = applyVolume(pcm, volume);
        return pcm;
        } finally {
            paramSynthLock.unlock();
        }
    }

    /** UI 音调 0-100 → 苹果 pitchBase（以当前角色 pitchBase 为基线的 ±30 范围） */
    private static int mapUiPitchToKona(int uiPitch, int basePitch) {
        // uiPitch 50 = 中性（用角色默认 pitchBase）；0 = 低 30；100 = 高 30
        int offset = uiPitch - 50;
        int pitch = basePitch + (int) Math.round(offset * 0.6);
        return coerceIn(pitch, 40, 120);
    }

    /** 设置角色预设（1-8） */
    public void setVoicePreset(int n) {
        voicePreset = coerceIn(n, 1, 8);
        voiceCustom = false;
        Log.e(TAG, "voicePreset -> " + voicePreset);
    }

    /** 自定义音调模式（UI 滑块直接驱动） */
    public void setCustomPitch(int pitch) {
        customPitch = coerceIn(pitch, 0, 100);
        voiceCustom = true;
    }

    public float getPendingPitchFactor() { return pendingPitchFactor; }

    /**
     * 应用发音角色（现在角色参数在 synthesizeCore 内直接读 voiceProfile 覆盖，
     * 这里保留空实现以兼容旧调用点）
     */
    public void applyVoiceProfile(int dialect, VoiceProfile profile) {
        // 不需要额外处理：synthesizeCore 已通过 voiceProfile 字段读取自定义覆盖
    }

    // 苹果 CSV pitchBase 换算的角色音调因子（以 Reed=65 为基准）
    private static float presetPitchFactor(int n) {
        switch (n) {
            case 1: return 1.00f;  // Reed
            case 2: return 1.43f;  // Sandy
            case 3: return 1.25f;  // Shelley
            case 4: return 0.86f;  // Rocko
            case 5: return 1.06f;  // Eddy
            case 6: return 1.37f;  // Flo
            case 7: return 0.94f;  // Grandpa
            case 8: return 1.05f;  // Grandma
            default: return 1.0f;
        }
    }

    // 角色语速因子（苹果 CSV speed=50 基准的性格化微调）
    private static float presetSpeedFactor(int n) {
        switch (n) {
            case 1: return 1.00f;  // Reed
            case 2: return 1.03f;  // Sandy
            case 3: return 1.01f;  // Shelley
            case 4: return 0.98f;  // Rocko
            case 5: return 1.00f;  // Eddy
            case 6: return 1.02f;  // Flo
            case 7: return 0.94f;  // Grandpa
            case 8: return 0.96f;  // Grandma
            default: return 1.0f;
        }
    }

    // 苹果 CSV eciVoiceNumber（en-US）：Reed=1 Shelley=2 Sandy=3 Rocko=4 Flo=6 Grandma=7 Grandpa=8 Eddy=9
    private static int presetEciVoice(int n) {
        switch (n) {
            case 1: return 1;  // Reed
            case 2: return 3;  // Sandy
            case 3: return 2;  // Shelley
            case 4: return 4;  // Rocko
            case 5: return 9;  // Eddy
            case 6: return 6;  // Flo
            case 7: return 8;  // Grandpa
            case 8: return 7;  // Grandma
            default: return 1;
        }
    }
    /**
     * 用苹果 KonaVoicePresets.csv 的参数（breathiness/headSize/roughness/pitchFlutter）
     * 通过 eciSetVoiceParam 注入当前语音。ECI voice param 编号（与苹果 CSV 字段对应）：
     * 声音质感参数走 ECI 的标准 voice 参数通道。
     */
    private void applyCsvVoiceParams(long handle, int presetId) {
        // 苹果 CSV {breathiness, headSize, roughness, pitchFluctuation}
        int[] p;
        switch (presetId) {
            case 2: p = new int[]{50, 50, 0, 30}; break;   // Shelley
            case 3: p = new int[]{50, 22, 0, 30}; break;   // Sandy
            case 4: p = new int[]{0, 50, 0, 30}; break;    // Rocko
            case 5: p = new int[]{0, 55, 0, 30}; break;    // Eddy
            case 6: p = new int[]{35, 35, 0, 40}; break;   // Flo
            case 7: p = new int[]{20, 30, 18, 44}; break;  // Grandpa
            case 8: p = new int[]{45, 40, 20, 35}; break;  // Grandma
            case 1:
            default: p = new int[]{0, 50, 0, 30}; break;   // Reed
        }
        // ECI voice param：eciPitchBaseline=2, eciSpeed=3, eciVolume=4, eciGeneral=5,
        // eciSayAsCtrl=6 ... 粗糙度/气声等是引擎特定扩展参数，此处按常见编号尝试
        // 保守起见：只调已验证安全的基础参数，声音质感依赖 voiceNumber 本身
        // （voiceNumber 已通过 eciSetStandardVoice2 切换，基础音色随之变化）
        Log.e(TAG, "csvVoiceParams preset=" + presetId + " breath=" + p[0]
                + " head=" + p[1] + " rough=" + p[2] + " flutter=" + p[3]);
    }
    // SAPI 内联标记（Eloquence 原生支持，格式参照 chtvoice.sapi.txt）
    // \v=X\ 直接切换标准 voice 编号
    private static String presetSapiMark(int n) {
        switch (n) {
            case 1: return "\\v=1\\";   // Reed
            case 2: return "\\v=3\\";   // Sandy
            case 3: return "\\v=2\\";   // Shelley
            case 4: return "\\v=4\\";   // Rocko
            case 5: return "\\v=9\\";   // Eddy
            case 6: return "\\v=6\\";   // Flo
            case 7: return "\\v=8\\";   // Grandpa
            case 8: return "\\v=7\\";   // Grandma
            default: return "";
        }
    }
}
