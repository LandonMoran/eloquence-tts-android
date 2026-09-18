package com.xw.vvtts.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.xw.vvtts.core.VvttsCore
import com.xw.vvtts.utils.KonaVoice
import com.xw.vvtts.utils.VoiceProfile
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.HashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class EloquenceEngine(context: Context) {
    private val appContext: Context = context.applicationContext

    private var voiceProfile: VoiceProfile? = null
    private var core: VvttsCore? = null          // 自研桥接（多语言）
    private var coreHandle: Long = 0
    private var nativeHandle: Long = 0L
    private var initialized = false
    private val synthLock = ReentrantLock()
    @Volatile private var stopped = false
    @Volatile private var pendingVoice: Int? = null
    @Volatile private var pendingSapi = ""
    @Volatile private var pendingPitchFactor = 1.0f
    @Volatile private var pendingPitch: Int? = null
    @Volatile private var sampleRateSet = false
    private var lastNativePitch = 0
    @Volatile private var pendingSpeedFactor = 1.0f
    @Volatile private var pendingEciVoice = -1
    @Volatile private var pendingEciDialect = DIALECT_ZH_CN
    @Volatile private var warmedUp = false
    @Volatile private var pendingSapiMark = ""

    /** 设置角色配置来源，合成时优先读取其自定义覆盖 */
    fun setVoiceProfile(vp: VoiceProfile?) {
        this.voiceProfile = vp
    }

    private fun buildEloquenceConfig(libraryDir: File): String {
        val sb = StringBuilder()
        // 14 语言 + 4 CJK romanizer（苹果 tvOS 18.2 完整引擎）
        // [段号] dialect -> lib<name>.so，Path_Rom 仅 CJK
        val langs = arrayOf(
            arrayOf("1.0", "libenu.so", null),           // en-US
            arrayOf("1.1", "libeng.so", null),           // en-GB
            arrayOf("2.0", "libesp.so", null),           // es-ES
            arrayOf("2.1", "libesm.so", null),           // es-MX
            arrayOf("3.0", "libfra.so", null),           // fr-FR
            arrayOf("3.1", "libfrc.so", null),           // fr-CA
            arrayOf("4.0", "libdeu.so", null),           // de-DE
            arrayOf("5.0", "libita.so", null),           // it-IT
            arrayOf("6.0", "libchs.so", "libchsrom.so"), // zh-CN
            arrayOf("6.1", "libcht.so", "libchtrom.so"), // zh-TW
            arrayOf("7.0", "libptb.so", null),           // pt-BR
            arrayOf("8.0", "libjpn.so", "libjpnrom.so"), // ja-JP
            arrayOf("9.0", "libfin.so", null),           // fi-FI
            arrayOf("10.0", "libkor.so", "libkorrom.so") // ko-KR
        )
        for (l in langs) {
            sb.append("[").append(l[0]).append("]\nPath=")
            sb.append(File(libraryDir, l[1]).absolutePath)
            sb.append("\n")
            if (l[2] != null) {
                sb.append("Path_Rom=")
                sb.append(File(libraryDir, l[2]).absolutePath)
                sb.append("\n")
            }
            sb.append("Version=6.1\n\n")
        }

        // 标准 voice 表——中文库 register_voices 依赖此段初始化 voice 表
        sb.append("Voice1=0 50 65 30 0 0 50 92\n")    // Reed
        sb.append("Voice2=0 50 81 50 0 0 50 95\n")    // Shelley
        sb.append("Voice3=0 50 93 50 0 0 22 95\n")    // Sandy
        sb.append("Voice4=0 50 56 0 0 0 86 93\n")     // Rocko
        sb.append("Voice5=0 50 65 30 0 0 50 92\n")    // 备用
        sb.append("Voice6=0 50 89 40 0 0 56 95\n")    // Flo
        sb.append("Voice7=0 50 68 40 3 0 45 90\n")    // Grandma
        sb.append("Voice8=0 50 61 20 18 0 30 90\n")   // Grandpa
        sb.append("Voice9=0 50 69 0 0 0 50 92\n")     // Eddy
        return sb.toString()
    }

    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return true
        // 新苹果引擎：不再依赖广荣 JNI（libeloquence_jni.so 已废弃）。
        // 合成走 VvttsCore 自研桥接（dlopen 苹果 libeci.so），这里只需预写 eci.ini。
        try {
            val info: ApplicationInfo = appContext.applicationInfo
            val libDir = info.nativeLibraryDir
            if (libDir == null) {
                Log.e(TAG, "nativeLibraryDir is null")
                return false
            }
            val configDir = File(appContext.filesDir, "eloquence")
            if (!configDir.exists()) configDir.mkdirs()
            val config = buildEloquenceConfig(File(libDir))
            FileWriter(File(configDir, "eci.ini")).use { fw -> fw.write(config) }
            initialized = true
            Log.i(TAG, "Eloquence engine (apple-eloquence-elf) ready, 14 语言")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            return false
        }
    }

    // 广荣 mapPitch: <=50 -> p*69/50; >50 -> (p-50)*31/50+69
    private fun mapPitch(pitch: Int): Int {
        var pitch = pitch
        if (pitch < 0) pitch = 0
        if (pitch > 100) pitch = 100
        return if (pitch <= 50) pitch * 69 / 50 else (pitch - 50) * 31 / 50 + 69
    }

    private fun coerceIn(v: Int, min: Int, max: Int): Int {
        if (v < min) return min
        return if (v > max) max else v
    }

    private fun coerceInF(v: Float, min: Float, max: Float): Float {
        if (v < min) return min
        return if (v > max) max else v
    }

    fun setProsody(rate: Int, pitch: Int, volume: Int, rateMultiplier: Float) {
        // 旧广荣通路已废弃，韵律由 synthesizeCore 内部 setParam 处理
        lastNativePitch = mapPitch(pitch)
    }

    companion object {
        private const val TAG = "EloquenceEngine"

        const val DIALECT_EN_US = 0x10000    // [1.0] enu
        const val DIALECT_EN_GB = 0x10001    // [1.1] eng
        const val DIALECT_ES_ES = 0x20000    // [2.0] esp
        const val DIALECT_ES_MX = 0x20002    // [2.2] esmx（真实墨西哥西语模块）
        const val DIALECT_FR_FR = 0x30000    // [3.0] fra
        const val DIALECT_FR_CA = 0x30001    // [3.1] frc
        const val DIALECT_DE_DE = 0x40000    // [4.0] deu
        const val DIALECT_IT_IT = 0x50000    // [5.0] ita
        const val DIALECT_ZH_CN = 0x60000    // [6.0] chs —— 本构建未链接 lang/chs，见 SHIPPED_DIALECTS
        const val DIALECT_ZH_TW = 0x60001    // [6.1] cht
        const val DIALECT_PT_BR = 0x70000    // [7.0] ptb
        const val DIALECT_JA_JP = 0x80000    // [8.0] jpn
        const val DIALECT_FI_FI = 0x90000    // [9.0] fin
        const val DIALECT_KO_KR = 0xA0000    // [10.0] kor
        // 本构建实际链接的语言模块（与 build_native.sh LANGS 一致）。
        // 未列出的方言（zh/pt/fi/ko/zh-TW）引擎里没有模块，native 侧会拒绝，
        // 这里先拦截以免依赖 native 拒绝对话。
        val SHIPPED_DIALECTS: Set<Long> = setOf(
            0x10000L, 0x10001L,             // enus, engb
            0x20000L, 0x20001L, 0x20002L,    // eses, esus, esmx
            0x30000L, 0x30001L,             // frfr, frca
            0x40000L,                       // dede
            0x50000L,                       // itit
            0x80000L,                       // jajp
            0x110000L,                      // plpl
        )
        fun isShippedDialect(dialect: Int): Boolean = dialect.toLong() in SHIPPED_DIALECTS
        // 引擎真实输出采样率：eci.ini 的 en 库固定 11025Hz，运行时不可改
        const val ENGINE_SAMPLE_RATE = 11025
        // 输出采样率 = 引擎原生 11025（实测音色最自然，不做重采样）
        const val SAMPLE_RATE = 11025

        private val CHINESE_CHARSET: Charset = Charset.forName("GB18030")
        private val ENGLISH_CHARSET: Charset = Charset.forName("windows-1252")

        /** 方言 → 文本编码（CJK 用特定编码，西文用 windows-1252） */
        private fun charsetForDialect(dialect: Int): Charset {
            return when (dialect) {
                DIALECT_ZH_CN -> Charset.forName("GB18030")
                DIALECT_ZH_TW -> Charset.forName("Big5")
                DIALECT_JA_JP -> Charset.forName("Shift_JIS")
                DIALECT_KO_KR -> Charset.forName("EUC-KR")
                else -> Charset.forName("windows-1252")
            }
        }

        @JvmStatic
        fun applyVolume(pcm: ShortArray, volume: Int): ShortArray {
            var volume = volume
            if (volume < 0) volume = 0
            if (volume > 100) volume = 100
            // NOTE: unity gain at volume == 100. The engine already applies its own
            // eciVolume (Kona voicing, usually ~90) internally; scaling AGAIN by
            // volume/50.0 would double-amplify any signal and hard-clip the output
            // at the default setting of 100. volume/100.0 is clean unity at default.
            val gain = volume / 100.0f
            if (gain == 1.0f) return pcm
            val out = ShortArray(pcm.size)
            for (i in pcm.indices) {
                var v = Math.round(pcm[i] * gain)
                if (v < -32768) v = -32768
                if (v > 32767) v = 32767
                out[i] = v.toShort()
            }
            return out
        }
    }

    fun synthesize(text: String, dialect: Int, volume: Int): ShortArray? {
        // 旧广荣通路已废弃，转发到 synthesizeCore（苹果引擎）
        return synthesizeCore(text, dialect, volume, 1, 50)
    }

    fun stop() {
        stopped = true
        core?.let { for (h in coreHandles.values) VvttsCore.stop(h) }
    }

    @Synchronized
    fun shutdown() {
        core?.let {
            for (h in coreHandles.values) VvttsCore.shutdown(h)
            coreHandles.clear()
        }
        initialized = false
        core = null
    }

    fun isInitialized(): Boolean = initialized
    fun getNativeHandle(): Long = nativeHandle
    fun getSampleRate(): Int = SAMPLE_RATE

    // ===== 自研桥接（唯一合成通道）=====
    private val coreHandles = HashMap<Int, Long>()
    // 参数注入 + 合成 的串行锁：并发调用时避免多个线程同时 setVoiceParam/synth 互相覆盖
    private val paramSynthLock = ReentrantLock()
    private var lastSynthRate = 11025  // 最近一次输出的采样率（WAV 头解析）
    fun getCoreSampleRate(): Int = lastSynthRate

    /** 当前选择的角色预设（1-8）与自定义模式标记 */
    @Volatile private var voicePreset = 1
    @Volatile private var voiceCustom = false
    @Volatile private var customPitch = 50

    /**
     * 苹果 Kona CSV 反引号 annotation。
     * `vN=标准voice编号 `vs=语速(0-100) `vv=音量 `vy=vocalTract
     * `vb=breathiness `vh=headSize `vr=roughness `vf=pitchFluctuation `vb?=pitch
     * 数值全部来自 KonaVoicePresets.csv（Reed=1 Shelley=2 Sandy=3 Rocko=4 Flo=6 Grandma=7 Grandpa=8 Eddy=9）
     */
    private fun presetAnnotation(n: Int): String {
        return when (n) {
            1 -> "`v1 `vs50 `vv90"                              // Reed
            2 -> "`v3 `vb61 `vh31 `vr18 `vy20 `vf44 `vs50 `vv90" // Sandy
            3 -> "`v2 `vb20 `vh30 `vr5  `vy30 `vf30 `vs50 `vv90" // Shelley
            4 -> "`v4 `vb0  `vh50 `vr45 `vy50 `vf25 `vs48 `vv90" // Rocko
            5 -> "`v9 `vb10 `vh55 `vr8  `vy50 `vf35 `vs50 `vv90" // Eddy
            6 -> "`v6 `vb35 `vh35 `vr10 `vy35 `vf40 `vs52 `vv90" // Flo
            7 -> "`v8 `vb30 `vh45 `vr28 `vy45 `vf22 `vs44 `vv90" // Grandpa
            8 -> "`v7 `vb45 `vh40 `vr20 `vy40 `vf35 `vs45 `vv90" // Grandma
            else -> "`v1"
        }
    }

    /**
     * 用自研桥接合成（苹果完整引擎，14 语言全支持）。
     * 角色由 presetId 驱动（1-8 苹果预设），走 eciSetStandardVoice2。
     */
    @Synchronized
    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int): ShortArray? {
        return synthesizeCore(text, dialect, volume, presetId, 50)
    }

    @Synchronized
    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int, uiPitch: Int): ShortArray? {
        // 默认语速 100%（中性）
        return synthesizeCore(text, dialect, volume, presetId, uiPitch, 100)
    }

    @Synchronized
    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int, uiPitch: Int, uiRate: Int): ShortArray? {
        return paramSynthLock.withLock {
            // 本构建未链接的语言模块（zh/pt/fi/ko/zh-TW）直接拒绝。
            // native 侧也会拒绝，这里拦截是让 TTS 服务拿到干净的 null（Android
            // 自动 fallback 其它引擎），而不是让任何路径靠近会把进程杀掉的 eciNewEx。
            if (!isShippedDialect(dialect)) {
                Log.e(TAG, "dialect not in this build: 0x" + Integer.toHexString(dialect))
                return@withLock null
            }
            if (core == null) core = VvttsCore()

            // session 懒加载（按方言缓存）
            var handle = coreHandles[dialect] ?: 0L
            if (handle == 0L) {
                val info: ApplicationInfo = appContext.applicationInfo
                val libDir = File(info.nativeLibraryDir)
                val cfgDir = File(appContext.filesDir, "eloquence")
                try {
                    FileWriter(File(cfgDir, "eci.ini")).use { fw ->
                        fw.write(buildEloquenceConfig(libDir))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "write eci.ini failed", e)
                    return@withLock null
                }
                handle = VvttsCore.openEngine(cfgDir.absolutePath, libDir.absolutePath, dialect)
                Log.e(TAG, "core init dialect=" + Integer.toHexString(dialect) + " handle=" + handle)
                if (handle == 0L) return@withLock null
                coreHandles[dialect] = handle
            }

            // === 完整参数适配（苹果 Kona CSV 权威数据）===
            // 注意：eciSetStandardVoice2 会崩（ETIEvent::wait，libeci+0x149b4），
            // 即使 ctype 修复后也一样——这是独立的 ETIEvent 生命周期问题。
            // 所以角色切换不走 eciSetStandardVoice，而是靠下面 8 个 eciSetVoiceParam
            // 完整注入角色音色（headSize/pitchBase/pitchFluc/rough/breath/speed/vol）。
            val voice: KonaVoice.Voice = KonaVoice.byPreset(presetId)

            // 注入该角色的 8 个 ECI voice 参数（gender=0 head=1 pitchBase=2
            // pitchFluc=3 rough=4 breath=5 speed=6 vol=7）
            val vp = voiceProfile
            for (p in 0..7) {
                // 优先用自定义覆盖，否则用 KonaVoice 默认
                val value = if (vp != null && vp.hasOverride(presetId, p)) vp.getParam(presetId, p) else voice.param(p)
                VvttsCore.setVoiceParam(handle, 0, p, value)
                // 静默失败：setVoiceParam 偶发 ret<0 不影响合成，不打日志避免刷屏
            }

            // 用户 UI 参数
            // 音调：UI 0-100 → 苹果 pitchBase（以角色 pitchBase 为基线的 ±30 范围）
            val pitchBase = mapUiPitchToKona(uiPitch, voice.pitchBase)
            VvttsCore.setVoiceParam(handle, 0, 2, pitchBase)   // eciPitchBaseline

            // 语速：eciSpeed 语音参数（voice param 6，范围 0..250，50=正常语速，
                        // 与上方 8 参数注入中的 CSV speed 一致）。之前用 eciSampleRate
                        // （env[5]）重采样充当语速，22050/32000/44100 档把 11 kHz 的
                        // LPC 声线强行 sinc 拉高，听感全是“滋滋”的静电破音——已废弃。
                        // 输出采样率固定在本机原生 11025，引擎自己保持音高不变地变速。
                        val speedVal = Math.round(50.0f * uiRate / 100.0f)
                            .toInt().coerceIn(5, 250)   // eciSpeed：0..250（引擎上限）
                        VvttsCore.setVoiceParam(handle, 0, 6, speedVal)
                        VvttsCore.setParam(handle, 5, 1)   // eciSampleRate=1 → 恒 11025 Hz
                        lastSynthRate = 11025


            // 音量：CSV 预置 volume + 不做二次 applyVolume 前先设 voice param
            VvttsCore.setVoiceParam(handle, 0, 7, voice.vol)   // eciVolume

            // 编码
            val cs = charsetForDialect(dialect)
            val encoded: ByteArray
            try {
                val bb: ByteBuffer = cs.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .encode(CharBuffer.wrap(text))
                encoded = ByteArray(bb.remaining())
                bb.get(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "encode failed for " + Integer.toHexString(dialect), e)
                return@withLock null
            }
            val charset = if (dialect == DIALECT_ZH_CN) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
            val outFile = File(appContext.cacheDir, "core_pcm_out")
            var pcm = VvttsCore.synth(handle, dialect, encoded, charset, outFile.absolutePath)
            if (pcm != null && pcm.size > 0) pcm = applyVolume(pcm, volume)
            pcm
        }
    }

    /** UI 音调 0-100 → 苹果 pitchBase（以当前角色 pitchBase 为基线的 ±30 范围） */
    private fun mapUiPitchToKona(uiPitch: Int, basePitch: Int): Int {
        // uiPitch 50 = 中性（用角色默认 pitchBase）；0 = 低 30；100 = 高 30
        val offset = uiPitch - 50
        val pitch = basePitch + Math.round(offset * 0.6).toInt()
        return coerceIn(pitch, 40, 120)
    }

    /** 设置角色预设（1-8） */
    fun setVoicePreset(n: Int) {
        voicePreset = coerceIn(n, 1, 8)
        voiceCustom = false
        Log.e(TAG, "voicePreset -> " + voicePreset)
    }

    /** 自定义音调模式（UI 滑块直接驱动） */
    fun setCustomPitch(pitch: Int) {
        customPitch = coerceIn(pitch, 0, 100)
        voiceCustom = true
    }

    fun getPendingPitchFactor(): Float = pendingPitchFactor

    /**
     * 应用发音角色（现在角色参数在 synthesizeCore 内直接读 voiceProfile 覆盖，
     * 这里保留空实现以兼容旧调用点）
     */
    fun applyVoiceProfile(dialect: Int, profile: VoiceProfile?) {
        // 不需要额外处理：synthesizeCore 已通过 voiceProfile 字段读取自定义覆盖
    }

    // 苹果 CSV pitchBase 换算的角色音调因子（以 Reed=65 为基准）
    private fun presetPitchFactor(n: Int): Float {
        return when (n) {
            1 -> 1.00f  // Reed
            2 -> 1.43f  // Sandy
            3 -> 1.25f  // Shelley
            4 -> 0.86f  // Rocko
            5 -> 1.06f  // Eddy
            6 -> 1.37f  // Flo
            7 -> 0.94f  // Grandpa
            8 -> 1.05f  // Grandma
            else -> 1.0f
        }
    }

    // 角色语速因子（苹果 CSV speed=50 基准的性格化微调）
    private fun presetSpeedFactor(n: Int): Float {
        return when (n) {
            1 -> 1.00f  // Reed
            2 -> 1.03f  // Sandy
            3 -> 1.01f  // Shelley
            4 -> 0.98f  // Rocko
            5 -> 1.00f  // Eddy
            6 -> 1.02f  // Flo
            7 -> 0.94f  // Grandpa
            8 -> 0.96f  // Grandma
            else -> 1.0f
        }
    }

    // 苹果 CSV eciVoiceNumber（en-US）：Reed=1 Shelley=2 Sandy=3 Rocko=4 Flo=6 Grandma=7 Grandpa=8 Eddy=9
    private fun presetEciVoice(n: Int): Int {
        return when (n) {
            1 -> 1  // Reed
            2 -> 3  // Sandy
            3 -> 2  // Shelley
            4 -> 4  // Rocko
            5 -> 9  // Eddy
            6 -> 6  // Flo
            7 -> 8  // Grandpa
            8 -> 7  // Grandma
            else -> 1
        }
    }

    /**
     * 用苹果 KonaVoicePresets.csv 的参数（breathiness/headSize/roughness/pitchFlutter）
     * 通过 eciSetVoiceParam 注入当前语音。ECI voice param 编号（与苹果 CSV 字段对应）：
     * 声音质感参数走 ECI 的标准 voice 参数通道。
     */
    private fun applyCsvVoiceParams(handle: Long, presetId: Int) {
        // 苹果 CSV {breathiness, headSize, roughness, pitchFluctuation}
        val p: IntArray = when (presetId) {
            2 -> intArrayOf(50, 50, 0, 30)   // Shelley
            3 -> intArrayOf(50, 22, 0, 30)   // Sandy
            4 -> intArrayOf(0, 50, 0, 30)    // Rocko
            5 -> intArrayOf(0, 55, 0, 30)    // Eddy
            6 -> intArrayOf(35, 35, 0, 40)   // Flo
            7 -> intArrayOf(20, 30, 18, 44)  // Grandpa
            8 -> intArrayOf(45, 40, 20, 35)  // Grandma
            else -> intArrayOf(0, 50, 0, 30) // Reed
        }
        // ECI voice param：eciPitchBaseline=2, eciSpeed=3, eciVolume=4, eciGeneral=5,
        // eciSayAsCtrl=6 ... 粗糙度/气声等是引擎特定扩展参数，此处按常见编号尝试
        // 保守起见：只调已验证安全的基础参数，声音质感依赖 voiceNumber 本身
        // （voiceNumber 已通过 eciSetStandardVoice2 切换，基础音色随之变化）
        Log.e(TAG, "csvVoiceParams preset=" + presetId + " breath=" + p[0]
                + " head=" + p[1] + " rough=" + p[2] + " flutter=" + p[3])
    }

    // SAPI 内联标记（Eloquence 原生支持，格式参照 chtvoice.sapi.txt）
    // \v=X\ 直接切换标准 voice 编号
    private fun presetSapiMark(n: Int): String {
        return when (n) {
            1 -> "\\v=1\\"   // Reed
            2 -> "\\v=3\\"   // Sandy
            3 -> "\\v=2\\"   // Shelley
            4 -> "\\v=4\\"   // Rocko
            5 -> "\\v=9\\"   // Eddy
            6 -> "\\v=6\\"   // Flo
            7 -> "\\v=8\\"   // Grandpa
            8 -> "\\v=7\\"   // Grandma
            else -> ""
        }
    }
}