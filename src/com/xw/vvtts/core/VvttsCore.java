package com.xw.vvtts.core;

/**
 * 自研 ECI 桥接（libvvtts_core.so）v3
 * 中英文各一个 session（dialect handle），角色由反引号 annotation 驱动。
 */
public class VvttsCore {

    static {
        System.loadLibrary("vvtts_core");
    }

    public static final int CHARSET_1252 = 0;   // Windows-1252（西文）
    public static final int CHARSET_GBK = 6;    // 中文 GB18030

    static native long nativeInitEngine(String configDir, String libDir, int dialect);
    static native short[] nativeSynthesize(long handle, int dialect, byte[] text, int charsetId, String outPath);
    static native int nativeSetVoiceParam(long handle, int voice, int param, int value);
    static native int nativeGetVoiceParam(long handle, int voice, int param);
    static native int nativeSetParam(long handle, int param, int value);
    static native int nativeSetStandardVoice(long handle, int voiceNumber);
    static native void nativeShutdown(long handle);
    static native void nativeStop(long handle);

    /** 为指定 dialect 创建 session（重复调用会重建） */
    public static long openEngine(String configDir, String libDir, int dialect) {
        try {
            return nativeInitEngine(configDir, libDir, dialect);
        } catch (Throwable e) {
            android.util.Log.e("VvttsCore", "openEngine native crash", e);
            return 0;
        }
    }

    /** 合成。textBytes 已编码。返回 PCM short 数组。 */
    public static short[] synth(long handle, int dialect, byte[] text, int charsetId, String outPath) {
        try {
            return nativeSynthesize(handle, dialect, text, charsetId, outPath);
        } catch (Throwable e) {
            android.util.Log.e("VvttsCore", "synth native crash", e);
            return null;
        }
    }

    /** ECI 语音参数（voice=0 当前活动语音） */
    public static int setVoiceParam(long handle, int voice, int param, int value) {
        try {
            return nativeSetVoiceParam(handle, voice, param, value);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int getVoiceParam(long handle, int voice, int param) {
        try {
            return nativeGetVoiceParam(handle, voice, param);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 引擎参数（2=音调 5=音量 7=语速） */
    public static int setParam(long handle, int param, int value) {
        try {
            return nativeSetParam(handle, param, value);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 切换标准 voice */
    public static int setStandardVoice(long handle, int voiceNumber) {
        try {
            return nativeSetStandardVoice(handle, voiceNumber);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static void shutdown(long handle) {
        try {
            nativeShutdown(handle);
        } catch (Throwable e) {}
    }

    public static void stop(long handle) {
        try {
            nativeStop(handle);
        } catch (Throwable e) {}
    }
}