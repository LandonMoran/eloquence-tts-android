package com.xw.vvtts.utils

/**
 * Apple Kona Eloquence voice-param table(authoritative data from KonaVoicePresets.csv).
 *
 * 8 voices x the ECI voice params shared across all languages:
 *   gender(0) headSize(1) pitchBase(2) pitchFluc(3) rough(4) breath(5) speed(6) vol(7)
 *
 * raw CSV field order:breathiness,eciVoiceNumber,,headSize,,konaDialect,
 *   languageCode, name, pitchBase, pitchFluctuation, roughness, speed, vocalTract, volume
 *
 * mapped to ECI eciSetVoiceParam numbers(authoritative enum in eci.h):
 *   eciGender=0, eciHeadSize=1, eciPitchBaseline=2, eciPitchFluctuation=3,
 *   eciRoughness=4, eciBreathiness=5, eciSpeed=6, eciVolume=7
 *
 * vocalTract(vocal tension)is an Apple extension with no counterpart among the ECI standard 8 params,
 * but eciSetStandardVoice2 already covers it;we drive selection with the standard 8 params + eciVoiceNumber.
 */
class KonaVoice {
    class Voice(
        /** English voice name */
        val name: String,
        /** Chinese voice name */
        val nameCn: String,
        /** the Apple CSV's eciVoiceNumber(used for switching) */
        val eciVoiceNumber: Int,
        val gender: Int,
        val headSize: Int,
        val pitchBase: Int,
        val pitchFluc: Int,
        val rough: Int,
        val breath: Int,
        val speed: Int,
        val vol: Int,
        val vocalTract: Int,
    ) {
        /** Get a value by ECI voice param number */
        fun param(eciParam: Int): Int {
            return when (eciParam) {
                0 -> gender
                1 -> headSize
                2 -> pitchBase
                3 -> pitchFluc
                4 -> rough
                5 -> breath
                6 -> speed
                7 -> vol
                else -> 50
            }
        }
    }

    companion object {
        /** 8 voices(UI presets 1-8 order:Reed,Shelley,,Sandy,,Rocko,,Flo,,Grandma,,Grandpa,,Eddy)
        *  gender comes straight from the CSV's vocalTract field(0=male,,1=female);this is what keeps female voices from sounding "manly". */
        val VOICES = arrayOf(
            // 1 Reed(vocalTract=0 male)
            Voice("Reed", "Reed", 1, 0, 50, 65, 30, 0, 0, 50, 92, 0),
            // 2 Shelley(vocalTract=1 female)
            Voice("Shelley", "Shelley", 2, 1, 50, 81, 30, 0, 50, 50, 95, 1),
            // 3 Sandy(vocalTract=1 female)
            Voice("Sandy", "Sandy", 3, 1, 22, 93, 30, 0, 50, 50, 95, 1),
            // 4 Rocko(vocalTract=0 male)
            Voice("Rocko", "Rocko", 4, 0, 86, 56, 47, 0, 0, 50, 93, 0),
            // 5 Flo(vocalTract=1 female)
            Voice("Flo", "Flo", 6, 1, 56, 89, 35, 0, 40, 50, 95, 1),
            // 6 Grandma(vocalTract=1 female)
            Voice("Grandma", "Grandma", 7, 1, 45, 68, 30, 3, 40, 50, 90, 1),
            // 7 Grandpa(vocalTract=0 male)
            Voice("Grandpa", "Grandpa", 8, 0, 30, 61, 44, 18, 20, 50, 90, 0),
            // 8 Eddy(vocalTract=0 male)
            Voice("Eddy", "Eddy", 9, 0, 50, 69, 34, 0, 0, 50, 92, 0),
        )

        /** UI preset number 1-8 -> voice */
        fun byPreset(n: Int): Voice {
            var preset = n
            if (preset < 1 || preset > 8) preset = 1
            return VOICES[preset - 1]
        }

        /** eciVoiceNumber -> voice(falls back to Reed when not found) */
        fun byEciVoiceNumber(vn: Int): Voice {
            for (v in VOICES) if (v.eciVoiceNumber == vn) return v
            return VOICES[0]
        }
    }
}