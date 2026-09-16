package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 发音角色配置。
 * 预置 8 个 Kona 角色，支持按角色自定义覆盖 8 个 ECI voice 参数。
 * 默认值来自 KonaVoice（KonaVoicePresets.csv 权威数据），
 * 用户自定义值存 SharedPreferences，恢复默认即删除覆盖。
 */
class VoiceProfile(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val preset: Int
        get() = prefs.getInt(KEY_PRESET, 1)

    fun setPreset(n: Int) {
        var v = n
        if (v < 1) v = 1
        if (v > 8) v = 8
        prefs.edit().putInt(KEY_PRESET, v).commit()
    }

    /** 某角色某参数是否有自定义覆盖 */
    fun hasOverride(preset: Int, param: Int): Boolean {
        return prefs.contains(overrideKey(preset, param))
    }

    /** 取某角色的参数值：优先自定义覆盖，否则回落 KonaVoice 默认 */
    fun getParam(preset: Int, param: Int): Int {
        val key = overrideKey(preset, param)
        if (prefs.contains(key)) return prefs.getInt(key, 0)
        return KonaVoice.byPreset(preset).param(param)
    }

    /** 设置某角色的参数自定义覆盖 */
    fun setParam(preset: Int, param: Int, value: Int) {
        prefs.edit().putInt(overrideKey(preset, param), value).commit()
    }

    /** 恢复某角色的默认（删除该角色全部自定义覆盖） */
    fun resetPreset(preset: Int) {
        val e = prefs.edit()
        for (p in 0 until 8) {
            e.remove(overrideKey(preset, p))
        }
        e.commit()
    }

    companion object {
        private const val PREFS = "vvtts_voice_profile"
        private const val KEY_PRESET = "preset"   // 当前选中的预置角色 1-8

        // ECI voice param 权威编号（eci.h）
        const val PARAM_GENDER = 0        // 性别 0=男 1=女（二选一）
        const val PARAM_HEAD_SIZE = 1     // 头部大小 0-100
        const val PARAM_PITCH_BASE = 2    // 音调基线 40-120
        const val PARAM_PITCH_FLUC = 3    // 抖动 0-100
        const val PARAM_ROUGHNESS = 4     // 粗糙度 0-100
        const val PARAM_BREATHINESS = 5   // 沙哑度/气声 0-100
        const val PARAM_SPEED = 6         // 语速 0-250
        const val PARAM_VOLUME = 7        // 音量 0-100

        // 弹编辑对话框时展示的音色滑杆参数（gender 单独单选做官方原厂切换；
        // pitchBase/speed/volume 由主界面的音调/语速/音量控制，编辑界面不重复暴露）
        val EDITABLE_PARAMS = intArrayOf(
            PARAM_HEAD_SIZE, PARAM_PITCH_FLUC, PARAM_ROUGHNESS, PARAM_BREATHINESS,
        )

        // 预置角色英文名（苹果 Kona 8 角色）
        val PRESET_NAMES = arrayOf(
            "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy",
        )

        val PRESET_NAMES_CN = arrayOf(
            "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy",
        )

        private fun overrideKey(preset: Int, param: Int): String {
            return "override_$preset" + "_" + param
        }

        /** 参数中文名 */
        fun paramName(param: Int): String {
            return when (param) {
                PARAM_GENDER -> "性别"
                PARAM_HEAD_SIZE -> "头部大小"
                PARAM_PITCH_BASE -> "音调"
                PARAM_PITCH_FLUC -> "情感起伏"
                PARAM_ROUGHNESS -> "粗糙度"
                PARAM_BREATHINESS -> "沙哑度"
                PARAM_SPEED -> "语速"
                PARAM_VOLUME -> "音量"
                else -> "参数$param"
            }
        }
    }
}