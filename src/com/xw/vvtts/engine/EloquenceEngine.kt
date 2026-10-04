package com.xw.vvtts.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import com.xw.vvtts.core.VvttsCore
import com.xw.vvtts.utils.DictEntry
import com.xw.vvtts.utils.KonaVoice
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.TextNormalizer
import com.xw.vvtts.utils.VoiceProfile
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID
import com.xw.vvtts.utils.CrashCodeDefender

class EloquenceEngine(context: Context) {
    private val appContext: Context = context.applicationContext
    private val storageContext: Context = context.createDeviceProtectedStorageContext()

    @Volatile private var voiceProfile: VoiceProfile? = null
    /** User dictionary (word|spoken lines, VoiceConfig.dictEntries(); applied after the
     *  builtin spoken-exception tables so user overrides win ( mirrors the factory
     *  native loadUserDictionary hook; our ECI build has no dict API, so the substitution
     *  pass is the Kotlin-side equivalent. Fresh read per utterance, so edits apply immediately. */
    private val userDictionary by lazy { VoiceConfig(appContext) }
    @Volatile private var core: VvttsCore? = null          // In-house bridge (multi-language
    private var coreHandle: Long = 0
    private var nativeHandle: Long = 0L
    private var initialized = false
    @Volatile private var synthExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "elq-synth").apply { isDaemon = true }
    }
    @Volatile private var hangDetected = false
    // Bumped on every rotate/shutdown/stop; native handles born across a bump are never cached
    @Volatile private var engineEpoch = 0
        @Volatile private var retireUntilMs = 0L
        private val HANG_TIMEOUT_S = 30L
        private val ZOMBIE_GRACE_MS = 12000L
    @Volatile private var stopped = false
    @Volatile private var pendingVoice: Int? = null
    @Volatile private var pendingSapi = ""
    @Volatile private var pendingPitchFactor = 1.0f
    @Volatile private var pendingPitch: Int? = null
    @Volatile private var sampleRateSet = false
    private var lastNativePitch = 0
    @Volatile private var pendingSpeedFactor = 1.0f
    // Voice-copy state is per-dialect: each dialect owns a cached handle, so a
    // single global would skip setStandardVoice on a fresh handle that reuses the
    // same eciVoiceNumber after a dialect switch, leaving the new dialect speaking
    // the engine-default voice. Keyed by dialect (stable across shutdown/reopen).
    private val pendingEciVoiceByDialect = ConcurrentHashMap<Int, Int>()
    @Volatile private var pendingEciDialect = DIALECT_ZH_CN
    @Volatile private var warmedUp = false
    @Volatile private var pendingSapiMark = ""
    // Fingerprint of the last-applied param set; repeated utterances (TalkBack
    // swipe bursts) skip the ~19-call native param re-injection entirely.
    private var lastParamSig: String? = null

    /** Voice-profile source: custom overrides win during synthesis */
    fun setVoiceProfile(vp: VoiceProfile?) {
        this.voiceProfile = vp
    }

    private fun buildEloquenceConfig(libraryDir: File): String {
        val sb = StringBuilder()
        // 14 languages + 4 CJK romanizers (full Apple tvOS 18.2 engine)
        // [segment] dialect -> lib<name>.so; Path_Rom is CJK-only
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

        // Standard voice table — the Chinese library's register_voices relies on this to init voices
        sb.append("Voice1=0 50 65 30 0 0 50 92\n")    // Reed
        sb.append("Voice2=0 50 81 50 0 0 50 95\n")    // Shelley
        sb.append("Voice3=0 50 93 50 0 0 22 95\n")    // Sandy
        sb.append("Voice4=0 50 56 0 0 0 86 93\n")     // Rocko
        sb.append("Voice5=0 50 65 30 0 0 50 92\n")    // fallback
        sb.append("Voice6=0 50 89 40 0 0 56 95\n")    // Flo
        sb.append("Voice7=0 50 68 40 3 0 45 90\n")    // Grandma
        sb.append("Voice8=0 50 61 20 18 0 30 90\n")   // Grandpa
        sb.append("Voice9=0 50 69 0 0 0 50 92\n")     // Eddy
        return sb.toString()
    }

    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return true
        // New Apple engine: no longer uses the legacy Guangrong JNI (libeloquence_jni.so is deprecated)
        // Synthesis runs through VvttsCore's in-house bridge (dlopen Apple libeci.so); we just pre-write eci.ini here.
        try {
            val info: ApplicationInfo = appContext.applicationInfo
            val libDir = info.nativeLibraryDir
            if (libDir == null) {
                Log.e(TAG, "nativeLibraryDir is null")
                return false
            }
            val configDir = File(storageContext.filesDir, "eloquence")
            if (!configDir.exists()) configDir.mkdirs()
            val config = buildEloquenceConfig(File(libDir))
            FileWriter(File(configDir, "eci.ini")).use { fw -> fw.write(config) }
            initialized = true
            Log.i(TAG, "Eloquence engine (apple-eloquence-elf) ready, 14 languages")
            // Warm the default (US English) voice in the background: preloads the LPC voice
            // tables so the FIRST hover/swipe utterance starts speaking immediately.
            if (!skipWarmupForAutotest) warmupDialect(SHIPPED_DIALECTS.first().toInt())
            return true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            return false
        }
    }

    // Guangrong mapPitch: p<=50 -> p*69/50; p>50 ->(p-50)*31/50+69
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
        // Legacy Guangrong routing dropped; prosody is handled by synthesizeCore's internal setParam
        lastNativePitch = mapPitch(pitch)
    }

    companion object {
        private const val TAG = "EloquenceEngine"

        /** CI autotest (zh oracle) skips the en warmup: its forced-synthesis init
         *  phase never returns on x86_64 CI emulators only. Prod behavior is untouched. */
        @Volatile var skipWarmupForAutotest = false

        private fun applyDict(text: String, entries: List<DictEntry>): String {
            var t = text
            for (e in entries) {
                if (e.word.isEmpty()) continue
                val flag = if (e.caseSensitive) "" else "(?i)"
                val re = Regex(flag + "\\b" + Regex.escape(e.word) + "\\b")
                t = re.replace(t,  e.spoken)
            }
            return t
        }

        const val DIALECT_EN_US = 0x10000    // [1.0] enu
        const val DIALECT_EN_GB = 0x10001    // [1.1] eng
        const val DIALECT_ES_ES = 0x20000    // [2.0] esp
        const val DIALECT_ES_MX = 0x20002    // [2.2] esmx (real Mexican Spanish module
        const val DIALECT_FR_FR = 0x30000    // [3.0] fra
        const val DIALECT_FR_CA = 0x30001    // [3.1] frc
        const val DIALECT_DE_DE = 0x40000    // [4.0] deu
        const val DIALECT_IT_IT = 0x50000    // [5.0] ita
        const val DIALECT_ZH_CN = 0x60000    // [6.0] chs — linked against lang/chs; synthesis via the oracle voice bank (see SHIPPED_DIALECTS)
        const val DIALECT_ZH_TW = 0x60001    // [6.1] cht
        const val DIALECT_PT_BR = 0x70000    // [7.0] ptb
        const val DIALECT_JA_JP = 0x80000    // [8.0] jpn
        const val DIALECT_FI_FI = 0x90000    // [9.0] fin
        const val DIALECT_KO_KR = 0xA0000    // [10.0] kor
        // Language modules actually linked in this build (matches build_native.sh LANGS)
        // chs is linked (synthesis = oracle voice bank); zh-TW (cht)/ko (kor) have no module,
        // the native side would reject them; we intercept first so nothing depends on that rejection.
        val SHIPPED_DIALECTS: Set<Long> = setOf(
            0x10000L, 0x10001L,             // enus, engb
            0x20000L, 0x20001L, 0x20002L,    // eses, esus, esmx
            0x30000L, 0x30001L,             // frfr, frca
            0x40000L,                       // dede
            0x50000L,                       // itit
            0x70000L,                       // ptb
            0x80000L,                       // jajp
            0x90000L,                       // fin
            0x110000L,                      // plpl
            0x60000L,                       // chs
        )
        fun isShippedDialect(dialect: Int): Boolean = dialect.toLong() in SHIPPED_DIALECTS
        // Engine's real output rate:  the enu library in eci.ini is fixed at  11025 Hz and can't be changed at runtime
                const val ENGINE_SAMPLE_RATE = 11025
                // Playback rate:   the JNI layer resamples the engine's 11.025k PCM
                // natively to  44.1k (4x polyphase windowed-sinc FIR( before returning it,
                // so we tell Android AudioTrack the truth here.  ENGINE_SAMPLE_RATE stays untouched.

                const val SAMPLE_RATE = 44100

        private val CHINESE_CHARSET: Charset = Charset.forName("GB18030")
        private val ENGLISH_CHARSET: Charset = Charset.forName("windows-1252")

        /** Dialect -> text encoding (CJK uses its own encodings; Western uses windows-1252) */
        private fun charsetForDialect(dialect: Int): Charset {
            return when (dialect) {
                DIALECT_ZH_CN -> Charset.forName("GB18030")
                DIALECT_ZH_TW -> Charset.forName("Big5")
                DIALECT_JA_JP -> Charset.forName("Shift_JIS")
                DIALECT_KO_KR -> Charset.forName("EUC-KR")
                else -> Charset.forName("windows-1252")
            }
        }

        /** Normalize numerals/symbols for CJK (Chinese readings); other dialects pass
         *  through unchanged —the Lingua/segment layer already handled their quirks. */
        private fun preprocess(
            text: String, dialect: Int,
            userDict: List<DictEntry> = emptyList(),
            readPunct: Boolean = false,
            numberMode: Int = 0,
        ): String {
            val base = if (dialect == DIALECT_ZH_CN || dialect == DIALECT_ZH_TW)
                TextNormalizer.normalizeForChinese(text) else text
            if (base.isEmpty() || dialect == DIALECT_ZH_CN || dialect == DIALECT_ZH_TW) return base
            // Factory (decompiled Eloquence apk) Western text cleanups + per-dialect
            // spoken-exception tables ( ia.b a() + language packs(, ported verbatim..
            // Case-insensitive substring semantics match the factory (no word boundaries(. The
            // tables fix syllables/contractions the linked eci library would otherwise botch..
            var t = base.replace("\u0080", "euro").replace('|', ' ')
            t = t.replace('\u2019', '\'')
            if (t.isEmpty()) return t
            val overrides: List<Pair<String, String>> = when (dialect) {
                DIALECT_EN_US -> ENU_SPOKEN_EXCEPTIONS
                DIALECT_EN_GB -> ENG_SPOKEN_EXCEPTIONS
                DIALECT_FR_FR, DIALECT_FR_CA -> FR_SPOKEN_EXCEPTIONS
                DIALECT_DE_DE -> DE_SPOKEN_EXCEPTIONS
                else -> emptyList()
            }
            for ((w, r) in overrides) {
                t = Regex("(?i)" + Regex.escape(w)).replace(t, r)
            }

            // Factory number grouping, ported from their TTS service. Mode -1 = off
            // ( pass-through); mode 0 = ECI defaults ( step 1 for runs of 9+);
            // 1-4 = fixed group sizes 1..4.
            t = numberGroups(t,  numberMode)

            // User dictionary: wire the existing applyDict() helper ( committed but
            // never called(; user-added word|spoken entries now reach synthesis. Mirrors
            // the factory native loadUserDictionary hook; applied after the builtin tables so
            // user overrides win ( word-boundary match - Landons intended semantics(.
            t = applyDict(t, userDict)
            // Read-punctuation ( "speak_punctuation" knob in Settings → Reading tab; mirrors
            // the factory "Use punctuation" — expands ,.!?... to spoken names so a blind
            // review of typed/OCR text hears the actual punctuation ( no silent marks(.
            if (readPunct) t = expandPunct(t)
            return t
        }
        // Factory voice registry: (eng,USA( -> enu pack, (eng,GBR( -> eng pack — so en-US
        // uses the 28-entry table ( "#"→" hash " is en-GB-only(. Ported verbatim from
        // the decompiled Eloquence apk language packs ( f3234b/f3232b/f3242b/f3244b/f3230b(.
        private val ENU_SPOKEN_EXCEPTIONS = listOf(
            "tzsche" to "tsche", "ctrl" to "control", "JLS" to "J L S",
            "assistive" to "a sistive", "freedomscientific" to "Freedom Scientific",
            "caesure" to "seizure", "c#0sure" to "seizure", "SD" to "S D",
            "h've" to "have", "h're" to "here", "hhs" to "hs", "bhes" to "b hes",
            "dhes" to "d hes", "fhes" to "f hes", "jhes" to "j hes", "lhes" to "l hes",
            "mhes" to "m hes", "nhes" to "n hes", "qhes" to "q hes", "vhes" to "v hes",
            "zhes" to "z hes", "uncosp" to "un cosp", "rarheskill" to "rar heskill",
            "ad hesi" to "adhesi", "gmail" to "g mail",
            "TS2:21st" to "TS2:21s t", "TS2:22nd" to "TS2:22n d", "TS2:24th" to "TS2:24t h",
        )
        private val ENG_SPOKEN_EXCEPTIONS = ENU_SPOKEN_EXCEPTIONS + ("#" to " hash ")
        private val FR_SPOKEN_EXCEPTIONS = listOf(
            "quil" to "kil",
            "Je ne voudrais pas quil t'arrive quelque" to "Je ne voudrais pas quil t arrive quelque",
        )
        private val DE_SPOKEN_EXCEPTIONS = listOf(
            "dagegen" to "dage gen", "dage-gen" to "dage gen",
            "dageben" to "dage ben", "dage-ben" to "dage ben",
        )

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
            val out = ShortArray(pcm.size)
            for (i in pcm.indices) {
                val s = (pcm[i] * gain).toInt()
                out[i] = if (s > 32767) 32767.toShort()
                else if (s < -32768) (-32768).toShort()
                else s.toShort()
            }
            return out
        }
        private val punctMap: Map<Char, String> = mapOf(
            '.' to " period ", ',' to " comma ", '!' to " exclamation mark ",
            '?' to " question mark ", ';' to " semicolon ", ':' to " colon ",
            '"' to " quote ", '\'' to " apostrophe ",
            '(' to " open parenthesis ", ')' to " close parenthesis ",
            '/' to " slash ", '*' to " asterisk ",
        )

        private fun expandPunct(text: String): String {
            val sb = StringBuilder(text.length +  24)
            var i =  0
            while (i < text.length) {
                val c = text[i]
                if (c == '.' && i >  0 && i +  1 < text.length &&
                    text[i -  1].isDigit() && text[i +  1].isDigit()) {
                    sb.append(c)  // decimal point: keep "3.14" intact
                } else {
                    val name = punctMap[c]
                    if (name != null) sb.append(name) else sb.append(c)
                }
                i++
            }
            return sb.toString()
        }

        /**
         * Factory number processing, ported from their TTS service: group long digit
         * runs into fixed steps; mode 0 = "use ECI defaults" (their f3223c=0 path,
         * implemented here as step 1 for runs of 9+ ). Mode 1..4 = fixed step of
         * that many digits, with trailing groups of 1/2/3/4. Runs shorter than  5
         * digits pass through untouched. */
        private fun numberGroups(text: String,  mode: Int): String {
            if (text.none { it.isDigit() }) return text
            val sb = StringBuilder(text.length +  16)
            var i =  0
            while (i < text.length) {
                if (text[i].isDigit()) {
                    var j = i +  1
                    while (j < text.length && text[j].isDigit()) j++
                    val n = j - i
                    if (n >=  5) {
                        val step = if (mode <  0) 0 else if (mode !=  0) mode else if (n >=  9) 1 else  0
                        if (step >  0) {
                            var k = i
                            while (k < j) {
                                val end = Math.min(k + step,  j)
                                sb.append(text,  k,  end)
                                if (end < j) sb.append(' ')
                                k = end
                            }
                        } else {
                            sb.append(text,  i,  j)
                        }
                    } else {
                        sb.append(text,  i,  j)
                    }
                    i = j
                } else {
                    sb.append(text[i])
                    i++
                }
            }
            return sb.toString()
        }
    }







    fun synthesize(text: String, dialect: Int, volume: Int): ShortArray? {
        // Legacy Guangrong routing dropped; forwards to synthesizeCore (Apple engine(
        return synthesizeCore(text, dialect, volume, 1, 50)
    }

    fun stop() {
        stopped = true
        engineEpoch++
        core?.let { for (h in coreHandles.values) VvttsCore.stop(h) }
    }

    @Synchronized
    fun shutdown() {
        engineEpoch++
        // Drain/cancel in-flight work BEFORE closing the native handles: a worker
        // still inside synthesizeCore would otherwise hit a native session that
        // shutdown() just freed (use-after-free racing the closed EP pipe), and
        // submit() during teardown would throw. A hung worker gets shutdownNow()
        // after a short grant.
        val ex = synthExecutor
        if (!ex.isShutdown) {
            ex.shutdown()
            try {
                if (!ex.awaitTermination(150, TimeUnit.MILLISECONDS)) {
                    ex.shutdownNow()
                    ex.awaitTermination(100, TimeUnit.MILLISECONDS)
                }
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                ex.shutdownNow()
            }
        }
        core?.let {
            for (h in coreHandles.values) VvttsCore.shutdown(h)
            coreHandles.clear()
        }
        pendingEciVoiceByDialect.clear()
        initialized = false
        core = null
    }

    fun isInitialized(): Boolean = initialized
    fun getNativeHandle(): Long = nativeHandle
    fun getSampleRate(): Int = SAMPLE_RATE

        // ===== In-house bridge (the only synthesis path) =====
    private val coreHandles = ConcurrentHashMap<Int, Long>()
    private var lastSynthRate =  44100  // output rate of the most recent synthesized audio (44.1k after resample(
    fun getCoreSampleRate(): Int = lastSynthRate

    /** Open + cache the engine handle for a dialect (no synthesis(.  Doing this
     *  once inthe background after onCreate removes the LPC voice-table load from
     *  the critical path of the first utterance (the single biggest "hover to
     *  speech" latency component(.
     */
    private fun ensureHandle(dialect: Int): Long {
        val cached = coreHandles[dialect] ?: 0L
        if (cached !=  0L) return cached
        val info: ApplicationInfo = appContext.applicationInfo
        val libDirRaw = info.nativeLibraryDir
        if (libDirRaw == null) { Log.e(TAG, "nativeLibraryDir null  cannot open engine"); return 0L }
        val libDir = File(libDirRaw)
        val cfgDir = File(storageContext.filesDir, "eloquence")
        try {
            FileWriter(File(cfgDir, "eci.ini")).use { fw ->
                fw.write(buildEloquenceConfig(libDir))
            }
        } catch (e: Exception) {
            Log.e(TAG, "write eci.ini failed", e)
            return 0L
        }
        val epoch = engineEpoch
        val handle = VvttsCore.openEngine(cfgDir.absolutePath, libDir.absolutePath, dialect)
        Log.e(TAG, "core init dialect=" + Integer.toHexString(dialect) + " handle=" + handle)
        if (handle ==  0L) return 0L
        // A handle born across a rotate/shutdown/stop is retired, never cached —
        // the zombie worker that opened it may still be driving it.
        if (epoch != engineEpoch) {
            VvttsCore.shutdown(handle)
            return   0L
        }
        coreHandles[dialect] = handle
        return handle
    }

    /** Warm the engine handle for a dialect on the serial worker, so the
     *  first real utterance with that dialect finds its LPC tables already resident.

     *  No synthesis happens here — just the native handle open. */
    fun warmupDialect(dialect: Int) {
        if (dialect < 0) return
        // After shutdown() the executor is dead and the natives are closed: a
        // warmup submitted then would throw RejectedExecutionException into
        // onLoadLanguage/onCreate. No-op instead (initialized is the gate).
        if (!initialized) {
            Log.i(TAG, "warmupDialect skipped: engine not initialized")
            return
        }
        try {
            synthExecutor.execute { ensureHandle(dialect) }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "warmupDialect skipped: executor shutting down")
        }
    }
    /** Currently selected voice preset (1-8( and custom-mode flag */
    @Volatile private var voicePreset = 1
    @Volatile private var voiceCustom = false
    @Volatile private var customPitch = 50

    /**
     * Apple Kona CSV backtick annotation.
     * `vN=standard voice number` vs=speed (0-100)` vv=volume` vy=vocalTract
     * `vb=breathiness `vh=headSize `vr=roughness `vf=pitchFluctuation `vb?=pitch
     * All values come from KonaVoicePresets.csv (Reed=1 Shelley=2 Sandy=3 Rocko=4 Flo=6 Grandma=7 Grandpa=8 Eddy=9)
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
     * Synthesis runs on the in-house bridge (full Apple engine, all 14 languages supported().
     * Voice is driven by presetId (1-8 Apple presets(, going through eciSetStandardVoice2.
     */
    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int): ShortArray? {
        return synthesizeCore(text, dialect, volume, presetId, 50)
    }

    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int, uiPitch: Int): ShortArray? {
        // Default speed 100% (neutral(
        return synthesizeCore(text, dialect, volume, presetId, uiPitch, 100)
    }

    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int, uiPitch: Int, uiRate: Int): ShortArray? {
        return synthWithTimeout {
        // Languages not linked in this build (zh/pt/fi/ko/zh-TW( are rejected outright.
            //the native side would reject them; we intercept so the TTS service gets a clean null
            //(Android auto-fallbacks to other engines(, keeping every path away from the crashy eciNewEx.
            if (!isShippedDialect(dialect)) {
                Log.e(TAG, "dialect not in this build: 0x" + Integer.toHexString(dialect))
                return@synthWithTimeout null
            }
            if (core == null) core = VvttsCore()

            // session lazy-loading (cached per dialect(
            val handle = ensureHandle(dialect)
            if (handle ==  0L) return@synthWithTimeout null

        // === Voice row copy + full param tuning — Apple Kona CSV is the authoritative source.
            // The copy below must precede the param writes: params tune the copied row but never pick
            // WHICH voice (engine default=Reed without the copy; CLI probe corr 0.02 Reed vs Sandy).
            val voice: KonaVoice.Voice = KonaVoice.byPreset(presetId)



            // Copy the selected preset row onto the active voice 0 (openevv's eciCopyVoice,
            // equivalent of the old Apple eciSetStandardVoice2 — crash-free here). Without it,
            // every utterance speaks engine default=Reed; params tune but never pick the voice.


            if (voice.eciVoiceNumber != pendingEciVoiceByDialect[dialect]) {

                VvttsCore.setStandardVoice(handle, voice.eciVoiceNumber)
                pendingEciVoiceByDialect[dialect] = voice.eciVoiceNumber
            }

            val vp = voiceProfile
            val sigBase = presetId.toString() + "|" + dialect + "|" + uiPitch + "|" + uiRate + "|" + volume + "|" + voice.eciVoiceNumber
            // Voice-character overrides (head/fluc/rough/breath) bust the the voice-param cache too:
            // dragging a char slider leaves the sig unchanged and the engine skips param re-injection ( dead sliders(.
            val sigChar = if (vp != null) VoiceProfile.EDITABLE_PARAMS.joinToString("|") { p -> vp.getParam(presetId,	p).toString() } else ""
            val sig = sigBase + "|" + sigChar
            val sameAsLast = sig == lastParamSig
            val pitchBase = mapUiPitchToKona(uiPitch, voice.pitchBase)   // eciPitchBaseline
            val speedVal = Math.round(50.0f * uiRate /  100.0f)
                .toInt().coerceIn(5,  250)   // eciSpeed:  0..250 (engine ceiling

            if (!sameAsLast) {
            // Inject this voice's 8 ECI voice params (gender=0 head=1 pitchBase=2
            // pitchFluc=3 rough=4 breath=5 speed=6 vol=7.
            var pitchLogged = false
            var speedLogged = false
            var volLogged = false
            for (p in 0..7) {
            // Custom overrides first; otherwise KonaVoice defaults
                val value = if (vp != null && vp.hasOverride(presetId, p)) vp.getParam(presetId, p) else voice.param(p)
                val ret = VvttsCore.setVoiceParam(handle, 0, p, value)
                // Diagnostic-only logging (remove after root cause found): failures always;
                // successes once per param, so a dead channel shows in logcat without spam.

                if (ret < 0) {
                    Log.w("VvTts", "voice param #$p=$value -> ret $ret (FAILURE)")
                } else if (p==2 && !pitchLogged) {

                    Log.i("VvTts", "voice param #2 pitch=$value -> ret $ret (OK)")
                    pitchLogged = true
                } else if (p==6 && !speedLogged) {
                    Log.i("VvTts", "voice param #6 speed=$value -> ret $ret (OK)")
                    speedLogged = true
                } else if (p==7 && !volLogged) {
                    Log.i("VvTts", "voice param #7 vol=$value -> ret $ret (OK)")
                    volLogged = true
                }
            }

            // User UI params
            // Pitch: UI 0-100 -> Apple pitchBase (±30 around the voice's own pitchBase
            VvttsCore.setVoiceParam(handle, 0, 2, pitchBase)   // eciPitchBaseline

            // Speed: eciSpeed voice param (voice param 6, range 0..250, 50=normal,
                        // matching the CSV speed in the 8-param injection above; previously eciSampleRate
                        // (env[5]) resampling faked the speed,and the 22050/32000/44100 steps force-sinc'd
                        // the 11 kHz LPC voice up — it sounded like pure electric crackle — dropped.
                        // Engine outputs native 11025;the JNI layer upsamplest it to 44.1k for playback.
                        VvttsCore.setVoiceParam(handle, 0, 6, speedVal)
                        VvttsCore.setParam(handle,  5,  1)   // eciSampleRate=1 => engine stays native,no speed-side effects
                                                lastSynthRate = 44100  // post-resample playback rate


            // Volume: CSV preset volume; no second applyVolume; set the voice param first
            VvttsCore.setVoiceParam(handle, 0,   7, voice.vol)   // eciVolume
                        }
            // Encoding
            val cs = charsetForDialect(dialect)
            val encoded: ByteArray
            try {
                val bb: ByteBuffer = cs.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .encode(CharBuffer.wrap(CrashCodeDefender.sanitize(appContext,  preprocess(
                                            text,  dialect,  userDictionary.dictEntries(),  userDictionary.punctEnabled,
                                            if (userDictionary.numberEnabled) userDictionary.numberModePref else -1))))
                encoded = ByteArray(bb.remaining())
                bb.get(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "encode failed for " + Integer.toHexString(dialect), e)
                return@synthWithTimeout null
            }
            val charset = if (dialect == DIALECT_ZH_CN) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
            val outFile = File(storageContext.cacheDir, "core_pcm_out_" + UUID.randomUUID())
            if (!sameAsLast) {
            // Second param write right before synthesis — an addText/internal reset on this
            // call path would otherwise drop the first batch (CLI only ever writes once, before add(.
            for (p in 0..7) {
                val value = if (vp != null && vp.hasOverride(presetId, p)) vp.getParam(presetId, p) else voice.param(p)
                val ret = VvttsCore.setVoiceParam(handle, 0, p, value)
            }
            VvttsCore.setVoiceParam(handle, 0, 2, pitchBase)   // eciPitchBaseline
                        VvttsCore.setVoiceParam(handle, 0, 6, speedVal)
            VvttsCore.setVoiceParam(handle, 0,   7, voice.vol)   // eciVolume
                        }
                        var pcm = VvttsCore.synth(handle, dialect, encoded, charset, null)
            // DSP mode read live from prefs so both the Settings test path and the
            // TTS service honor the toggle without restart (0 = standard, 1 = enhanced).
            runCatching { outFile.delete() }
            if (pcm != null && pcm.size > 0) {
                pcm = applyVolume(pcm, volume)
                lastParamSig = sig  // only admit success:failed param writes must retry
            }
            pcm
        }
    }

    /** UI pitch 0-100 -> Apple pitchBase (±30 around the current voice's pitchBase) */
    private fun mapUiPitchToKona(uiPitch: Int, basePitch: Int): Int {
        // uiPitch 50 = neutral (voice's default pitchBase(; 0 = -30; 100 = +30
        val offset = uiPitch - 50
        val pitch = basePitch + Math.round(offset * 0.6).toInt()
        return coerceIn(pitch, 40, 120)
    }

    /** Set the voice preset (1-8() */
    fun setVoicePreset(n: Int) {
        voicePreset = coerceIn(n, 1, 8)
        voiceCustom = false
        Log.e(TAG, "voicePreset -> " + voicePreset)
    }

    /** Custom pitch mode (driven directly by the UI slider( */
    fun setCustomPitch(pitch: Int) {
        customPitch = coerceIn(pitch, 0, 100)
        voiceCustom = true
    }

    fun getPendingPitchFactor(): Float = pendingPitchFactor

    /**
     * Apply the speaking voice (voice params are now read inside synthesizeCore from the
     * voiceProfile override; this stub stays for backward compat.)
     */
    fun applyVoiceProfile(dialect: Int, profile: VoiceProfile?) {
        // Nothing extra needed: synthesizeCore already reads overrides via the voiceProfile field
    }

    // Apple-CSV pitchBase-derived voice pitch factor (Reed=65 is the baseline(
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

    // Voice speed factor (Apple CSV speed=50-baseline character tuning(
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

    // Apple CSV eciVoiceNumber (en-US(: Reed=1 Shelley=2 Sandy=3 Rocko=4 Flo=6 Grandma=7 Grandpa=8 Eddy=9
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
     * Uses Apple KonaVoicePresets.csv params (breathiness/headSize/roughness/pitchFlutter(
     * injected into the current voice via eciSetVoiceParam. ECI voice param numbers (matching the Apple CSV fields(:
     * Voice-quality params go through ECI's standard voice param channel.
     */
    private fun applyCsvVoiceParams(handle: Long, presetId: Int) {
    // Apple CSV {breathiness, headSize, roughness, pitchFluctuation}
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
        // ECI voice param:eciPitchBaseline=2, eciSpeed=3, eciVolume=4, eciGeneral=5,
            // eciSayAsCtrl=6 ... roughness/breath are engine-specific extended params; trying common numbers here
            // Conservative: only safe, verified base params are set; tone quality rides on voiceNumber itself
            // (voiceNumber is switched via eciSetStandardVoice2, so the base timbre follows it)
        Log.e(TAG, "csvVoiceParams preset=" + presetId + " breath=" + p[0]
                + " head=" + p[1] + " rough=" + p[2] + " flutter=" + p[3])
    }

        // SAPI inline tags (natively supported by Eloquence; format follows chtvoice.sapi.txt(
        // \\v=X\\ switches the standard voice number directly
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


    // Runs a synthesis body on the single worker thread. If it has not finished
    // in HANG_TIMEOUT_S, log the stuck stack, retire the engine, and return null
    // so THIS request fails fast — a frozen native call cannot take down the whole TTS.
    private fun synthWithTimeout(block: () -> ShortArray?): ShortArray? {
        val nowMin = SystemClock.elapsedRealtime()
        if (nowMin < retireUntilMs) {
            Log.w(TAG, "TTS_HANG: zombie grace until " + retireUntilMs + " (" + (retireUntilMs - nowMin) + " ms left); skipping to avoid doubling the retired native worker")
            return null
        }
        val future = try {
            synthExecutor.submit<ShortArray?> { block() }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "TTS_HANG: worker rejected — rotating", e)
            rotateEngine()
            retireUntilMs = SystemClock.elapsedRealtime() + ZOMBIE_GRACE_MS
            return null
        }
        return try {
            future.get(HANG_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.e(TAG, "TTS_HANG: synthesis ran >" + HANG_TIMEOUT_S + "s — rotating engine", e)
            var first = true
            for ((t, st)in Thread.getAllStackTraces()) {
                if (first) { Log.e(TAG, "  in-flight threads:"); first = false }
                Log.e(TAG, "    " + t.name + ": " + st.joinToString(" | "))
            }
            rotateEngine()
            retireUntilMs = SystemClock.elapsedRealtime() + ZOMBIE_GRACE_MS
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: ExecutionException) {
            Log.e(TAG, "synthesis threw", e)
            null
        } catch (e: java.util.concurrent.CancellationException) {
            Log.w(TAG, "synthesis cancelled by rotate/shutdownNow()", e)
            null
        }
    }

    // The single worker froze (can't interrupt native code(: retire it, start a fresh
    // executor + fresh native handles so subsequent requests work again immediatel
    private fun rotateEngine() {
        try {
            synthExecutor.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "rotate: shutdown failed", e)
        }
        // Best-effort: stop any in-flight render on the retired handles so a
        // fresh open isn't competing with a zombie session. VvttsCore.stop is
        // flag-based (non-blocking(, and this cross-thread usage mirrors stop().
        // (The frozen worker's own native state can't be freed safely — that
        // memory is released once per hang event, unavoidably.)
        try {
            for (h in coreHandles.values) VvttsCore.stop(h)
        } catch (e: Exception) {
            Log.e(TAG, "rotate: stop handles failed", e)
        }
        synthExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "elq-synth").apply { isDaemon = true }
        }
        coreHandles.clear()
        engineEpoch++
        lastParamSig = null
        pendingEciVoiceByDialect.clear() // fresh handles start at engine-default voices; drop stale cache
        hangDetected = true
    }
}
