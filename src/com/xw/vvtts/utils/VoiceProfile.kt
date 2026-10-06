package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Voice profile configuration.
 * 8 preset Kona voices; per-role custom overrides of the 8 ECI voice params.
 * Defaults come from KonaVoice (KonaVoicePresets.csv is the source of truth),
 * user custom values live in SharedPreferences; reset removes the overrides.
 */
class VoiceProfile(context: Context) {
    private val store = MirroredPreferences(context, PREFS)
    private fun readMap() = store.strings()
    private fun writeBoth(block: (SharedPreferences.Editor) -> Unit) { store.edit(block) }

    val preset: Int
        get() = (readMap()[KEY_PRESET]?.toIntOrNull() ?: 1).coerceIn(1, 8)

    fun setPreset(n: Int) {
        var v = n
        if (v < 1) v = 1
        if (v > 8) v = 8
        writeBoth { it.putInt(KEY_PRESET, v) }
    }

    /** Whether the given param has a custom override for this preset */
    fun hasOverride(preset: Int, param: Int): Boolean {
        return readMap().containsKey(overrideKey(preset, param))
    }

    /** Get a preset's param value: custom override first, else KonaVoice default */
    fun getParam(preset: Int, param: Int): Int {
        val key = overrideKey(preset, param)
        val v = readMap()[key]
        val maximum = if (param == PARAM_SPEED) 250 else if (param == PARAM_GENDER) 1 else 100
        v?.toIntOrNull()?.takeIf { it in 0..maximum }?.let { return it }
        val customVoice = KonaVoice.byPreset(preset)
        return customVoice.param(param)
    }

    /** Set a custom override for a preset param */
    fun setParam(preset: Int, param: Int, value: Int) {
        writeBoth { it.putInt(overrideKey(preset, param), value) }
    }

    /** Restore a preset's defaults (delete all its custom overrides) */
    fun resetPreset(preset: Int) {
        writeBoth { e ->
            for (p in 0..7) {
                e.remove(overrideKey(preset, p))
            }
    }
    }

    companion object {
        private const val PREFS = "vvtts_voice_profile"
        private const val KEY_PRESET = "preset"   // Currently selected preset role 1-8

        // ECI voice param canonical ids (eci.h)
        const val PARAM_GENDER = 0        // Gender:  0=male  1=female (pick one)
        const val PARAM_HEAD_SIZE = 1     // Head size  0-100
        const val PARAM_PITCH_BASE = 2    // Pitch baseline  0-100
        const val PARAM_PITCH_FLUC =  3    // Pitch flutter  0-100
        const val PARAM_ROUGHNESS = 4     // Roughness  0-100
        const val PARAM_BREATHINESS =  5   // Breathiness  0-100
        const val PARAM_SPEED =  6         // Speed  0-250
        const val PARAM_VOLUME =  7        // Volume  0-100

        // Sliders shown in the edit dialog (gender is a separate radio - official voice switch;
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
