package com.xw.vvtts.services

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
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.VoiceProfile
import java.util.Locale

/**
 * Eloquence TTS service (openevv port, single engine).
 * 11 dialects are linked in this build: en-US/en-GB/de-DE/fr-FR/fr-CA/
 * es-ES/es-US/es-MX/it-IT/ja-JP/pl-PL. (zh/pt/fi/ko are not linked here.)
 */
class VvTtsService : TextToSpeechService() {
    private var engine: EloquenceEngine? = null     // openevv ECI engine (linked dialects only)
    private var voiceConfig: VoiceConfig? = null
    private var voiceProfile: VoiceProfile? = null
    private var deviceCtx: Context? = null
    // Settings are mirrored once at startup and re-read only when something
        // actually changed. UI edits land via the prefs listener. Re-reading per
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
        if (getSystemService(UserManager::class.java).isUserUnlocked()) mirrorPrefsToDevice(device)
        voiceConfig = VoiceConfig(device)
        voiceProfile = VoiceProfile(device)
        deviceCtx = device
        refreshSettings()
        registerAllPrefsListeners(device)
        registerAllPrefsListeners(applicationContext!!)
        engine = EloquenceEngine(device)
        engine!!.setVoiceProfile(voiceProfile)
        val ok = engine!!.initialize()
        // Restore the language-detection settings from device-protected storage
        restoreLanguageSettings(device.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        // Preload Lingua (background thread)
        LanguageDetector.preloadLingua()
        // Warm the engine handle for the user's fixed dialect so the first
        // utterance skips the native LPC load (biggest hover-to-speech delay(.
        engine!!.warmupDialect(LanguageDetector.getFixedDialect())
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
        try {
            if (engine != null) engine!!.stop()
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


    private fun refreshSettings() {
        if (!settingsDirty) return
        // Clear BEFORE re-reading: a prefs write that lands during the read
        // sets dirty=true again via the listener, so it must not be wiped by a
        // stale clear afterthe read completes (lost-update race(.
        settingsDirty = false
        try {
            val unlocked = getSystemService(UserManager::class.java).isUserUnlocked()
            val active = if (unlocked) {
                applicationContext
            } else {
                deviceCtx
            } ?: return
            voiceConfig = VoiceConfig(active)
            voiceProfile = VoiceProfile(active)
            restoreLanguageSettings(active.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
            val profile = voiceProfile
            if (profile != null) engine?.setVoiceProfile(profile)
        } catch (t: Throwable) {
            // Re-arm the dirty flag: we cleared it before reading, but the
            // read failed, so the next utterance must retry.
            settingsDirty = true
            Log.e(TAG, "refreshSettings failed", t)
        }
    }

    override fun onGetLanguage(): Array<String> {
        // Keep in sync with onGetVoices / onIsLanguageAvailable /
        // onGetDefaultVoiceNameFor (ISO 639-1); only dialects actually
        // linked in this build are listed.

        return arrayOf(
            "en", "de", "fr", "es", "it", "ja", "pl", "pt", "fi", "zh"
        )
    }



    override fun onGetDefaultVoiceNameFor(language: String, country: String, variant: String): String {
        val lang = (language ?: "").lowercase()
        val c = (country ?: "").uppercase()
        if (lang.startsWith("en")) return if ("GB" == c) "en-GB" else "en-US"
        if (lang.startsWith("de")) return "de-DE"
        if (lang.startsWith("fr")) return if ("CA" == c) "fr-CA" else "fr-FR"
        if (lang.startsWith("es")) return when (c) { "US" -> "es-US"; "MX" -> "es-MX"; else -> "es-ES" }
        if (lang.startsWith("it")) return "it-IT"
        if (lang.startsWith("ja")) return "ja-JP"
        if (lang.startsWith("pl")) return "pl-PL"
        if (lang.startsWith("pt")) return if ("BR" == c) "pt-BR" else "pt-PT"
        if (lang.startsWith("fi")) return "fi-FI"
        if (lang.startsWith("zh")) return "zh-CN"
        return "en-US"
    }

    override fun onGetVoices(): List<Voice> {
        val voices = ArrayList<Voice>()
        // Voice names use BCP-47; Locale matches the dialect; feature=null = plain
        voices.add(Voice("en-US", Locale.US,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("en-GB", Locale.UK,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("de-DE", Locale.GERMANY,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("fr-FR", Locale.FRANCE,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("fr-CA", Locale.CANADA_FRENCH,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("es-ES", Locale("es", "ES"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("es-US", Locale("es", "US"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("es-MX", Locale("es", "MX"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("it-IT", Locale.ITALY,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("ja-JP", Locale.JAPAN,
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("pl-PL", Locale("pl", "PL"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("pt-BR", Locale("pt", "BR"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("fi-FI", Locale("fi", "FI"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        voices.add(Voice("zh-CN", Locale("zh", "CN"),
            Voice.QUALITY_HIGH, Voice.LATENCY_HIGH, false, null))
        // Only advertise dialects actually linked in this build (build_native.sh LANGS)
        return voices
    }

    override fun onIsLanguageAvailable(language: String, country: String, variant: String): Int {
        if (language == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val lang = language.lowercase()
        val supported = lang.startsWith("en") || lang.startsWith("de")
                || lang.startsWith("fr") || lang.startsWith("es") || lang.startsWith("it")
                || lang.startsWith("ja") || lang.startsWith("pl") || lang.startsWith("pt") || lang.startsWith("fi")
                || lang.startsWith("zh")
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED

        // has country/variant -> COUNTRY_VAR_AVAILABLE; language only -> AVAILABLE
        val hasCountry = country != null && country.isNotEmpty()
        val hasVariant = variant != null && variant.isNotEmpty()
        if (hasCountry || hasVariant) return TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (hasCountry) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(language: String, country: String, variant: String): Int {
        return onIsLanguageAvailable(language, country, variant)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        // Screen-reader pacing:a new request supersedes any queued-but-not-yet-
        // started utterance. TalkBack swipes enqueue faster than playback drains;
        // a plain FIFO piles up an unbounded backlog until speech trails focus
        // by 10-20s and breaks ("keeps up a moment, then dies"(. In-flight
        // speech still runs to completion (the engine cannot abandon it(; only jobs
        // waiting behind it -- thus already stale -- are dropped.
        bumpGeneration()
        val g = generation
        deliveryExecutor.execute(Runnable {
            if (g == generation) {
                runSynthesis(request, callback, g)
            } else {
                // Keep the framework's utterance contract: silently complete dropped
                // jobs so the client isn't left waiting on onSynthesizeText.
                try {
                    callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callback.done()
                } catch (ignore: Throwable) {}
            }
        })
    }

    private fun runSynthesis(request: SynthesisRequest, callback: SynthesisCallback, g: Int) {
        var text: String? = request.text
        Log.d("VvTtsService", "synth voice='" + request.voiceName + "' lang=" + request.language)

        // The system TTS language picker passes the chosen voice in request.voiceName.

        // A zh picker row iso honored per-utterance (and reverted in finally):the
                // engine speaks zh via its oracle bank, so a zh voice must be pinned for
                // that utterance;the app's own detection/default stays untouched
        val savedDefault = LanguageDetector.getDefaultLanguage()
        val savedLangs = LanguageDetector.getEnabledLanguages()
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


            LanguageDetector.setEnabledLanguages(LanguageDetector.getEnabledLanguages() + "zh")


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

            refreshSettings()
        // Auto-detect + chunk
            val segments = LanguageDetector.segment(text)
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
            val pitch = clamp(voiceConfig!!.pitch.coerceIn(0,100) + (sysPitch - 100) / 2, 0, 100)
            val volume = voiceConfig!!.volume

            val pace = Pace(engine!!.getCoreSampleRate())
            for (seg in segments) {
                                // A superseded utterance stops delivering new chunks:the framework
                // audio already handed keeps playing; we just stop feeding it so the
                // newest request speaks promptly instead of waiting out the prior
                // utterance's real-time pacing remainder ( the ~10s lag on fast
                // lock->unlock->swipe). In no case is start+done left dangling( finally(.
                if (g != generation) break
                if (seg.text == null || seg.text!!.trim().isEmpty()) continue
                var segText: String = seg.text!!
                Log.i("VvTtsService", "seg 0x" + Integer.toHexString(seg.dialect) + " '" + segText + "'")
                // CJK normalization (width + number + symbol readings) happens once,
                // inside EloquenceEngine.preprocess for zh segments. Do not repeat it
                // here: a second pass after symbols were expanded defeats the
                // date/time boundary detection (2024-03-15 -> mangled readings).
                val expanded = when (seg.dialect) {
                    LanguageDetector.DIALECT_ZH_CN -> EmojiExpanderZhHans.expand(segText)
                    LanguageDetector.DIALECT_ZH_TW -> EmojiExpanderZhHant.expand(segText)
                    else -> EmojiExpander.expand(segText)
                }
                if (expanded != null && expanded.isNotEmpty()) {
                    segText = expanded
                }
                val pcm = engine!!.synthesizeCore(segText, seg.dialect, volume, preset, pitch, rate)
                if (pcm != null && pcm.size > 0) {
                    if (!started) {
                        callback.start(engine!!.getCoreSampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1)
                        started = true
                    }
                    val bytes = shortsToBytes(pcm)
                    val max = callback.maxBufferSize
                    // Pace the handoff: never run more than 300 ms of audio ahead of
                    // playback  otherwise swipes/stops drown in the framework's queue
                    var offset = 0
                    while (offset < bytes.size) {
                        if (g != generation) break
                        val len = Math.min(max, bytes.size - offset)
                        callback.audioAvailable(bytes, offset, len)
                        offset += len
                        pace.handed(len)
                        hold(pace, g)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onSynthesizeText failed", e)
        } finally {
                    // Revert the per-utterance override (preserve app-pref state)
                    LanguageDetector.setDefaultLanguage(savedDefault)
                    LanguageDetector.setEnabledLanguages(savedLangs)
                    LanguageDetector.setFixedDialect(savedFixed)
                    try {
                        // Playback contract: start() must precede done((),the framework throws
                        // otherwise. One pair per utterance  every path funnels here, so
                        // all silent/empty/early returns get the pair exactly once
                        if (!started) {
                            callback.start(EloquenceEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        }
                        callback.done()
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
    @Volatile private var generation =  0
        // The pacing hold waits on this monitor so a generation bump wakes it,and
        // the superseded utterance stops pacing at real-time ( the 10s lag on fast
        // lock->unlock->swipeand the first-boot lock-screen silence(.
        private val paceLock = Object()
        private fun bumpGeneration() {
            generation++
            synchronized(paceLock) { paceLock.notifyAll() }
        }
            override fun onStop() {
            bumpGeneration()
            if (engine != null) engine!!.stop()
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
            val all =from.getAll() ?: return
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
            e.apply()
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

        private fun hold(pace: Pace, g: Int) {
            // Wait until the playhead is caught up -- or a generation bump wakes us;
            // the bump returns hold early, so the caller's gate check aborts the
            // superseded utterance instead of sleeping out its real-time remainder
            // (that sleep is what caused the ~10s lag and the first-boot lock-screen silence(.
            synchronized(paceLock) {
                var over = pace.aheadMs() - PACE_LEAD_MS
                while (over > 0L && g == generation) {
                    try {
                        paceLock.wait(minOf(over, 20L))
                    } catch (interrupted: InterruptedException) {
                        break
                    }
                    over = pace.aheadMs() - PACE_LEAD_MS
                }
            }
        }

        companion object {
            private const val TAG = "VvTtsService"
            private const val PREFS_NAME = "vvtts_lang_settings"
            // SharedPreferences files mirrored to device-protected storage for lock-screen starts.

            private const val VOICE_CONFIG_PREFS = "vvtts_prefs"
            private const val VOICE_PROFILE_PREFS = "vvtts_voice_profile"
            // Pacing lead: max audio ms handed ahead of playback. At 300ms the single
            // delivery worker held its thread for the real-time duration of most TalkBack
            // utterances, so fast swipes stacked behind the previous utterance's playback (
            // the clogged-swipe ~1s+ symptom(. The factory hands audio at synth speed with
            // no pacing hold at all; 3000ms covers TalkBack-sized utterances so the worker
            // hands off instantly for them, while capping buffered audio ~265KB for longer reads..
            private const val PACE_LEAD_MS = 3000L
        }
    }
