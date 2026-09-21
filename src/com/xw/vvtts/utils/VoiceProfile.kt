package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Voice profile configuration.
 * 8 preset Kona voices;; per-role custom overrides of the 8 ECI voice params.
 * Defaults come from KonaVoice (KonaVoicePresets.csv is the source of truth),
 * user custom values live in SharedPreferences;; reset removes the overrides.
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

    /** Whether the given param has a custom override for this preset */
    fun hasOverride(preset: Int, param: Int): Boolean {
        return prefs.contains(overrideKey(preset, param))
    }

    /** Get a preset's param value: custom override first, else KonaVoice default */
    fun getParam(preset: Int, param: Int): Int {
        val key = overrideKey(preset, param)
        if (prefs.contains(key)) return prefs.getInt(key, 0)
        return KonaVoice.byPreset(preset).param(param)
    }

    /** Set a custom override for a preset param */
    fun setParam(preset: Int, param: Int, value: Int) {
        prefs.edit().putInt(overrideKey(preset, param), value).commit()
    }

    /** Restore a preset's defaults (delete all its custom overrides) */
    fun resetPreset(preset: Int) {
        val e = prefs.edit()
        for (p in 0 until 8) {
            e.remove(overrideKey(preset, p))
        }
        e.commit()
    }

    companion object {
        private const val PREFS = "vvtts_voice_profile"
        private const val KEY_PRESET = "preset"   // Currently selected preset role 1-8

        // ECI voice param canonical ids (eci.h)
        const val PARAM_GENDER = 0        // Gender:  0=male  1=female (pick one)
        const val PARAM_HEAD_SIZE = 1     // Head size  0-100
        const val PARAM_PITCH_BASE = 2    // Pitch baseline  40-120
        const val PARAM_PITCH_FLUC =  3    // Pitch flutter  0-100
        const val PARAM_ROUGHNESS = 4     // Roughness  0-100
        const val PARAM_BREATHINESS =  5   // Breathiness  0-100
        const val PARAM_SPEED =  6         // Speed  0-250
        const val PARAM_VOLUME =  7        // Volume  0-100

        // Sliders shown in the edit dialog (gender is a separate radio — official voice switch;
        // pitchBase/speed/volume are controlled from the main screen, not duplicated here)
        val EDITABLE_PARAMS = intArrayOf(
            PARAM_HEAD_SIZE, PARAM_PITCH_FLUC, PARAM_ROUGHNESS, PARAM_BREATHINESS,
        )

        // Preset voice names (Apple Kona 8 roles)
        val PRESET_NAMES = arrayOf(
            "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy",
        )

        private fun overrideKey(preset: Int, param: Int): String {
            return "override_$preset" + "_" + param
        }

        /** Parameter display name */
        fun paramName(param: Int): String {
            return when (param) {
                PARAM_GENDER -> "Gender"
                PARAM_HEAD_SIZE -> "Head size"
                PARAM_PITCH_BASE -> "Pitch"
                PARAM_PITCH_FLUC -> "Pitch flutter"
                PARAM_ROUGHNESS -> "Roughness"
                PARAM_BREATHINESS -> "Breathiness"
                PARAM_SPEED -> "Speed"
                PARAM_VOLUME -> "Volume"
                else -> "Parameter $param"
            }
        }
    }
}