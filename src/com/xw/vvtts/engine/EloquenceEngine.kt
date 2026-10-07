package com.xw.vvtts.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import com.xw.vvtts.core.VvttsCore
import com.xw.vvtts.utils.KonaVoice
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.TextNormalizer
import com.xw.vvtts.utils.VoiceProfile
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.xw.vvtts.utils.CrashCodeDefender

internal class AppliedVoiceParams {
    private var dialect: Int? = null
    private var presetId: Int = 0
    private var values: IntArray? = null

    fun matches(dialect: Int, presetId: Int, params: IntArray): Boolean =
        this.dialect == dialect && this.presetId == presetId && values?.contentEquals(params) == true

    fun record(dialect: Int, presetId: Int, params: IntArray) {
        this.dialect = dialect
        this.presetId = presetId
        values = params.copyOf()
    }

    fun clear() {
        dialect = null
        values = null
    }
}

class EloquenceEngine(context: Context) {
    private val appContext: Context = context.applicationContext
    private val handleLock = Any()
    private val storageContext: Context = context.createDeviceProtectedStorageContext()

    @Volatile private var voiceProfile: VoiceProfile? = null
    /** User dictionary (word|spoken lines, VoiceConfig.dictEntries(); applied after the
     *  builtin spoken-exception tables so user overrides win ( mirrors the factory
     *  native loadUserDictionary hook; our ECI build has no dict API, so the substitution
     *  pass is the Kotlin-side equivalent. Fresh read per utterance, so edits apply immediately. */
    // Direct Boot: user-dictionary prefs must stay in device-protected storage.
    // Credential-encrypted getSharedPreferences throws IllegalStateException, so a
    // locked start would break every synthesis until the first user unlock. See #235.
    private val userDictionary by lazy { VoiceConfig(storageContext) }
    @Volatile private var initialized = false
    // Keep handles with their owning worker, including after a timeout retires it.
    private class SynthWorker {
        @Volatile var retired = false
        // handles is iterated by stop() from the caller thread, so it must stay safe
        // for concurrent reads while this worker lazily opens new sessions..
        val handles = ConcurrentHashMap<Int, Long>()
        // Voice-copy state is per-dialect: each dialect owns a cached handle, so a
        // single global would skip setStandardVoice on a fresh handle that reuses the
        // same eciVoiceNumber after a dialect switch, leaving the new dialect speaking
        // the engine-default voice.  Scoped per worker so a retired worker's late
        // writes never land in a replacement worker's cache.

