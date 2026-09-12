package com.xw.vvtts.utils;

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
public final class KonaVoice {

    public static class Voice {
        public final String name;      // 角色英文名
        public final String nameCn;    // 中文名
        public final int eciVoiceNumber; // 苹果 CSV 的 eciVoiceNumber（切换用）
        public final int gender;
        public final int headSize;
        public final int pitchBase;
        public final int pitchFluc;
        public final int rough;
        public final int breath;
        public final int speed;
        public final int vol;
        public final int vocalTract;

        Voice(String n, String cn, int vn, int g, int h, int pb, int pf,
              int r, int b, int s, int vo, int vt) {
            name = n; nameCn = cn; eciVoiceNumber = vn;
            gender = g; headSize = h; pitchBase = pb; pitchFluc = pf;
            rough = r; breath = b; speed = s; vol = vo; vocalTract = vt;
        }

        /** 按 ECI voice param 编号取值 */
        public int param(int eciParam) {
            switch (eciParam) {
                case 0: return gender;
                case 1: return headSize;
                case 2: return pitchBase;
                case 3: return pitchFluc;
                case 4: return rough;
                case 5: return breath;
                case 6: return speed;
                case 7: return vol;
                default: return 50;
            }
        }
    }

    /** 8 角色（界面预设 1-8 顺序：Reed, Shelley, Sandy, Rocko, Flo, Grandma, Grandpa, Eddy）
     *  gender 直接取 CSV 的 vocalTract 字段（0=男, 1=女），这是女声不显"男嗓"的关键。 */
    public static final Voice[] VOICES = {
        // 1 Reed（vocalTract=0 男）
        new Voice("Reed", "Reed", 1, 0, 50, 65, 30, 0, 0, 50, 92, 0),
        // 2 Shelley（vocalTract=1 女）
        new Voice("Shelley", "Shelley", 2, 1, 50, 81, 30, 0, 50, 50, 95, 1),
        // 3 Sandy（vocalTract=1 女）
        new Voice("Sandy", "Sandy", 3, 1, 22, 93, 30, 0, 50, 50, 95, 1),
        // 4 Rocko（vocalTract=0 男）
        new Voice("Rocko", "Rocko", 4, 0, 86, 56, 47, 0, 0, 50, 93, 0),
        // 5 Flo（vocalTract=1 女）
        new Voice("Flo", "Flo", 6, 1, 56, 89, 35, 0, 40, 50, 95, 1),
        // 6 Grandma（vocalTract=1 女）
        new Voice("Grandma", "Grandma", 7, 1, 45, 68, 30, 3, 40, 50, 90, 1),
        // 7 Grandpa（vocalTract=0 男）
        new Voice("Grandpa", "Grandpa", 8, 0, 30, 61, 44, 18, 20, 50, 90, 0),
        // 8 Eddy（vocalTract=0 男）
        new Voice("Eddy", "Eddy", 9, 0, 50, 69, 34, 0, 0, 50, 92, 0),
    };

    private KonaVoice() {}

    /** 界面预设编号 1-8 → 角色 */
    public static Voice byPreset(int n) {
        if (n < 1 || n > 8) n = 1;
        return VOICES[n - 1];
    }

    /** eciVoiceNumber → 角色（找不到返回 Reed） */
    public static Voice byEciVoiceNumber(int vn) {
        for (Voice v : VOICES) if (v.eciVoiceNumber == vn) return v;
        return VOICES[0];
    }
}