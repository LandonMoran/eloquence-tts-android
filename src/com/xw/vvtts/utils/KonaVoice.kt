package com.xw.vvtts.utils

/**
 * 苹果 Kona Eloquence 角色参数表（来自 KonaVoicePresets.csv 权威数据）。
 *
 * 8 个角色 × 全语言统一的 ECI voice 参数：
 *   gender(0) headSize(1) pitchBase(2) pitchFluc(3) rough(4) breath(5) speed(6) vol(7)
 *
 * CSV 原始字段顺序：breathiness, eciVoiceNumber, headSize, konaDialect,
 *   languageCode, name, pitchBase, pitchFluctuation, roughness, speed, vocalTract, volume
 *
 * 映射到 ECI eciSetVoiceParam 编号（eci.h 权威枚举）：
 *   eciGender=0, eciHeadSize=1, eciPitchBaseline=2, eciPitchFluctuation=3,
 *   eciRoughness=4, eciBreathiness=5, eciSpeed=6, eciVolume=7
 *
 * vocalTract（声带紧张度）是苹果扩展，ECI 标准 8 参数里没有对应，
 * 但已有 eciSetStandardVoice2 可用，故这里用标准 8 参数 + eciVoiceNumber 切换组合驱动。
 */
class KonaVoice {
    class Voice(
        /** 角色英文名 */
        val name: String,
        /** 中文名 */
        val nameCn: String,
        /** 苹果 CSV 的 eciVoiceNumber（切换用） */
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
        /** 按 ECI voice param 编号取值 */
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
        /** 8 角色（界面预设 1-8 顺序：Reed, Shelley, Sandy, Rocko, Flo, Grandma, Grandpa, Eddy）
         *  gender 直接取 CSV 的 vocalTract 字段（0=男, 1=女），这是女声不显"男嗓"的关键。 */
        val VOICES = arrayOf(
            // 1 Reed（vocalTract=0 男）
            Voice("Reed", "Reed", 1, 0, 50, 65, 30, 0, 0, 50, 92, 0),
            // 2 Shelley（vocalTract=1 女）
            Voice("Shelley", "Shelley", 2, 1, 50, 81, 30, 0, 50, 50, 95, 1),
            // 3 Sandy（vocalTract=1 女）
            Voice("Sandy", "Sandy", 3, 1, 22, 93, 30, 0, 50, 50, 95, 1),
            // 4 Rocko（vocalTract=0 男）
            Voice("Rocko", "Rocko", 4, 0, 86, 56, 47, 0, 0, 50, 93, 0),
            // 5 Flo（vocalTract=1 女）
            Voice("Flo", "Flo", 6, 1, 56, 89, 35, 0, 40, 50, 95, 1),
            // 6 Grandma（vocalTract=1 女）
            Voice("Grandma", "Grandma", 7, 1, 45, 68, 30, 3, 40, 50, 90, 1),
            // 7 Grandpa（vocalTract=0 男）
            Voice("Grandpa", "Grandpa", 8, 0, 30, 61, 44, 18, 20, 50, 90, 0),
            // 8 Eddy（vocalTract=0 男）
            Voice("Eddy", "Eddy", 9, 0, 50, 69, 34, 0, 0, 50, 92, 0),
        )

        /** 界面预设编号 1-8 → 角色 */
        fun byPreset(n: Int): Voice {
            var preset = n
            if (preset < 1 || preset > 8) preset = 1
            return VOICES[preset - 1]
        }

        /** eciVoiceNumber → 角色（找不到返回 Reed） */
        fun byEciVoiceNumber(vn: Int): Voice {
            for (v in VOICES) if (v.eciVoiceNumber == vn) return v
            return VOICES[0]
        }
    }
}