        // Repeated utterances skip the ~19-call native param re-injection without
        // constructing a string signature for every synthesis request.
        val appliedVoiceParams = AppliedVoiceParams() // accessed only by this worker's executor
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "elq-synth").apply { isDaemon = true }
        }
    }
    // Long native retires ( ~2s settlement waits( run here so they never stall the single synth thread
    @Volatile private var retireExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "elq-retire").apply { isDaemon = true }
    }
    @Volatile private var synthWorker = SynthWorker()
    // Bumped on every rotate/shutdown/stop; native handles born across a bump are never cached
    private val engineEpoch = AtomicLong()
    private val lifecycleEpoch = AtomicLong()
        @Volatile private var retireUntilMs = 0L
        private val HANG_TIMEOUT_S = 30L
        private val ZOMBIE_GRACE_MS = 12000L

        /** Voice-profile source: custom overrides win during synthesis */
    fun setVoiceProfile(vp: VoiceProfile?) {
        this.voiceProfile = vp
    }

    /** Prepare device storage and schedule warmup; return false if initialization fails. */
    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return true
        if (retireExecutor.isShutdown) retireExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "elq-retire").apply { isDaemon = true }
        }
        // Prepare storage and schedule optional native warmup. Native readiness is checked per request.
        try {
            val info: ApplicationInfo = appContext.applicationInfo
            val libDir = info.nativeLibraryDir
            if (libDir == null) {
                Log.e(TAG, "nativeLibraryDir is null")
                return false
            }
            val configDir = File(storageContext.filesDir, "eloquence")
            if (!configDir.exists()) configDir.mkdirs()
            initialized = true
            Log.i(TAG, "Eloquence openevv engine ready")
            // Warm the default (US English) voice in the background: preloads the LPC voice
            // tables so the FIRST hover/swipe utterance starts speaking immediately.
            if (!skipWarmupForAutotest) warmupDialect(SHIPPED_DIALECTS.first().toInt())
            return true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            return false
        }
    }

    companion object {
        private const val TAG = "EloquenceEngine"
        /** Serializes ALL native synthesis ops engine-side delivery — Settings diagnostics,
         *  the TTS service, warmup and stop (issue #197). Reentrant: callers that already hold
         *  their own engineCallLock just nest safely. */
        private val nativeLock = Any()

        /** CI autotest (zh oracle) skips the en warmup: its forced-synthesis init
         *  phase never returns on x86_64 CI emulators only. Prod behavior is untouched. */
        @Volatile var skipWarmupForAutotest = false

        /** Apply dictionary rules in order, rejecting any intermediate expansion beyond 16,384 characters. */
        private fun applyDict(text: String, rules: List<Pair<Regex, String>>): String {
            var result = text
            for ((pattern, spoken) in rules) {
                // Bound chained replacements too; dictionary entries can expand each other.
                val next = StringBuilder()
                var offset = 0
                for (match in pattern.findAll(result)) {
                    require(next.length + match.range.first - offset + spoken.length <= 16384) { "Dictionary expansion too large" }
                    next.append(result, offset, match.range.first).append(spoken)
                    offset = match.range.last + 1
                }
                require(next.length + result.length - offset <= 16384) { "Dictionary expansion too large" }
                next.append(result, offset, result.length)
                result = next.toString()
            }
            return result
        }

        const val DIALECT_EN_US = 0x10000    // [1.0] enu
        const val DIALECT_EN_GB = 0x10001    // [1.1] eng
        const val DIALECT_ES_ES = 0x20000    // [2.0] esp
        const val DIALECT_ES_MX =                          0x20002    // [2.2] esmx (real Mexican Spanish module
        const val DIALECT_ES_US =                          0x20001    // [2.1] esus — US Spanish (#18: no const existed; shipped module esus
        const val DIALECT_PL_PL =                         0x110000    // [11.0] plpl — Polish
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
        val SHIPPED_DIALECTS: Set<Long> = VoiceRegistry.voices.map { it.dialect.toLong() }.toSet()
        fun isShippedDialect(dialect: Int): Boolean = dialect.toLong() in SHIPPED_DIALECTS
        // Engine's real output rate:  the enu library in eci.ini is fixed at  11025 Hz and can't be changed at runtime
                const val ENGINE_SAMPLE_RATE = 11025
                // Playback rate:   the JNI layer resamples the engine's 11.025k PCM
                // natively to  44.1k (4x polyphase windowed-sinc FIR( before returning it,
                // so we tell Android AudioTrack the truth here.  ENGINE_SAMPLE_RATE stays untouched.

                const val SAMPLE_RATE = 44100


        /** Dialect -> text encoding (CJK uses its own encodings; Western uses windows-1252) */
        private fun charsetForDialect(dialect: Int): Charset {
            return when (dialect) {
                DIALECT_ZH_CN -> Charset.forName("GB18030")
                DIALECT_ZH_TW -> Charset.forName("Big5")
                DIALECT_JA_JP -> Charset.forName("Shift_JIS")
                DIALECT_PL_PL -> Charsets.UTF_8 // openevv custom-character module decodes UTF-8
                DIALECT_KO_KR -> Charset.forName("EUC-KR")
                else -> Charset.forName("windows-1252")
            }
        }

        /** Normalize numerals/symbols for CJK (Chinese readings); other dialects pass
         *  through unchanged —the Lingua/segment layer already handled their quirks. */
        private fun preprocess(
            text: String, dialect: Int,
            userDict: List<Pair<Regex, String>> = emptyList(),
            readPunct: Boolean = false,
            numberMode: Int = 0,
        ): String {
            val base = if (dialect == DIALECT_ZH_CN || dialect == DIALECT_ZH_TW)
                TextNormalizer.normalizeForChinese(text) else text
            if (base.isEmpty()) return base
            if (dialect == DIALECT_ZH_CN || dialect == DIALECT_ZH_TW) return applyDict(base, userDict)
            // Factory (decompiled Eloquence apk) Western text cleanups + per-dialect
            // spoken-exception tables ( ia.b a() + language packs(, ported verbatim..
            // Case-insensitive substring semantics match the factory (no word boundaries(. The
            // tables fix syllables/contractions the linked eci library would otherwise botch..
            var t = base.replace("\u0080", "euro").replace('|', ' ')
            t = t.replace('\u2019', '\'')
            if (t.isEmpty()) return t
            val overrides: List<Pair<Regex, String>> = when (dialect) {
                DIALECT_EN_US -> ENU_SPOKEN_REGEXES
                DIALECT_EN_GB -> ENG_SPOKEN_REGEXES
                DIALECT_FR_FR, DIALECT_FR_CA -> FR_SPOKEN_REGEXES
                DIALECT_DE_DE -> DE_SPOKEN_REGEXES
                else -> emptyList()
            }
            for ((pattern, spoken) in overrides) {
                t = pattern.replace(t, spoken)
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
        private fun compileSpokenExceptions(entries: List<Pair<String, String>>): List<Pair<Regex, String>> =
            entries.map { (written, spoken) -> Regex("(?i)" + Regex.escape(written)) to spoken }

        private val ENU_SPOKEN_REGEXES = compileSpokenExceptions(ENU_SPOKEN_EXCEPTIONS)
        private val ENG_SPOKEN_REGEXES = compileSpokenExceptions(ENG_SPOKEN_EXCEPTIONS)
        private val FR_SPOKEN_REGEXES = compileSpokenExceptions(FR_SPOKEN_EXCEPTIONS)
        private val DE_SPOKEN_REGEXES = compileSpokenExceptions(DE_SPOKEN_EXCEPTIONS)

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

    /** Invalidate pending synthesis and signal native handles without waiting for the synthesis lock. */
    fun stop() {
            engineEpoch.incrementAndGet()
            // Native stop (s->cancel=1 volatile) is the designed cross-thread cancellation:
            // it aborts the in-flight synth's own wait loop instead of queueing behind it.  Iterating
            // handles cross-thread is safe now that they live in a ConcurrentHashMap the worker may lazily grow.

            val worker = synthWorker
            synchronized(handleLock) {
                for (h in worker.handles.values) VvttsCore.stop(h)
            }
        }

    /** Retire the worker and arrange handle cleanup after active native calls return. */
    @Synchronized
    fun shutdown() {
        engineEpoch.incrementAndGet()
        lifecycleEpoch.incrementAndGet()
        val worker = synthWorker
        worker.retired = true
        initialized = false
        try {
            worker.executor.execute {
                // Cleanup queues behind any in-flight synthesis so it frees the
                // handles on their owning thread only after the native call returns.


                closeHandles(worker)
            }
        } catch (ignore: RejectedExecutionException) {
            // A retired worker may still be using its handles; its in-flight task


            // frees them on the owning thread once it returns (see synthWithTimeout.

        }
        // Retire this worker so no fresh work lands on a closing executor;the
        // replacement starts with clean per-worker caches (fresh handles begin at the
        // engine-default voice, so no stale voice/param state can leak across an open).

        worker.executor.shutdown()


        // Drain/cancel in-flight work BEFORE closing the native handles: a worker
        // still inside synthesizeCore would otherwise hit a native session that
        // shutdown() just freed (use-after-free racing the closed EP pipe),and
        // submit() during teardown would throw. A hung worker gets shutdownNow()
        // after a short grant.



        try {
            if (!worker.executor.awaitTermination(150, TimeUnit.MILLISECONDS)) {

                worker.executor.shutdownNow()
                worker.executor.awaitTermination(100, TimeUnit.MILLISECONDS)
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            worker.executor.shutdownNow()
        }
        retireExecutor.shutdown()
        synthWorker = SynthWorker()
        initialized = false
        }

    /** Report whether initialization succeeded and the engine has not been shut down. */
    fun isInitialized(): Boolean = initialized
    fun getSampleRate(): Int = SAMPLE_RATE

        // ===== In-house bridge (the only synthesis path) =====
    /** Return the bridge output sample rate in Hz. */
    fun getCoreSampleRate(): Int = SAMPLE_RATE

    /** Detach a worker's cached handles and shut them down after its active native call returns. */
    private fun closeHandles(worker: SynthWorker) {
        val handles = synchronized(handleLock) {
            val values = worker.handles.values.toList()
            worker.handles.clear()
            values
        }
        handles.forEach { VvttsCore.shutdown(it) }
    }

    /** Remove a failed handle from its worker and schedule destruction outside the handle lock. */
    private fun retireHandle(worker: SynthWorker, dialect: Int, handle: Long) {
        val removed = synchronized(handleLock) { worker.handles.remove(dialect, handle) }
        if (removed) {
            worker.appliedVoiceParams.clear()
            try {
                retireExecutor.execute { VvttsCore.shutdown(handle) }
            } catch (_: RejectedExecutionException) {
                // Teardown closed the executor while this owning worker returned.
                VvttsCore.shutdown(handle)
            }
        }
    }

    /** Return a cached or newly opened dialect handle, or zero if the worker is obsolete. */
    private fun ensureHandle(worker: SynthWorker, dialect: Int): Long {
        if (worker.retired || synthWorker !== worker || !initialized) return 0L
        val cached = worker.handles[dialect] ?: 0L
        if (cached !=  0L) return cached
        val info: ApplicationInfo = appContext.applicationInfo
        val libDirRaw = info.nativeLibraryDir
        if (libDirRaw == null) { Log.e(TAG, "nativeLibraryDir null  cannot open engine"); return 0L }
        val libDir = File(libDirRaw)
        val cfgDir = File(storageContext.filesDir, "eloquence")
        val epoch = lifecycleEpoch.get()
        val handle = VvttsCore.openEngine(cfgDir.absolutePath, libDir.absolutePath, dialect)
        Log.e(TAG, "core init dialect=" + Integer.toHexString(dialect) + " handle=" + handle)
        if (handle ==  0L) return 0L
        // A handle born across a rotate/shutdown is retired, never cached —
        // the zombie worker that opened it may still be driving it. A stop does
        // not retire the owning worker, so a handle opened across a stop is kept.
        if (epoch != lifecycleEpoch.get() || worker.retired || synthWorker !== worker || !initialized) {
            VvttsCore.shutdown(handle)
            return   0L
        }
        val accepted = synchronized(handleLock) {
            if (epoch != lifecycleEpoch.get() || worker.retired || synthWorker !== worker || !initialized) false
            else { worker.handles[dialect] = handle; true }
        }
        // Native destruction can wait; never hold the lock used by Stop while closing.
        if (!accepted) { VvttsCore.shutdown(handle); return 0L }
        return handle
    }

    /** Warm the engine handle for a dialect on the serial worker, so the
     *  first real utterance with that dialect finds its LPC tables already resident.

     *  No synthesis happens here — just the native handle open. */
    fun warmupDialect(dialect: Int) {
        if (dialect <  0) return
        // Fail closed while the engine is retired: no fresh handle may open
        // beside a possibly-still-alive zombie worker..

        if (SystemClock.elapsedRealtime() < retireUntilMs) {

            Log.w(TAG, "TTS_HANG: skipped warmup while engine retired")
            return
        }
        val worker = synthWorker
        // After shutdown() the executor is dead and the natives are closed: a
        // warmup submitted then would throw RejectedExecutionException into
        // onLoadLanguage/onCreate. No-op instead (initialized is the gate).


        if (!initialized) {
            Log.i(TAG, "warmupDialect skipped: engine not initialized")
            return
        }
        val epoch = lifecycleEpoch.get()
        try {
            worker.executor.execute {
                if (epoch == lifecycleEpoch.get() && !worker.retired && synthWorker === worker && initialized)
                    ensureHandle(worker, dialect)
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "warmupDialect skipped: executor shutting down")
        }
    }
    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int): ShortArray? {
        return synthesizeCore(text, dialect, volume, presetId, 50)
    }

    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int, uiPitch: Int): ShortArray? {
        // Default speed 100% (neutral(
        return synthesizeCore(text, dialect, volume, presetId, uiPitch, 100)
    }

    /** Configure and synthesize on the serial worker; return null on failure or cancellation. */
    fun synthesizeCore(text: String, dialect: Int, volume: Int, presetId: Int, uiPitch: Int, uiRate: Int): ShortArray? = synchronized(nativeLock) {
        return synthWithTimeout { worker ->
        // Languages not linked in this build (zh/pt/fi/ko/zh-TW( are rejected outright.
            //the native side would reject them; we intercept so the TTS service gets a clean null
            //(Android auto-fallbacks to other engines(, keeping every path away from the crashy eciNewEx.
            if (!isShippedDialect(dialect)) {
                Log.e(TAG, "dialect not in this build: 0x" + Integer.toHexString(dialect))
                return@synthWithTimeout null
            }

            // session lazy-loading (cached per dialect(
            val handle = ensureHandle(worker, dialect)
            if (handle ==  0L) return@synthWithTimeout null

        // === Voice row copy + full param tuning — Apple Kona CSV is the authoritative source.
            // The copy below must precede the param writes: params tune the copied row but never pick
            // WHICH voice (engine default=Reed without the copy; CLI probe corr 0.02 Reed vs Sandy).
            val voice: KonaVoice.Voice = KonaVoice.byPreset(presetId)



            // Copy the selected preset row onto the active voice 0 (openevv's eciCopyVoice,
            // equivalent of the old Apple eciSetStandardVoice2 — crash-free here). Without it,
            // every utterance speaks engine default=Reed; params tune but never pick the voice.


            val vp = voiceProfile
            val params = IntArray(8) { p -> vp?.getParam(presetId, p) ?: voice.param(p) }
            params[2] = mapUiPitchToKona(uiPitch, params[2])
            params[6] = Math.round(50.0f * uiRate / 100.0f).coerceIn(5, 250)
            if (!worker.appliedVoiceParams.matches(dialect, presetId, params)) {
                // openevv places Eddy at 5; Kona's CSV places Eddy at 9.
                val copied = VvttsCore.setStandardVoice(handle, voice.nativeVoiceNumber)
                val configured = copied >= 0 && params.indices.all { p ->
                    VvttsCore.setVoiceParam(handle, 0, p, params[p]) >= 0
                } && VvttsCore.setParam(handle, VvttsCore.ECI_SAMPLE_RATE, 1) >= 0
                if (!configured) {
                    retireHandle(worker, dialect, handle)
                    return@synthWithTimeout null
                }
                worker.appliedVoiceParams.record(dialect, presetId, params)
            }
            // Encoding
            val cs = charsetForDialect(dialect)
            val encoded: ByteArray
            try {
                val bb: ByteBuffer = cs.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .encode(CharBuffer.wrap(CrashCodeDefender.sanitize(appContext,  preprocess(
                                            text,  dialect,  userDictionary.compiledDictionary(),  userDictionary.punctEnabled,
                                            if (userDictionary.numberEnabled) userDictionary.numberModePref else -1))))
                encoded = ByteArray(bb.remaining())
                bb.get(encoded)
            } catch (e: Exception) {
                Log.e(TAG, "encode failed for " + Integer.toHexString(dialect), e)
                return@synthWithTimeout null
            }
            val charset = if (dialect == DIALECT_ZH_CN) VvttsCore.CHARSET_GBK else VvttsCore.CHARSET_1252
            var pcm = VvttsCore.synth(handle, dialect, encoded, charset, null)
            if (pcm != null && pcm.isEmpty()) {
                // Native retirement is permanent. The next utterance opens a
                // fresh session and must reapply its voice and parameters.
                retireHandle(worker, dialect, handle)
                return@synthWithTimeout null
            }
            if (pcm != null && pcm.size > 0) {
                pcm = applyVolume(pcm, volume)
            }
            pcm
        }
    }

    /** Map UI pitch 0..100 across the native range, keeping 50 at the selected voice's baseline. */
    private fun mapUiPitchToKona(uiPitch: Int, basePitch: Int): Int {
        // uiPitch 50 = neutral (voice's default pitchBase(; 0 = -30; 100 = +30
        val pitch = uiPitch.coerceIn(0, 100)
        val base = basePitch.coerceIn(0, 100)
        return if (pitch <= 50) base * pitch / 50 else base + (100 - base) * (pitch - 50) / 50
    }

    // Runs a synthesis body on the single worker thread. If it has not finished
    // in HANG_TIMEOUT_S, log the stuck stack, retire the engine, and return null
    // so THIS request fails fast — a frozen native call cannot take down the whole TTS.
    /** Run synthesis with a watchdog, discarding results invalidated by stop or retirement. */
    private fun synthWithTimeout(block: (SynthWorker) -> ShortArray?): ShortArray? {
        val nowMin = SystemClock.elapsedRealtime()
        if (nowMin < retireUntilMs) {
            Log.w(TAG, "TTS_HANG: zombie grace until " + retireUntilMs + " (" + (retireUntilMs - nowMin) + " ms left); skipping to avoid doubling the retired native worker")
            return null
        }
        val worker = synthWorker
        val requestEpoch = engineEpoch.get()
        val future = try {
            worker.executor.submit<ShortArray?> {
                // Fail closed even if queued before retire:the retired engine's native
                // worker may still own the session; no queued output may escape while retired.


 
                if (requestEpoch != engineEpoch.get() || synthWorker !== worker || SystemClock.elapsedRealtime() < retireUntilMs) {
                    Log.w(TAG, "TTS_HANG: retired engine; dropping queued synthesis")
                    null
                } else {
                val r = try {
                    block(worker)
                } finally {
                    // A worker retired by rotateEngine (or shutdown()'s replacement( frees its
                    // own cached handles here, on its own thread, AFTER the in-flight native call has
                    // returned -- nativeShutdown is not cross-thread-safe, so never free them from the
                    // replacement worker's thread. Idempotent: a queued shutdown cleanup (or an
                    // already-cleared cache) makes this a no-op.

                    if (worker.retired || synthWorker !== worker) {
                        closeHandles(worker)
                    }
                }
                r
            }
        }
} catch (e: RejectedExecutionException) {
            // A rejected submit never created the future, so there's nothing to cancel.

            Log.e(TAG, "TTS_HANG: worker rejected — rotating", e)
            rotateEngine()
            retireUntilMs = SystemClock.elapsedRealtime() + ZOMBIE_GRACE_MS
            return null
        }
        return try {
            future.get(HANG_TIMEOUT_S, TimeUnit.SECONDS).takeIf { requestEpoch == engineEpoch.get() }
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
    /** Replace the worker and invalidate its queued work while deferring active handle cleanup. */
    private fun rotateEngine() {
        val worker = synthWorker
        worker.retired = true
        try {
            worker.executor.shutdownNow()
        } catch (e: Exception) {
            Log.e(TAG, "rotate: shutdown failed", e)
        }
        // JNI stop/shutdown are not safe while the retired worker is in native code.
        // Leave its handles with it; the replacement worker owns a separate cache.
        engineEpoch.incrementAndGet()
        lifecycleEpoch.incrementAndGet()
        // A fresh worker starts with empty per-worker caches; fresh handles begin at
        // the engine-default voice, so no stale voice/param state can leak across an open.

        synthWorker = SynthWorker()
    }
}
