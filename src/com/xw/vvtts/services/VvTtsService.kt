package com.xw.vvtts.services

import java.util.concurrent.atomic.AtomicLong
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.UserManager
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.xw.vvtts.engine.EloquenceEngine
import com.xw.vvtts.utils.EmojiExpander
import com.xw.vvtts.utils.EmojiExpanderZhHans
import com.xw.vvtts.utils.EmojiExpanderZhHant
import com.xw.vvtts.utils.LanguageDetector
import com.xw.vvtts.utils.NgramScorer
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.VoiceProfile
import java.io.File
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.RejectedExecutionException

/**
 * Eloquence TTS service (openevv port, single engine).
 * 11 dialects are linked in this build: en-US/en-GB/de-DE/fr-FR/fr-CA/
 * es-ES/es-US/es-MX/it-IT/ja-JP/pl-PL. (zh/pt/fi/ko are not linked here.)
 */
class VvTtsService : TextToSpeechService() {
    private var engine: EloquenceEngine? = null     // openevv ECI engine (linked dialects only)
    private val engineCallLock = Any()          // guards lifecycle stop/warmup dispatch
    // #12: engineRefLock guards only the engine *reference* — never the long native
    // waits — so lifecycle teardown never blocks behind a synthesis parked in
    // native code for up to HANG_TIMEOUT_S.
    private val engineRefLock = Any()
    private fun currentEngine(): EloquenceEngine? = synchronized(engineRefLock) { engine }
    @Volatile private var voiceConfig: VoiceConfig? = null
    @Volatile private var voiceProfile: VoiceProfile? = null
    private var deviceCtx: Context? = null
    // BCP-47 tags advertised by onGetVoices; onLoadVoice accepts exactly these.
    private val shippedVoiceTags: List<String> = listOf(
        "en-US", "en-GB", "de-DE", "fr-FR", "fr-CA", "es-ES", "es-US", "es-MX",
        "it-IT", "ja-JP", "pl-PL", "pt-BR", "fi-FI", "zh-CN",
    )
    // Settings are mirrored once at startup and re-read only when something
        // actually changed. Cross-process UI edits are caught via the shared_prefs dir mtime. Re-reading per
        // utterance re-built VoiceConfig and VoiceProfile and re-applied language state,
        // which could drop a per-utterance zh pin on EVERY TalkBack swipe; the
        // dirty gate removes that whole per-swipe cost.
    @Volatile private var settingsDirty = true
    private val onPrefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsDirty = true }

    // The dirty gate only re-reads when a relevant prefs file actually changed.

    // refreshSettings() re-reads three prefs files: voice config, voice profile,
    // language state. Re-reading them on every swipe costs CPUand the
    // language-state re-apply can drop a per-utterance zh pin, so register
    // dirty listeners on all three from both the device-protected storage and
    // the credential store, so any UI edit marks settings dirty exactly once.

    private fun registerAllPrefsListeners(ctx: Context?) {
        if (ctx == null) return
        val names = arrayOf(VOICE_CONFIG_PREFS, VOICE_PROFILE_PREFS, PREFS_NAME)
        for (n in names) {
            try {
                ctx.getSharedPreferences(n, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onPrefsChanged)
            } catch (ignore: Throwable) {
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        // Direct Boot: speak on the lock screen ( before first unlock(.
        // Settings live in credential-encrypted storage until the user unlocks, so
        // mirror them into device-protected storage whenever we start unlocked
        // a locked start before the first unlock uses defaults, like evvdroid does. The
        // engine writes only eci.ini to filesDir/eloquence/, going to the same
        // device-protected area keeps it writable while locked ( voice banks are in the .so ).
        val device = createDeviceProtectedStorageContext()
        if (getSystemService(UserManager::class.java)?.isUserUnlocked == true) mirrorPrefsToDevice(device)
        voiceConfig = VoiceConfig(device)
        voiceProfile = VoiceProfile(device)
        deviceCtx = device
        refreshSettings()
        registerAllPrefsListeners(device)
        registerAllPrefsListeners(applicationContext!!)
        val eng = acquireProcessEngine(device)
        engine = if (eng.isInitialized()) eng else null
        eng.setVoiceProfile(voiceProfile)
        val ok = eng.isInitialized()
        // Restore the language-detection settings from device-protected storage
        restoreLanguageSettings(device.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        // Preload Lingua (background thread(
        LanguageDetector.preloadLingua()
        // Preload the in-RAM n-gram tables (66KB asset,milliseconds(--primary detector
        // for Latin runs means Lingua only fires on ambiguous text.
        Thread { NgramScorer.load(this) }.start()
                // Warm the engine handle for the user's fixed dialect
        // utterance skips the native LPC load (biggest hover-to-speech delay(.
        // Background thread: binder queries (getLanguage/getVoices/onInit reply)
        // can arrive while onCreate is still running; a long synchronous warmup
        // would delay the init state the framework builds from. Synthesis locks
        // engineCallLock, so a concurrent warmup is safe.
        if (eng.isInitialized()) {
            val warmDialect = try {
                LanguageDetector.getFixedDialect()
            } catch (t: Throwable) {
                Log.w(TAG, "fixed dialect query failed; warming default", t)
                LanguageDetector.DIALECT_EN_US
            }
            val warmEngine = eng
            Thread {
                try {
                    synchronized(engineCallLock) { warmEngine.warmupDialect(warmDialect) }
                } catch (t: Throwable) {
                    Log.w(TAG, "background warmup failed", t)
                }
            }.apply { isDaemon = true }.start()
        }
        Log.e(TAG, "onCreate engine initialized=$ok")
    }

    override fun onDestroy() {
        // Drop every queued utterance on teardown. The delivery executor's
        // thread keeps draining queued jobs even after the service dies ( hot-swap
        // tears the service down via unbind/destroy without any stop(,( so the
        // generation gate is the only thing that keeps them from flushing seconds
        // later: the reported "two voices at once" / "1-10s dead time" /
        // lock-screen speech arriving late on unlock. Reboot clears them naturally
        // ( process death kills the queue(; unbind/destroy must too.
        bumpGeneration()
        // No aggressive shutdown: TextToSpeechService gets created/destroyed,
        // aggressive shutdown would force the native engine to reload repeatedly (process restarts are expensive).)
        // Let GC reclaim; a leaked engine handle is acceptable (the handle lives as long as the service process etc.).
        stopping = true
        deliveryExecutor.shutdown()
        try {
            deliveryExecutor.awaitTermination(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (ignore: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            synchronized(engineCallLock) { if (engine != null) engine!!.stop() }
        } catch (ignore: Throwable) {
        }
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Engine hot-swap ( switching TTS engines in Settings/at lock/unlock(
        // unbinds the old engine without calling onStop(;drop every queued
        // utterance so nothing flushes seconds later when the user is already
        // elsewhere (the "two voices overlapping" and "late 1-10s speech"
        // reports(.
        bumpGeneration()
        return super.onUnbind(intent)
    }


    private fun prefsDirs(): List<File> {
        val dirs = mutableListOf(File(applicationContext.getDataDir(), "shared_prefs"))
        deviceCtx?.let { dirs += File(it.getDataDir(), "shared_prefs") }
        return dirs
    }

    /** Records preference directory timestamps and reports changes, including the first observation. */
    private fun prefsDirsChanged(): Boolean = synchronized(prefsDirLock) {

        var changed = false
        // Lock-state flips switch the active storage context;re-read even when
        // the dir/file timestamps happen to match ( stale values after a config change(.
        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (lastLockState == null || lastLockState != unlocked) {
            changed = true
            lastLockState = unlocked
        }
        val prefsFiles = arrayOf(VOICE_CONFIG_PREFS, VOICE_PROFILE_PREFS, PREFS_NAME)
        for (dir in prefsDirs()) {
            val dirKey = dir.absolutePath
            val m = dir.lastModified()
            val prev = prefsDirMtimes[dirKey]
            if (prev == null) { prefsDirMtimes[dirKey] = m; changed = true } else if (m != prev) { prefsDirMtimes[dirKey] = m; changed = true }
            // Directory mtimes only move on entry create/delete/rename;,not in-place
            // content writes;(track the XML files themselves so Settings UI edits still
            // land as a change(.
            for (name in prefsFiles) {
                val pf = File(dir, "$name.xml")
                val pm = pf.lastModified()
                val key = "$dir|$name"
                val pprev = prefsFileMtimes[key]
                if (pprev == null) { prefsFileMtimes[key] = pm; changed = true } else if (pm != pprev) { prefsFileMtimes[key] = pm; changed = true }
            }
        }
        changed
    }

    private val prefsDirLock = Any()
    private var lastLockState: Boolean? = null
    private val prefsDirMtimes = HashMap<String, Long>()
    private val prefsFileMtimes = HashMap<String, Long>()

    private fun refreshSettings() { synchronized(prefsDirLock) {
        if (!settingsDirty && !prefsDirsChanged()) return
        // Clear BEFORE re-reading: a prefs write that lands during the read
        // sets dirty=true again via the listener, so it must not be wiped by a
        // stale clear afterthe read completes (lost-update race(.
        settingsDirty = false
        try {
            val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
            val active = if (unlocked) {
                applicationContext
            } else {
                deviceCtx
            } ?: return
            voiceConfig = VoiceConfig(active)
            voiceProfile = VoiceProfile(active)
            restoreLanguageSettings(active.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
            val profile = voiceProfile
            if (profile != null) engine?.setVoiceProfile(profile) else {}
        } catch (t: Throwable) {
            // Re-arm the dirty flag: we cleared it before reading, but the
            // read failed, so the next utterance must retry.
            settingsDirty = true
            Log.e(TAG, "refreshSettings failed", t)
        }
        }
    }

    // Never let a voice query escape to the binder: an exception thrown in
    // ANY of these methods (cold-init NPE, disk read, parse( is marshalled
    // into the AIDL reply as an exception-status and the Android 17 Settings
    // page dies decoding it (Parcel.createExceptionOrNull -> Collection.toArray(.
    /** Runs a voice query and logs any thrown failure before returning [fallback]. */
    private inline fun <T> voiceSafe(fallback: T, block: () -> T): T {
        return try { block() } catch (t: Throwable) {
            Log.w(TAG, "voice query failed;returning safe fallback", t)
            fallback
        }
    }
    /** Returns the active language, country and variant, falling back to English if the query fails. */
    override fun onGetLanguage(): Array<String> {
        return voiceSafe(arrayOf("en", "US", "")) {
            // Framework contract: exactly 3 elements [language, country, variant].
            // Static answer on purpose: binder language queries can arrive before
            // or while onCreate initializes, and Android 15+ Settings/TalkBack
            // decode ANY thrown exception-status from getLanguage/getVoices as a
            // fatal parcel NPE. The spoken voice is set per-utterance in
            // runSynthesis, so the init-time answer stays constant en-US.
            arrayOf("en", "US", "")
        }
    }



    /** Maps the requested language and country to a supported voice name, defaulting to en-US. */
    override fun onGetDefaultVoiceNameFor(language: String?, country: String?, variant: String?): String {
        return voiceSafe("en-US") {
        val lang = (language ?: "").lowercase()
        val c = (country ?: "").uppercase()
        if (lang.startsWith("en")) return if ("GB" == c) "en-GB" else "en-US"
        if (lang.startsWith("de")) return "de-DE"
        if (lang.startsWith("fr")) return if ("CA" == c) "fr-CA" else "fr-FR"
        if (lang.startsWith("es")) return when (c) { "US" -> "es-US"; "MX" -> "es-MX"; else -> "es-ES" }
        if (lang.startsWith("it")) return "it-IT"
        if (lang.startsWith("ja")) return "ja-JP"
        if (lang.startsWith("pl")) return "pl-PL"
        if (lang.startsWith("pt")) return "pt-BR"
        if (lang.startsWith("fi")) return "fi-FI"
        if (lang.startsWith("zh")) return "zh-CN"
        return "en-US"
        }
    }

    /** Returns the advertised offline voices, or an empty list if building the catalog fails. */
    override fun onGetVoices(): List<Voice> {
        // Dependency-free catalog with NON-NULL features: Voice.parceling converts
        // features to ArrayList, and null features have been observed corrupting
        // the binder reply on Android 15+ (Settings/TalkBack then die decoding a
        // poisoned parcel: NPE Collection.toArray() in Parcel.createExceptionOrNull).
        return voiceSafe(emptyList<Voice>()) {
            val voices = ArrayList<Voice>(14)
            voices.add(Voice("en-US", Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("en-GB", Locale.UK, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("de-DE", Locale.GERMANY, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("fr-FR", Locale.FRANCE, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("fr-CA", Locale.CANADA_FRENCH, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("es-ES", Locale("es", "ES"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("es-US", Locale("es", "US"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("es-MX", Locale("es", "MX"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("it-IT", Locale.ITALY, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("ja-JP", Locale.JAPAN, Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("pl-PL", Locale("pl", "PL"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("pt-BR", Locale("pt", "BR"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("fi-FI", Locale("fi", "FI"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            voices.add(Voice("zh-CN", Locale("zh", "CN"), Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, emptySet()))
            // Only advertise dialects actually linked in this build (build_native.sh LANGS).
            voices
        }
    }

    /** Reports the supported language detail level, or LANG_NOT_SUPPORTED for unknown languages or failures. */
    override fun onIsLanguageAvailable(language: String?, country: String?, variant: String?): Int {
        return voiceSafe(TextToSpeech.LANG_NOT_SUPPORTED) {
        if (language == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val lang = language.lowercase()
        val supported = lang.startsWith("en") || lang.startsWith("de")
                || lang.startsWith("fr") || lang.startsWith("es") || lang.startsWith("it")
                || lang.startsWith("ja") || lang.startsWith("pl") || lang.startsWith("pt") || lang.startsWith("fi")
                || lang.startsWith("zh")
        if (lang.startsWith("zh") && country != null && country.equals("TW", ignoreCase = true)) return TextToSpeech.LANG_NOT_SUPPORTED
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED

        // has country/variant -> COUNTRY_VAR_AVAILABLE; language only -> AVAILABLE
        val hasCountry = country != null && country.isNotEmpty()
        val hasVariant = variant != null && variant.isNotEmpty()
        if (hasCountry && hasVariant) return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (hasCountry) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        return TextToSpeech.LANG_AVAILABLE
        }
    }

    /** Returns the availability of the requested language without loading a native voice. */
    override fun onLoadLanguage(language: String?, country: String?, variant: String?): Int {
        return voiceSafe(TextToSpeech.LANG_NOT_SUPPORTED) {
        return onIsLanguageAvailable(language, country, variant)
        }
    }

    /** Queues synthesis with the current cancellation generation; reports an error if shutdown rejects it. */
    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val schedAt = SystemClock.elapsedRealtime()
        try {
            val gen = generation.get()  // snapshot: a stop() while queued must drop this task
            deliveryExecutor.execute(Runnable { runSynthesis(request, callback, gen, schedAt) })
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "service shutting down;dropping utterance", e)
            // Framework contract: every onSynthesizeText must terminate the
            // callback. runSynthesis never runs on this path, so no start()/done()
            // pair can fire — error() is the designated failure termination.
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    /**
     * Synthesizes and delivers paced audio for a queued request, rejecting stale cancellation generations.
     *
     * @param gen Cancellation generation captured when the request was queued.
     * @param schedAt Elapsed realtime in milliseconds when the request was scheduled, used for timing logs.
     */
    private fun runSynthesis(request: SynthesisRequest, callback: SynthesisCallback, gen: Long, schedAt: Long) {
        val t0 = SystemClock.elapsedRealtime()
        Log.i("SPD", "dequeued dt=" + (t0 - schedAt) + "ms gen=" + gen)
        var text: String? = request.charSequenceText?.toString()
        // A stop() bumped the generation: this utterance was queued before the
        // stop, the framework already canceled it, so it must not speak (ghost
        // speech after cancellation) and must not clear the stopping flag.
        // error() is the designated failure termination (never started).
        if (gen != generation.get()) {
            if (stopping) {
                Log.w(TAG, "dropping stale utterance queued before stop (gen $gen != $generation)")
                try {
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                } catch (ignore: Throwable) {
                }
            } else {
                Log.d(TAG, "utterance superseded mid-queue; gen=" + gen + " generation=" + generation.get())
                silentComplete(callback)
            }
            return
        }
        // The stop flag is cleared only here, once the utterance actually
        // dequeues:clearing it in onSynthesizeText() would let previous
        // utterance (still draining on the single-thread executor( resume
        // after a TalkBack re-swipes, causing overlapping speech.
        stopping = false
        // An onStop() can race in between the queue-time generation snapshot
        // (taken in onSynthesizeText()and this reset:it bumps generationand
        // sets stopping = true. Re-check so a just-issued cancellation isn't
        // cleared, which would let a stale utterance drain in full (ghost speech(.
        // Restore the stop flag and drop the utterance via error() instead.
        if (gen != generation.get()) {
            if (stopping) {
                Log.w(TAG, "stop raced the stop flag reset; gen=" + gen + " generation=" + generation.get())
                try {
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                } catch (ignore: Throwable) {}
            } else {
                Log.d(TAG, "utterance superseded mid-queue after reset; gen=" + gen + " generation=" + generation.get())
                silentComplete(callback)
            }
            return
        }
        Log.d("VvTtsService", "synth voice='" + request.voiceName + "' lang='" + request.language + "'")
                if (getSharedPreferences(VOICE_CONFIG_PREFS, MODE_PRIVATE).getBoolean("extra_logging", false)) {
                    Log.i("VvTtsX", "utterance voice=" + request.voiceName + " lang=" + request.language + " text_len=" + (request.charSequenceText?.length ?: 0))
                }

        // The system TTS language picker passes the chosen voice in request.voiceName.

        // A zh picker row iso honored per-utterance (and reverted in finally):the
                // engine speaks zh via its oracle bank, so a zh voice must be pinned for
                // that utterance;the app's own detection/default stays untouched
        synchronized(engineCallLock){
        refreshSettings()
        synchronized(LanguageDetector.stateLock) {
        val savedDefault = LanguageDetector.getDefaultLanguage()
        val savedFixed = LanguageDetector.getFixedDialect()
        val voiceName = request.voiceName
        val appVoice = voiceConfig?.voice
        // A zh voice from the *system picker* always pins zh (explicit user
        // choice(;the app's own "Voice" row only pins zh when detection is OFF
        // with Auto ON the spoken-voice locale must follow the detected text,
        // otherwise choosing "Auto detect" after a zh voice pick would read
        // every language as Chinese (the reported bug).
        val autoDetect = LanguageDetector.isDetectionEnabled()
        val zhRequested = (voiceName != null && voiceName.lowercase().startsWith("zh"))
            || (voiceName.isNullOrBlank() && !autoDetect && appVoice != null && appVoice.lowercase().startsWith("zh"))
        if (zhRequested) {

            LanguageDetector.setDefaultLanguage(EloquenceEngine.DIALECT_ZH_CN)


            LanguageDetector.setTransientEnabledLangs(LanguageDetector.getEnabledLanguages() + "zh")


            LanguageDetector.setFixedDialect(EloquenceEngine.DIALECT_ZH_CN)


        }

        // Pin user voice rows into base dialect (system picker first;else app row.
        if (!zhRequested) {
            val pv = voiceName ?: appVoice

            if (pv != null) {
                val pd = VoiceConfig.findLang(pv).eciDialect

                if (pd != 0L) {
                    LanguageDetector.setDefaultLanguage(pd.toInt())
                    if (!LanguageDetector.isDetectionEnabled()) LanguageDetector.setFixedDialect(pd.toInt())
                }
            }
        }

        var started = false
        try {
            if (text == null || text.isEmpty()) {
                return  // finally emits the start+done pair for an empty utterance
            }

            // emoji expansion now happens per-segment, pitched to the segment's detected dialect

        // Auto-detect + chunk
            val segments = LanguageDetector.segment(text)
            Log.i("SPD", "segmented n=" + segments.size + " dt=" + (SystemClock.elapsedRealtime() - t0) + "ms")
            for (seg in segments) {
                Log.i("VvTts", "seg dialect=" + Integer.toHexString(seg.dialect) + " len=" + seg.text.length)
            }


            if (engine == null || !engine!!.isInitialized()) {
                return  // finally emits the start+done pair for an uninitialized engine
            }

            val preset = if (voiceProfile != null) voiceProfile!!.preset else 1
            // Android passes speech rate/pitch as PERCENTS where 100 = normal
            // (SynthesisRequest.getSpeechRate()/getPitch()). System TTS rate is THE
            // single source of truth (in-app rate slider was removed to avoid offset
            // between app % and system %);engine scale is 100 = neutral, so pass the
            // request rate through directly: TalkBack's speed slider now maps 1:1.
            var sysRate = request.speechRate
                        var sysPitch = request.pitch
                        if (sysRate <=  0) sysRate =  100
                        if (sysPitch <=  0) sysPitch =  100

            val rate = clamp(Math.round(sysRate.toFloat()).toInt(),1,300)
            // 100% (normal) -> engine-neutral 50; TalkBack pitch slider
            // 50-200 -> 25-100 (spans the engine's full +/-30 kona range).
            val cfgVolume = voiceConfig?.volume ?: 100
                        val cfgPitch = voiceConfig?.pitch ?: 100  // #189: never `!!` a nullable service config on the synthesis path
                        val pitch = clamp(cfgPitch.coerceIn(0,100) + (sysPitch - 100) / 2, 0,100)
                        val volume = cfgVolume

            val pace = Pace(engine!!.getCoreSampleRate())
                        val uttDeadline = SystemClock.elapsedRealtime() + UTT_BUDGET_MS
            // Start the framework's audio pipe BEFORE synthesis: first-audio
            // latency must not include the first segment's native synth time.
            // The finally block terminates normal or canceled synthesis.
            if (!started) {
                callback.start(engine!!.getCoreSampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)
                started = true
            }
                        for (seg in segments) {
                            // Bail on stop() only (framework cancel( — generation supersedes are
                            // resolved at dequeue;never cut mid-flight speech(.
                            if (stopping) break
                            if (SystemClock.elapsedRealtime() > uttDeadline) {

                                Log.e(TAG, "utterance truncated: time budget (" + UTT_BUDGET_MS + " ms( exceeded; skipping remaining segments")
                                break
                            }
                if (seg.text == null || seg.text!!.trim().isEmpty()) continue
                var segText: String = seg.text!!
                Log.d("VvTtsService", "seg 0x" + Integer.toHexString(seg.dialect) + " len=" + segText.length)
        if (getSharedPreferences(VOICE_CONFIG_PREFS, MODE_PRIVATE).getBoolean("extra_logging", false)) {
            Log.i("VvTtsService", "seg 0x" + Integer.toHexString(seg.dialect) + " '" + segText + "'")
        }
                // CJK normalization (width + number + symbol readings) happens once,
                // inside EloquenceEngine.preprocess for zh segments. Do not repeat it
                // here: a second pass after symbols were expanded defeats the
                // date/time boundary detection (2024-03-15 -> mangled readings).
                // Synthesize long segments into small, sentence-aware pieces: each
                // piece is one bounded native call (~200 ms worst case), so the
                // stop/generation checkpoint between pieces means a swipe lands
                // within a couple hundred ms instead of waiting out a single
                // uninterruptible synth of the whole segment (the "one second
                // between swipes" / "fast swipes get clogged" regression).
                for (chunkText in splitSynthChunks(segText)) {
                    if (stopping) break
                    if (SystemClock.elapsedRealtime() > uttDeadline) break
                    var textToSynth: String = chunkText
                    val expanded = when (seg.dialect) {
                        LanguageDetector.DIALECT_ZH_CN -> EmojiExpanderZhHans.expand(chunkText)
                        LanguageDetector.DIALECT_ZH_TW -> EmojiExpanderZhHant.expand(chunkText)
                        else -> EmojiExpander.expand(chunkText)
                    }
                    if (expanded != null && expanded.isNotEmpty()) {
                        textToSynth = expanded
                    }
                    val t3 = SystemClock.elapsedRealtime()
                    val pcm = currentEngine()?.synthesizeCore(textToSynth, seg.dialect, volume, preset, pitch, rate)
                    Log.i("SPD", "seg len=" + chunkText.length + " synth_ms=" + (SystemClock.elapsedRealtime() - t3) + " pcm=" + (pcm?.size ?: 0))
                    if (getSharedPreferences(VOICE_CONFIG_PREFS, MODE_PRIVATE).getBoolean("extra_logging", false)) {
                        Log.i("VvTtsX", "chunk chars=" + chunkText.length + " text='" + chunkText + "' rate=" + rate + " pitch=" + pitch + " vol=" + volume + " preset=" + preset)
                    }
                    if (pcm != null && pcm.size > 0) {
                    val bytes = shortsToBytes(pcm)
                    val max = callback.maxBufferSize
                    // Pace the handoff: never run more than PACE_LEAD_MS of audio ahead of
                    // playback  otherwise swipes/stops drown in the framework's queue
                    // Guard: a 0/negative buffer-size report from the framework would
                    // make `offset += len` never advance -> infinite loop. Skip
                    // delivery (finally still terminates the pair cleanly).
                    if (max > 0) {
                        var offset = 0
                        while (offset < bytes.size) {
                            if (stopping) break
                            val len = Math.min(max, bytes.size - offset)
                            callback.audioAvailable(bytes, offset, len)
                            offset += len
                            pace.handed(len)
                            hold(pace)
                        }
                    }
                }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onSynthesizeText failed", e)
        } finally {
                    // Revert the per-utterance override (preserve app-pref state)
                    LanguageDetector.setDefaultLanguage(savedDefault)
                    LanguageDetector.setTransientEnabledLangs(null)
                    LanguageDetector.setFixedDialect(savedFixed)
        }
                    if (!started && !stopping && gen == generation.get()) {
                        // Playback contract: start() must precede done(), the framework
                        // throws otherwise. Silent/empty/early returns get the pair
                        // unless canceled, which terminates with error() below.
                        // If start() itself fails, done() would also throw (it
                        // requires a prior start), so error() is the contract's failure
                        // termination -- otherwise the callback is left unterminated.
                        try {
                            callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        } catch (e: Throwable) {
                            Log.e(TAG, "callback.start failed; terminating with error()", e)
                            try {
                                callback.error(TextToSpeech.ERROR_SYNTHESIS)
                            } catch (ignore: Throwable) {
                            }
                            return
                        }
                    }
                    if (stopping || gen != generation.get()) {
                        try {
                            callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        } catch (ignore: Throwable) {
                        }
                        return
                    }
                    try {
                        callback.done()
                    } catch (e: Throwable) {
                        // done() after a successful start must not leave a dangling
                        // utterance: error() is the fallback termination.
                        Log.e(TAG, "callback.done failed; terminating with error()", e)
                        try {
                            callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        } catch (ignore: Throwable) {
                        }
                    }
                }
        }
    }

    /** Terminate a superseded utterance silently:the framework converts
     *  start+done into silent playback, avoiding error() which a screen
     *  reader treats as a failed focus ( re-announce/stall(.
     */
    private fun silentComplete(callback: SynthesisCallback) {
        try {
            callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
        } catch (t: Throwable) {
            try {
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
            } catch (ignore: Throwable) {
            }
        }
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int {
        return if (v < lo) lo else Math.min(v, hi)
    }

    private fun shortsToBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = pcm[i]
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private val deliveryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var stopping = false
    /** Signals the pacing wait in hold(): onStop()/bumpGeneration() notifyAll()
     *  so a cancellation interrupts the artificial audio-duration sleep at once. */
    private val pacingMonitor = Any()
    /** Bumped by onStop(); utterances queued before the bump are stale (the
     *  framework already canceled them) and must not speak after the stop. */
    private val generation = AtomicLong(0)
    /** Drop every queued utterance on teardown/unbind: called from onDestroy
     *  and onUnbind so nothing flushes seconds after the service dies (two-voices
     *  overlap, late lock-screen speech(. */
    private fun bumpGeneration() {
        stopping = true
        generation.incrementAndGet()
        synchronized(pacingMonitor) { (pacingMonitor as Object).notifyAll() }  // wake the pacing wait now
        Log.i("VvTtsX", "generation bump (stop/unbind(: generation=" + generation)
    }
        /** Invalidates queued requests, stops audio delivery and asks the current engine to stop. */
        override fun onStop() {
            stopping = true
            generation.incrementAndGet()  // invalidate utterances already queued pre-stop
            synchronized(pacingMonitor) { (pacingMonitor as Object)..notifyAll() }  // wake the pacing wait so cancellation is immediate
            // stop() queues native work on the engine worker. Generation invalidation
            // stays synchronous,and this callback never waits for native synthesis.
            try { currentEngine()?.stop() } catch (ignore: Throwable) {}
        }

        // Restore language-detection settings from SharedPreferences (device-protected
        // storage when the user is locked; mirrored copy otherwise(.
        private fun restoreLanguageSettings(prefs: SharedPreferences) {
            LanguageDetector.setDetectionEnabled(prefs.getBoolean("detection_enabled", true))
            LanguageDetector.setFixedDialect(prefs.getInt("fixed_dialect", LanguageDetector.DIALECT_EN_US))
            LanguageDetector.setChineseDialect(prefs.getInt("chinese_dialect", LanguageDetector.DIALECT_ZH_CN))
            LanguageDetector.setEnglishDialect(prefs.getInt("english_dialect", LanguageDetector.DIALECT_EN_US))
            LanguageDetector.setSpanishDialect(prefs.getInt("spanish_dialect", LanguageDetector.DIALECT_ES_ES))
            LanguageDetector.setFrenchDialect(prefs.getInt("french_dialect", LanguageDetector.DIALECT_FR_FR))
            LanguageDetector.setDefaultLanguage(prefs.getInt("default_language", LanguageDetector.DEFAULT_UNSPECIFIED))
            val savedLangs = prefs.getStringSet("enabled_langs", null)
            // Fresh install (no pref(: enable every shipped language so everything works
            // with zero configuration; users who pruned keep their set.
            LanguageDetector.setEnabledLanguages(savedLangs ?: LanguageDetector.ALL_LANG_CODES.toSet())
        }

        // === Direct Boot (lock-screen( helpers === Mirror the 3 settings files from
        // credential-encrypted to device-protected storage. Called only while unlocked;
        // the device copy is what a locked start reads ( before first unlock(.
        private fun mirrorPrefsToDevice(device: Context) {
            val names = arrayOf(
                VOICE_CONFIG_PREFS,     // voice/rate/pitch/volume/dsp/auto-detect/punct/user dict
                VOICE_PROFILE_PREFS,    // preset + per-preset param overrides
                PREFS_NAME                  // LanguageDetector state
            )
            for (name in names) {
                try {
                    mirrorSharedPreferences(
                        getSharedPreferences(name, 0),
                        device.getSharedPreferences(name, 0)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "cannot mirror prefs $name", e)
                }
            }
        }

        private fun mirrorSharedPreferences(from: SharedPreferences,to: SharedPreferences) {
                val all = from.getAll() ?: return
                // Never mirror an empty/absent store:that would wipe the last good
                // device snapshot with a blank one;direct-boot access must not assume
                // the modern prefs exist yet ( first unlock may not have happened(.
                if (all.isEmpty()) return
                val e = to.edit()
                e.clear()
                for ((k, v) in all) {
                    when (v) {
                        is String -> e.putString(k, v)
                                                is Boolean -> e.putBoolean(k, v)
                                                is Int -> e.putInt(k, v)
                                                is Long -> e.putLong(k, v)
                                                is Float -> e.putFloat(k, v)
                                                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                        else -> {}
                    }
                }
                // Synchronous+atomic (temp-file write(;the async apply() kill window would
                // leave a half-written device copy on a locked/zoned write.
                val ok = e.commit()
                if (!ok) Log.e(TAG, "mirror commit rejected")
            }


        // === Pacing ===
        /** Track how much audio (in ms( has been handed to the framework versus how
         *  much wall time has passed; hold() keeps the lead under PACE_LEAD_MS so a
         *  swipe/stop never drowns in queued speech.
         */
        private class Pace(private val sampleRate: Int) {
            private val startMs: Long = SystemClock.elapsedRealtime()
            private var handedMs: Long = 0
            fun handed(bytes: Int) { handedMs += bytes / 2L * 1000L / sampleRate }
            fun aheadMs(): Long = handedMs - (SystemClock.elapsedRealtime() - startMs)
        }

        private fun hold(pace: Pace) {
            var over = pace.aheadMs() - PACE_LEAD_MS
            while (over > 0L && !stopping) {
                // Monitor-wait instead of a raw sleep: onStop()/bumpGeneration()
                // notifyAll() so a stop interrupts the artificial pacing period
                // immediately instead of only after the sleep interval ends.
                synchronized(pacingMonitor) {
                    if (!stopping) {
                        var waiterHit = false
                        try {
                            (pacingMonitor as Object).wait(minOf(over, 20L))
                        } catch (ie: InterruptedException) {
                            waiterHit = true
                        }
                        // break/continue inside synchronized() (an inline lambda) is
                        // experimental in Kotlin 1.9 and errors out; re-check in plain scope.
                        if (waiterHit) { break }
                    }
                }
                over = pace.aheadMs() - PACE_LEAD_MS
            }
        }

        companion object {
            private const val TAG = "VvTtsService"
            private const val PREFS_NAME = "vvtts_lang_settings"
            // SharedPreferences files mirrored to device-protected storage for lock-screen starts.

            private const val VOICE_CONFIG_PREFS = "vvtts_prefs"
            private const val VOICE_PROFILE_PREFS = "vvtts_voice_profile"
            // Pacing lead: max audio ms delivered ahead of playback. 300 ms did not
            // cover TalkBack-sized utterances (synthetic gaps between chunks/utterances
            // reappeared(; restored to the pre-merge 3000 ms. hold() polls every ~20ms
            // and exits as soon as stopping trips, so a swipe/stop stays responsive.
                        private const val PACE_LEAD_MS = 3000L
                        // Per-utterance wall-clock budget: if synthesis (hang-cascades, 30s
                        // watchdog rotations stacking behind each other( blows past this, we
                        // truncate rather than let one utterance stall the whole TalkBack pipeline.
                        private const val UTT_BUDGET_MS = 12000L
                        // Synth chunk caps: the first piece is smaller so first audio
                        // lands fast; later pieces cap the worst-case wait for a swipe,
                        // which now lands within one bounded native call instead of a
                        // whole segment.
                        private const val CHUNK_FIRST =  70
                        private const val CHUNK_MAX = 110
                        private const val CHUNK_SENTENCE_GRACE = 120
                        private const val MIN_CHUNK_SENTENCE =  40
                        // Sentence-ending punctuation: ASCII + full-width/Unicode
                        // variants, so localized text splits on the same boundaries..
                        private val SENTENCE_ENDS = ".!?。！？．"
                        /** Central numeric-token rules shared by every cut decision:
                         *  punctuation binding digits is numeric notation (decimal
                         *  point, thousands separator, date/symbol run), never a
                         *  sentence/boundary cut; full-width variants count too. */
                        private fun isNumericBoundary(c: Char, prev: Char, next: Char): Boolean =
                            ((c == '.' || c == '．' || c == '。') && (prev.isDigit() || next.isDigit()))
                                || ((c == ',' || c == '，') && prev.isDigit() && next.isDigit())

                        private fun isNumericRunChar(c: Char): Boolean =
                            c.isDigit() || c == ':' || c == '/' || c == '-' || c == '.' || c == '．'

            // === Process-scoped engine reuse ===
            // TextToSpeechService is created/destroyed each time the framework binds the
            // engine (TalkBack swipe bursts re-bind constantly(. A per-onCreate engine
            // would tear down warm LPC handles and every re-bind would re-pay the full
            // native eciNewEx voice-bank load — the big hover-to-speech delay after any
            // pause. Holding one engine per process keeps all dialects warm: re-binds
            // reuse live handles, first-swipe-after-idle latency drops to near zero.

            private val engineLock = Any()
            @Volatile private var processEngine: EloquenceEngine? = null
            // #16: serialization lock for concurrent engine ops (stop/synthesizeCore/
            // warmupDialect( must be process-scoped too — a per-instance lock would
            // let tworebound instances race the same shared native engine (calling
            // stop() while another instance synthesizes(.
            private val engineCallLock = Any()


            private fun acquireProcessEngine(ctx: Context): EloquenceEngine {
                processEngine?.let { return it }
                synchronized(engineLock) {
                    val cur = processEngine
                    if (cur != null) return cur
                    val fresh = EloquenceEngine(ctx)
                    if (fresh.initialize()) processEngine = fresh
                    return fresh
                }
            }

            /**
             * Split a long segment into sentence-aware pieces capped at CHUNK_MAX
             * chars (the first piece at CHUNK_FIRST so first audio lands fast).
             * Splits only at punctuation/whitespace so words, dates and number
             * runs stay intact - a '-'/'/' or '.' inside a date is never a split
             * point. Used per-utterance so a swipe (stop/generation bump) lands
             * within one bounded native synth call instead of waiting out a
             * giant segment.
             */
                        private fun splitSynthChunks(text: String): List<String> {
                val chunks = mutableListOf<String>()
                val n = text.length
                if (n <= CHUNK_MAX) return listOf(text)
                var start = 0
                while (start < n) {
                    val cap = if (chunks.isEmpty()) CHUNK_FIRST else CHUNK_MAX
                    var end = start + cap
                    if (end >= n) {
                        chunks.add(text.substring(start))
                        break
                    }
                    var cut = -1
                    // Pass A: keep sentences whole - expand the cap up to the nearest real
                    // sentence end (never beyond CHUNK_SENTENCE_GRACE extra chars(, so
                    // phrasing/intonation survive instead of hard mid-sentence cuts. Only
                    // hunt after a minimum length, so short texts still land fast..
                    var i = Math.min(n, end + CHUNK_SENTENCE_GRACE)
                    while (i > start + MIN_CHUNK_SENTENCE) {
                        val c = text[i - 1]
                        val prev = if (i >= 2) text[i - 2] else ' '
                        val next = if (i < n) text[i] else ' '
                        if (SENTENCE_ENDS.indexOf(c) >= 0 && !isNumericBoundary(c, prev, next)) {
                            cut = i
                            break
                        }
                        i--
                    }
                    // Pass B: nearest boundary within the hard cap (word/clause splits
                    // with the same digit guards so dates/numbers survive..
                    if (cut < 0) {
                        var j = end
                        while (j > start + 1 && cut < 0) {
                            val c = text[j - 1]
                            val isBoundary = c == ' ' || c == '\n' || c == '\t' || c == '.' || c == ','
                                || c == ';' || c == '!' || c == '?' || c == '。' || c == '，'
                                || c == '！' || c == '？' || c == '、' || c == '．'
                            if (isBoundary) {
                                val prev = if (j >= 2) text[j - 2] else ' '
                                val next = if (j < n) text[j] else ' '
                                if (!isNumericBoundary(c, prev, next)) cut = j
                            }
                            j--
                        }
                    }
                    // Pass C: no boundary in range: force-cut, backing off digit/symbol
                    // runs so dates/numbers are never split mid-run; never below the
                    // minimum progress size so a lone sliver is never handed out.
                    if (cut < 0) {
                        cut = end
val minCut = start + MIN_CHUNK_SENTENCE
                        while (cut > minCut && isNumericRunChar(text[cut - 1])) {
                            cut--
                        }
                        // Unsplittable run: the run spans past the min floor, so force the
                        // cut at the cap instead (chunks stay near max size, never empty / tiny.
                        if (cut <= minCut) cut = end
                    }
                    // A forced cut can land between a surrogate pair: back off one char so
                    // the pair stays together in the following chunk (valid UTF-16.
                    if (cut > start + 1 && cut < n && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) {
                        cut--
                    }
                    chunks.add(text.substring(start, cut))
                    start = cut
                }
                return chunks
            }
        }
    }
