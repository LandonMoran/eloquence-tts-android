package com.xw.vvtts.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 发音角色配置。
 * 预置 8 个 Kona 角色，支持按角色自定义覆盖 8 个 ECI voice 参数。
 * 默认值来自 KonaVoice（KonaVoicePresets.csv 权威数据），
 * 用户自定义值存 SharedPreferences，恢复默认即删除覆盖。
 */
public class VoiceProfile {
    private static final String PREFS = "vvtts_voice_profile";
    private static final String KEY_PRESET = "preset";   // 当前选中的预置角色 1-8

    // ECI voice param 权威编号（eci.h）
    public static final int PARAM_GENDER = 0;        // 性别 0=男 1=女（二选一）
    public static final int PARAM_HEAD_SIZE = 1;     // 头部大小 0-100
    public static final int PARAM_PITCH_BASE = 2;    // 音调基线 40-120
    public static final int PARAM_PITCH_FLUC = 3;    // 抖动 0-100
    public static final int PARAM_ROUGHNESS = 4;     // 粗糙度 0-100
    public static final int PARAM_BREATHINESS = 5;   // 沙哑度/气声 0-100
    public static final int PARAM_SPEED = 6;         // 语速 0-250
    public static final int PARAM_VOLUME = 7;        // 音量 0-100

    // 弹编辑对话框时展示的音色滑杆参数（gender 单独单选做官方原厂切换；
    // pitchBase/speed/volume 由主界面的音调/语速/音量控制，编辑界面不重复暴露）
    public static final int[] EDITABLE_PARAMS = {
        PARAM_HEAD_SIZE, PARAM_PITCH_FLUC, PARAM_ROUGHNESS, PARAM_BREATHINESS,
    };

    // 预置角色英文名（苹果 Kona 8 角色）
    public static final String[] PRESET_NAMES = {
        "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy",
    };

    public static final String[] PRESET_NAMES_CN = {
        "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy",
    };

    private final SharedPreferences prefs;

    public VoiceProfile(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getPreset() { return prefs.getInt(KEY_PRESET, 1); }

    public void setPreset(int n) {
        if (n < 1) n = 1;
        if (n > 8) n = 8;
        prefs.edit().putInt(KEY_PRESET, n).commit();
    }

    /** 某角色某参数是否有自定义覆盖 */
    public boolean hasOverride(int preset, int param) {
        return prefs.contains(overrideKey(preset, param));
    }

    /** 取某角色的参数值：优先自定义覆盖，否则回落 KonaVoice 默认 */
    public int getParam(int preset, int param) {
        String key = overrideKey(preset, param);
        if (prefs.contains(key)) return prefs.getInt(key, 0);
        return KonaVoice.byPreset(preset).param(param);
    }

    /** 设置某角色的参数自定义覆盖 */
    public void setParam(int preset, int param, int value) {
        prefs.edit().putInt(overrideKey(preset, param), value).commit();
    }

    /** 恢复某角色的默认（删除该角色全部自定义覆盖） */
    public void resetPreset(int preset) {
        SharedPreferences.Editor e = prefs.edit();
        for (int p = 0; p < 8; p++) {
            e.remove(overrideKey(preset, p));
        }
        e.commit();
    }

    private static String overrideKey(int preset, int param) {
        return "override_" + preset + "_" + param;
    }

    /** 参数中文名 */
    public static String paramName(int param) {
        switch (param) {
            case PARAM_GENDER: return "性别";
            case PARAM_HEAD_SIZE: return "头部大小";
            case PARAM_PITCH_BASE: return "音调";
            case PARAM_PITCH_FLUC: return "情感起伏";
            case PARAM_ROUGHNESS: return "粗糙度";
            case PARAM_BREATHINESS: return "沙哑度";
            case PARAM_SPEED: return "语速";
            case PARAM_VOLUME: return "音量";
            default: return "参数" + param;
        }
    }
}