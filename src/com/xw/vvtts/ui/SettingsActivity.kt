package com.xw.vvtts.ui
import com.xw.vvtts.update.UpdateActions
import com.xw.vvtts.update.UpdateNotifPrefs
import com.xw.vvtts.update.syncFcmTopics

import android.app.Activity
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build

import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.xw.vvtts.R
import com.xw.vvtts.engine.EloquenceEngine
import com.xw.vvtts.update.UpdateNotificationScheduler
import com.xw.vvtts.utils.CrashlyticsFx
import com.xw.vvtts.utils.DictEntry
import com.xw.vvtts.utils.LanguageDetector
import com.xw.vvtts.utils.Telemetry
import com.xw.vvtts.utils.TelemetryPrefs
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.VoiceProfile
import java.util.HashSet

class SettingsActivity : Activity() {
    companion object {
        const val EXTRA_OPEN_UPDATE_DIALOG = "com.xw.vvtts.EXTRA_OPEN_UPDATE_DIALOG"
        private const val REQ_POST_NOTIF = 4073
        private var processEngine: EloquenceEngine? = null
        private const val VOICE_CONFIG_PREFS = "vvtts_prefs"
        private const val PREFS_NAME = "vvtts_lang_settings"
    }
    private var voiceConfig: VoiceConfig? = null
    private var voiceProfile: VoiceProfile? = null
    private var engine: EloquenceEngine? = null
    private var pitchVal: TextView? = null
    private var volumeVal: TextView? = null
    private var pitchBar: SeekBar? = null
    private var volumeBar: SeekBar? = null
    private val charParamIds = intArrayOf(VoiceProfile.PARAM_HEAD_SIZE, VoiceProfile.PARAM_ROUGHNESS, VoiceProfile.PARAM_BREATHINESS, VoiceProfile.PARAM_PITCH_FLUC)
    private val charLabelRes = intArrayOf(R.string.voice_head, R.string.voice_roughness, R.string.voice_breathiness, R.string.voice_inflection)
    private val charBars = arrayOfNulls<SeekBar>(charParamIds.size)
    private val charVals = arrayOfNulls<TextView>(charParamIds.size)
    private var langSwitch: Switch? = null
    private var voiceBtn: Button? = null
    private var presetBtn: Button? = null
    private var refreshingCharSliders = false
    private var presetRow: LinearLayout? = null
    private var charSection: LinearLayout? = null
    private var punctBtn: Button? = null
    private var numberBtn: Button? = null
    private val REQ_PROFILE = 701
    private val REQ_DICT_OPEN = 702
    private val REQ_DICT_CREATE = 703
    private val REQ_POST_NOTIFICATIONS = 704
    /** Switch awaiting the POST_NOTIFICATIONS permission result, null when idle. */
    private var pendingNotifyToggle: Switch? = null
    /** Guards the launch auto-update dialog so it surfaces at most once per process. */
    @Volatile private var launchUpdateDialogShown = false
    /** Initializes settings and the shared engine, builds the settings screen and schedules requested autotests. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        // Tag the main-process session so crashes from the UI process are
        // distinguishable from engine (:tts) process native crashes.
        CrashlyticsFx.setContext(if (Build.SUPPORTED_ABIS.isEmpty()) "unknown" else Build.SUPPORTED_ABIS[0])
        // Parse autotest hook before engine init (hook is only honored in debug builds).
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val autotest = if (debuggable) intent?.getStringExtra("autotest") else null  // #198: never honor external autotest hooks in non-debuggable (release( builds
        var e = processEngine
        if (e == null || !e.isInitialized()) {
            val fresh = EloquenceEngine(applicationContext)
            var initOk = false
            try {
                initOk = fresh.initialize() && fresh.isInitialized()
            } catch (t: Throwable) {
                Log.e("SettingsActivity", "engine initialize() failed", t)
                CrashlyticsFx.record("engine:initialize-failed", t)
            }
            if (initOk) {
                e = fresh
                processEngine = fresh
            } else {
                // Never FC the settings screen because the native engine is
                // unhealthy: toast and continue; the test button surfaces the
                // engine error for diagnosis.
                CrashlyticsFx.record("engine:unhealthy", RuntimeException("EloquenceEngine init returned false"))
                Toast.makeText(this, "TTS engine failed to initialize", Toast.LENGTH_LONG).show()
            }
        }
        engine = e
        if (engine != null) {
            runCatching { engine!!.setVoiceProfile(voiceProfile) }
                    .onFailure { Log.e("SettingsActivity", "setVoiceProfile failed on unhealthy engine", it) }
        }
        // Preload the Lingua detector (background thread; avoids first-synthesis jank)
        LanguageDetector.preloadLingua()
        // Restore language-detection settings from SharedPreferences
        restoreLanguageSettings()
        // Auto-test hook: am start --es autotest german
        if ("german" == autotest) {
            // Legacy hook: compare three English voices (validates voice params)
            Handler(Looper.getMainLooper()).postDelayed({ testGerman() }, 3000)
        } else if ("crash" == autotest) {
            val hkText = intent?.getStringExtra("crashtext")
                    ?: "Hello, this is a speech synthesis test."
            Handler(Looper.getMainLooper()).postDelayed({ testCrash(hkText) }, 3000)
        }
        // Apply the current role config automatically
        
        // ---- SINGLE SCREEN SETTINGS ----
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setPadding(dp(16), dp(16), dp(16), dp(16))
        
        val scroll = ScrollView(this)
        val mainContainer = LinearLayout(this)
        mainContainer.orientation = LinearLayout.VERTICAL
        
        // Title
        val title = TextView(this)
        title.text = getString(R.string.title_main)
        title.textSize = 24f
        title.setTextColor(getColor(R.color.m3_on_surface))
        val tlLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tlLp.setMargins(dp(4), dp(4), 0, dp(8))
        title.layoutParams = tlLp
        mainContainer.addView(title)
        
        // ===== SPEECH SECTION =====
        addSectionHeader(mainContainer, R.string.sec_speech)
        pitchVal = addSeekBar(mainContainer, getString(R.string.pitch), voiceConfig!!.pitch, 0, 100, { pitchBar = it }) { v ->
            voiceConfig!!.setPitch(v)
            pitchVal!!.text = getString(R.string.pitch_fmt, v)
        }
        volumeVal = addSeekBar(mainContainer, getString(R.string.volume), voiceConfig!!.volume, 0, 100, { volumeBar = it }) { v ->
            voiceConfig!!.setVolume(v)
            volumeVal!!.text = getString(R.string.volume_fmt, v)
        }
        
        // ===== VOICE SECTION =====
        addSectionHeader(mainContainer, R.string.sec_voice)
        val presetBtnLocal = addRow(this, mainContainer)
        presetBtn = presetBtnLocal
        presetRow = presetBtnLocal.parent as? LinearLayout
        presetBtnLocal.setOnClickListener {
            startActivityForResult(Intent(this, VoiceProfileActivity::class.java), REQ_PROFILE)
        }
        refreshPresetButton(presetBtnLocal)
        val voiceBtnLocal = addRow(this, mainContainer)
        voiceBtn = voiceBtnLocal
        voiceBtnLocal.setOnClickListener { showVoiceDialog() }
        refreshVoiceButton(voiceBtnLocal)
        val langSwitchLocal = addSwitchRow(mainContainer, getString(R.string.lang_auto_switch), LanguageDetector.isDetectionEnabled()) { on ->
            LanguageDetector.setDetectionEnabled(on)
            saveLanguageSettings()
            refreshLanguageUi()
            voiceBtn?.let { refreshVoiceButton(it) }
        }
        langSwitch = langSwitchLocal
        refreshLanguageUi()
        val testBtnLocal = addFilledButton(mainContainer, R.string.test, dp(16))
        testBtnLocal.setOnClickListener { testSpeech() }
        
        // ---- Voice character: head size/roughness/breathiness/inflection (IBMTTS-style) ----
        val charSectionLocal = LinearLayout(this)
        charSectionLocal.orientation = LinearLayout.VERTICAL
        addSectionHeader(charSectionLocal, R.string.sec_voice_char)
        addVoiceCharSliders(charSectionLocal)
        addNoteRow(charSectionLocal, R.string.voice_char_note)
        val resetPresetBtnLocal = addRow(this, charSectionLocal)
        resetPresetBtnLocal.text = getString(R.string.reset_preset_button)
        resetPresetBtnLocal.setOnClickListener {
            val vp = voiceProfile
            if (vp == null) return@setOnClickListener
            vp.resetPreset(vp.preset)
            refreshVoiceCharSliders()
            Toast.makeText(this, getString(R.string.reset_preset_done, VoiceProfile.PRESET_NAMES[vp.preset - 1]), Toast.LENGTH_SHORT).show()
        }
        mainContainer.addView(charSectionLocal)
        charSection = charSectionLocal
        
        // ===== READING SECTION =====
        addSectionHeader(mainContainer, R.string.sec_reading)
        val punctBtnLocal = addRow(this, mainContainer)
        punctBtn = punctBtnLocal
        punctBtnLocal.setOnClickListener { showPunctuationDialog() }
        refreshPunctButton(punctBtnLocal)
        val numberBtnLocal = addRow(this, mainContainer)
        numberBtn = numberBtnLocal
        numberBtnLocal.setOnClickListener { showNumberDialog() }
        refreshNumberButton(numberBtnLocal)
        
        // ===== LANGUAGE / DICTIONARY SECTION =====
        addSectionHeader(mainContainer, R.string.sec_dictionary)
        val langDetectBtn = addRow(this, mainContainer)
        langDetectBtn.text = getString(R.string.lang_detect_button)
        langDetectBtn.setOnClickListener { showDetectionSettingsDialog() }
        val dictBtnLocal = addRow(this, mainContainer)
        dictBtnLocal.text = getString(R.string.user_dict_button)
        dictBtnLocal.setOnClickListener { showDictMenuDialog() }
        
        // ===== UPDATES SECTION =====
        addSectionHeader(mainContainer, R.string.sec_updates)
        val updateBtnLocal = addRow(this, mainContainer)
        updateBtnLocal.text = getString(R.string.update_button)
        updateBtnLocal.setOnClickListener {
            UpdateActions.showCheckDialog(this)
        }
// Optional background update notifications. FCM push is the preferred transport
        // when Google Play services is present; on devices without it, a once-per-day
        // AlarmManager check posts the notification instead. One toggle arms whichever
        // transport is available so the two coexist (FCM primary, local fallback).
        addNoteRow(mainContainer, R.string.update_notif_note)
        var updateNotifySwitch: Switch? = null
        updateNotifySwitch = addSwitchRow(
            mainContainer,
            getString(R.string.update_notify_toggle),
            UpdateNotifPrefs.isEnabled(this) || UpdateNotificationScheduler.isEnabled(this)
        ) { on -> onUpdateNotifyToggleChanged(on, updateNotifySwitch) }
        addSwitchRow(mainContainer, getString(R.string.update_notif_prereleases),
            UpdateNotifPrefs.usesPrereleases(this)) { on ->
            UpdateNotifPrefs.get(this).edit { e ->
                e.putBoolean(UpdateNotifPrefs.USE_PRERELEASES, on)
            }
            syncFcmTopics(this)
        }
        
        // ===== ADVANCED SECTION =====
        addSectionHeader(mainContainer, R.string.sec_advanced)
        addSwitchRow(mainContainer, getString(R.string.extra_logging_row), voiceConfig!!.extraLogging) { on ->
            voiceConfig!!.setExtraLogging(on)
            Log.i("VvTtsSettings", "extra_logging -> " + on)
        }
        addNoteRow(mainContainer, R.string.extra_logging_note)
        addSwitchRow(mainContainer, getString(R.string.analytics_row),
            TelemetryPrefs.isAnalyticsEnabled(this)) { on ->
            TelemetryPrefs.setAnalyticsEnabled(this, on)
            Telemetry.apply(this)
        }
        addNoteRow(mainContainer, R.string.analytics_note)
        addSwitchRow(mainContainer, getString(R.string.perf_row),
            TelemetryPrefs.isPerfEnabled(this)) { on ->
            TelemetryPrefs.setPerfEnabled(this, on)
            Telemetry.apply(this)
        }
        addNoteRow(mainContainer, R.string.perf_note)
        addNoteRow(mainContainer, R.string.crash_note)

        // ===== MAINTENANCE SECTION =====
        addSectionHeader(mainContainer, R.string.sec_maintenance)
        val resetBtnLocal = addRow(this, mainContainer)
        resetBtnLocal.text = getString(R.string.reset_button)
        resetBtnLocal.setOnClickListener { confirmResetDefaults() }
        val aboutBtnLocal = addRow(this, mainContainer)
        aboutBtnLocal.text = getString(R.string.about_row)
        aboutBtnLocal.setOnClickListener { showAboutDialog() }
        
        scroll.addView(mainContainer)
        outer.addView(scroll)
        setContentView(outer)
maybeShowLaunchUpdateDialog()

        // #355: an FCM release notification tap reopens us with this extra;
        // surface the same update dialog a manual check would (once per launch).
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE_DIALOG, false) == true) {
            UpdateActions.showCheckDialog(this)
        }

        // Android 13+: notify() silently drops without this runtime grant. Ask
        // once while notifications are enabled so FCM release pings can surface;
        // harmless no-op on older versions where it is a normal-time grant.
        maybeRequestNotifPermission()

        // Keep FCM topic subscriptions in sync with stored toggle prefs on every
        // open (covers first-run defaults and any prefs drifted from a cold boot).
        syncFcmTopics(this)
    }
    private fun maybeRequestNotifPermission() {
        // Only ask while the user has notifications enabled (i.e. they opted in),
        // and only once, so we do not nag on every open after a denial.
        if (Build.VERSION.SDK_INT >= 33 &&
            UpdateNotifPrefs.isEnabled(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !UpdateNotifPrefs.hasAskedNotifPermission(this)) {
            UpdateNotifPrefs.markNotifPermissionAsked(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
        }
    }
    private fun addSeekBar(root: LinearLayout, label: String, initial: Int, min: Int, max: Int, barOut: ((SeekBar) -> Unit)? = null, cb:(Int) -> Unit): TextView {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = getDrawable(R.drawable.bg_slider)
        card.setPadding(dp(16), dp(12), dp(16), dp(8))
        val cLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cLp.setMargins(0, 0, 0, dp(8))
        card.layoutParams = cLp
        root.addView(card)

        val tv = TextView(this)
        // label is already a resource string; just append %d%%
        tv.text = "$label: $initial%"
        tv.textSize = 14f
        tv.setTextColor(getColor(R.color.m3_on_surface_variant))
        // Visual-only value label — a11y label lives on the SeekBar itself
        tv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        card.addView(tv)

        val bar = SeekBar(this)
        bar.id = View.generateViewId()
        bar.contentDescription = label
        bar.max = max - min
        bar.progress = initial - min
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, f: Boolean) {
                cb(p + min)
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        card.addView(bar)
        barOut?.invoke(bar)
        return tv
    }

    private fun addVoiceCharSliders(root: LinearLayout) {
        val vp = voiceProfile ?: return
        val min = 0
        val max = 100
        for (i in charLabelRes.indices) {
            val p = charParamIds[i]
            val label = getString(charLabelRes[i])
            val cb: (Int) -> Unit = { v ->
                // Resolve the preset at write time and skip programmatic refreshes, so a
                // rapid voice switch can't write one voice's values into another profile.
                if (!refreshingCharSliders) vp.setParam(vp.preset, p, v)
                charVals[i]?.text = getString(R.string.voice_param_fmt, label, v)
            }
            charVals[i] = addSeekBar(root, label, vp.getParam(vp.preset, p), min, max, { charBars[i] = it }, cb)
        }
    }

    private fun refreshVoiceCharSliders() {
        val vp = voiceProfile ?: return
        val preset = vp.preset
        refreshingCharSliders = true
        try {
            for (i in charParamIds.indices) {
                val b = charBars[i] ?: continue
                b.progress = vp.getParam(preset, charParamIds[i])
                charVals[i]?.text = getString(R.string.voice_param_fmt, getString(charLabelRes[i]), b.progress)
            }
        } finally {
            refreshingCharSliders = false
        }
    }

    /** Starts sample speech synthesis and playback on a new background thread. */
    private fun testSpeech() {
        Thread { testSpeechImpl() }.start()
    }

    /** Synthesizes and plays a sample using the current voice settings, falling back to English for unshipped dialects. */
    private fun testSpeechImpl() {
        val eng = engine
        val cfg = voiceConfig
        if (eng == null || cfg == null || !eng.isInitialized()) {
            toastOnUi(R.string.engine_not_ready)
            return
        }
        val preset = voiceProfile?.preset ?: 1
        // Pick a sample + dialect matching the current language setting
        val text: String
        val dialect: Int
        if (LanguageDetector.isDetectionEnabled()) {
            text = "Hello, this is a speech synthesis test."
                        dialect = EloquenceEngine.DIALECT_EN_US
        } else {
            val code = cfg.voice
            val lang = VoiceConfig.findLang(code)
            text = sampleTextFor(lang.code)
            dialect = bcpToDialect(lang.code)
        }
        
        // If this build lacks the language module, synthesis is
                // impossible: fall back to English and say so. Hard rule: never feed
                // the engine an unlinked dialect (eciNewEx walks an invalid voice table
                // without the module — the test button once crashed).
        if (!EloquenceEngine.isShippedDialect(dialect)) {
                    toastOnUi(R.string.preview_in_english)
            val pcmEn = eng.synthesizeCore("Hello, this is a speech synthesis test.",
                EloquenceEngine.DIALECT_EN_US, cfg.volume, preset,
                cfg.pitch, 100)
            if (pcmEn != null && pcmEn.size > 0) {
                playPcm(pcmEn, eng.getCoreSampleRate())
                toastOnUi(R.string.spoken_en)
            } else {
                toastOnUi(R.string.synth_failed)
            }
            return
        }
        val pcm = eng.synthesizeCore(text, dialect,
            cfg.volume, preset,
            cfg.pitch, 100)
        if (pcm != null && pcm.size > 0) {
            playPcm(pcm, eng.getCoreSampleRate())
            toastOnUi(R.string.synth_ok)
        } else {
                    toastOnUi(R.string.synth_failed)
                }
            }

                    /** Crash-repro hook: synthesize one exact string through the real path. */
            private fun testCrash(text: String) {
        Thread { testCrashImpl(text) }.start()
    }

    private fun toastOnUi(resId: Int) {
        runOnUiThread { Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show() }
    }

    /** Synthesizes [text] for crash diagnostics using English, and logs the outcome. */
    private fun testCrashImpl(text: String) {
                val eng = engine
                val cfg = voiceConfig
                if (eng == null || cfg == null || !eng.isInitialized()) return
                Log.i("CRASHHOOK", "start:" + text)
                val dialect = EloquenceEngine.DIALECT_EN_US
                Log.i("CRASHHOOK", "dialect=" + Integer.toHexString(dialect))
                val pcm2 = try {
                    eng.synthesizeCore(text, dialect,
                        cfg.volume, voiceProfile?.preset ?: 1, cfg.pitch, 100)
                } catch (t: Throwable) {
                    Log.e("CRASHHOOK", "exception", t)
                    null
                }
                Log.i("CRASHHOOK", "done:" + if (pcm2 != null && pcm2.size > 0) pcm2.size else "fail")
                // Force a fatal crash so a real report reaches the dashboard —
                // the whole point of the debug-only autotest crash hook
                // (am start --es autotest crash).
                CrashlyticsFx.crash()
            }

            /** Per-language sample texts */
    private fun sampleTextFor(code: String): String {
        if (code.startsWith("en")) return "Hello, this is a speech synthesis test."
        if (code.startsWith("de")) return "Hallo, das ist ein Sprachsynthesetest."
        if (code.startsWith("fr")) return "Bonjour, ceci est un test de synthèse vocale."
        if (code.startsWith("es")) return "Hola, esta es una prueba de síntesis de voz."
        if (code.startsWith("it")) return "Ciao, questo è un test di sintesi vocale."
        if (code.startsWith("pt")) return "Olá, este é um teste de síntese de voz."
        if (code.startsWith("fi")) return "Hei, tämä on puhesynteesitesti."
        if (code.startsWith("ja")) return "これは音声合成のテストです"
        return "Hello, this is a speech synthesis test."
    }

    /** bcp47 → ECI dialect (see VoiceConfig.LANGS; unlinked languages fall back to en-US) */
    private fun bcpToDialect(code: String): Int {
        val d = VoiceConfig.findLang(code).eciDialect
        return if (d != 0L) d.toInt() else EloquenceEngine.DIALECT_EN_US
    }

    private fun testGerman() {
        // English-voice comparison test: Reed(1) → Sandy(2) → Grandpa(8)
        Thread {
            val eng = engine ?: return@Thread
            val cfg = voiceConfig ?: return@Thread
            val t0 = System.currentTimeMillis()
            val pcm1 = eng.synthesizeCore("Hello, this is a voice test.",
                EloquenceEngine.DIALECT_EN_US, cfg.volume, 1)
            val pcm2 = eng.synthesizeCore("Hello, this is a voice test.",
                EloquenceEngine.DIALECT_EN_US, cfg.volume, 2)
            val pcm3 = eng.synthesizeCore("Hello, this is a voice test.",
                EloquenceEngine.DIALECT_EN_US, cfg.volume, 8)
            val ms = System.currentTimeMillis() - t0
            val total = (pcm1?.size ?: 0) + (pcm2?.size ?: 0) + (pcm3?.size ?: 0)
            Log.e("RoleTest", "three roles total=$total shorts in $ms ms")
            runOnUiThread {
                if (total > 0) {
                    Toast.makeText(this, "3 roles OK total=$total", Toast.LENGTH_SHORT).show()
                    // Play sequentially (one finishes before the next starts; avoids overlap)
                    val pcms = arrayOf(pcm1, pcm2, pcm3)
                    playSequential(pcms, 0, EloquenceEngine.SAMPLE_RATE)
                } else {
                    Toast.makeText(this, "Voice test failed (see log)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun playPcm(pcm: ShortArray, sampleRate: Int) {
        Thread { playPcmAndWait(pcm, sampleRate) }.start()
    }

    /** Play several PCM chunks sequentially (background thread; sleeps for each chunk's duration) */
    private fun playSequential(pcms: Array<ShortArray?>, idx: Int, sampleRate: Int) {
        Thread {
            for (i in idx until pcms.size) {
                val p = pcms[i]
                if (p == null || p.size == 0) continue
                playPcmAndWait(p, sampleRate)
            }
        }.start()
    }

    /** Play and block until done (duration = samples / rate) */
    private fun playPcmAndWait(pcm: ShortArray, sampleRate: Int) {
        if (pcm.isEmpty() || sampleRate <= 0) return
        val bytes = shortsToBytes(pcm)
        val track = try { AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(bytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        } catch (t: Throwable) {
            Log.e("SettingsActivity", "AudioTrack create failed", t)
            return
        }
        try {
            track.write(bytes, 0, bytes.size)
            track.play()
            Thread.sleep((pcm.size * 1000.0 / sampleRate).toLong() + 120)
        } catch (ignore: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            runCatching { track.stop() }
            track.release()
        }
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

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()

    private fun refreshLanguageUi() {
        langSwitch?.isChecked = LanguageDetector.isDetectionEnabled()
    }

    private fun addSwitchRow(root: LinearLayout, label: String, checked: Boolean, onToggle: (Boolean) -> Unit): Switch {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8); lp.bottomMargin = dp(8)
        row.layoutParams = lp
        val pad = dp(16)
        row.setPadding(pad, dp(6), pad, dp(6))
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 16f
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tv.setPadding(0, dp(2), 0, dp(2))
        val sw = Switch(this)
        sw.isChecked = checked
        // Associate the adjacent label with the switch so TalkBack announces
        // which switch this is (labelFor links the TextView to the Switch), and
        // keep a content description as a fallback for non-label scopes.
        sw.id = View.generateViewId()
        tv.labelFor = sw.id
        sw.contentDescription = label
        sw.setOnCheckedChangeListener {  _,on -> onToggle(on) }
        row.addView(tv)
        row.addView(sw)
        root.addView(row)
        return sw
    }

    private fun addNoteRow(root: LinearLayout, labelRes: Int) {
        val tv = TextView(this)
        tv.setText(labelRes)
        tv.textSize = 13f
        tv.setTextColor(0xFF808080.toInt())
        tv.setPadding(dp(16), dp(6), dp(16), dp(10))
        root.addView(tv)
    }

    /** Language picker: one immediate picker for the dialects included in this build. */
    private fun showVoiceDialog() {
        val voices = VoiceConfig.LANGS.filter { EloquenceEngine.isShippedDialect(it.eciDialect.toInt()) }
        val codes = voices.map { it.code }.toTypedArray()
        val labels = voices.map { it.name }.toTypedArray()
        val cur = voiceConfig!!.voice
        var checked = codes.indexOfFirst { it.equals(cur, ignoreCase = true) }
        if (checked < 0) checked = 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_dlg_title))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                selectLanguage(codes[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun selectLanguage(code: String) {
        val language = VoiceConfig.LANGS.firstOrNull {
            it.code == code && EloquenceEngine.isShippedDialect(it.eciDialect.toInt())
        } ?: return
        val config = voiceConfig ?: return

        config.setVoice(language.code)
        LanguageDetector.setFixedDialect(language.eciDialect.toInt())
        LanguageDetector.setDetectionEnabled(false)
        saveLanguageSettings()
        refreshLanguageUi()
        voiceBtn?.let(::refreshVoiceButton)
    }

    private fun refreshVoiceButton(btn: Button) {
        val lang = VoiceConfig.findLang(voiceConfig!!.voice)
        btn.text = getString(R.string.voice_fmt, lang.name)
    }

    /** Preset voice button: shows the active character voice name. */
    private fun refreshPresetButton(btn: Button) {
        val p = voiceProfile?.preset ?: 1
        val name = VoiceProfile.PRESET_NAMES.getOrNull(p - 1) ?: "Reed"
        btn.text = getString(R.string.preset_voice_fmt, name)
    }

    private fun showPunctuationDialog() {
        val cur = if (voiceConfig!!.punctEnabled) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.punct_title))
            .setSingleChoiceItems(
                arrayOf(getString(R.string.punct_pauses_only), getString(R.string.punct_speak_marks)),
                cur,
            ) { d, which ->
                voiceConfig!!.setPunctEnabled(which == 1)
                d.dismiss()
                punctBtn?.let { refreshPunctButton(it) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun refreshPunctButton(btn: Button) {
        val on = voiceConfig!!.punctEnabled
        btn.text = getString(
            R.string.punctuation_fmt,
            if (on) getString(R.string.punct_speak_marks) else getString(R.string.punct_pauses_only)
        )
    }
    private fun showNumberDialog() {
        val cur = if (voiceConfig!!.numberEnabled) voiceConfig!!.numberModePref + 1 else 0
        val options = arrayOf(
            getString(R.string.number_off),
            getString(R.string.number_default),
            getString(R.string.number_1digit),
            getString(R.string.number_2digit),
            getString(R.string.number_3digit),
            getString(R.string.number_4digit)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.number_title))
            .setSingleChoiceItems(options,  cur) { d, which ->
                voiceConfig!!.setNumberEnabled(which > 0)
                if (which > 0) voiceConfig!!.setNumberModePref(which - 1)
                d.dismiss()
                numberBtn?.let { refreshNumberButton(it) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun refreshNumberButton(btn: Button) {
        val on = voiceConfig!!.numberEnabled
        val label = when {
            !on -> getString(R.string.number_off)
            voiceConfig!!.numberModePref == 0 -> getString(R.string.number_default)
            voiceConfig!!.numberModePref ==  1 -> getString(R.string.number_1digit)
            voiceConfig!!.numberModePref ==  2 -> getString(R.string.number_2digit)
            voiceConfig!!.numberModePref ==  3 -> getString(R.string.number_3digit)
            else -> getString(R.string.number_4digit)
        }
        btn.text = getString(R.string.number_fmt,  label)
    }

    private fun showDictMenuDialog() {
        val items = arrayOf(
            getString(R.string.dict_add_word),
            getString(R.string.dict_word_list),
            getString(R.string.dict_import_file),
            getString(R.string.dict_export_file),
            getString(R.string.dict_clear)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_menu_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDictAddDialog()
                    1 -> showDictListDialog()
                    2 -> {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "*/*"
                        startActivityForResult(i, REQ_DICT_OPEN)
                    }
                    3 -> {
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "text/plain"
                        i.putExtra(Intent.EXTRA_TITLE, "eloquence_dictionary.txt")
                        startActivityForResult(i, REQ_DICT_CREATE)
                    }
                    else -> showDictClearConfirm()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDictAddDialog(entry: DictEntry? = null) {
            val wrapper = LinearLayout(this)
            wrapper.orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            wrapper.setPadding(pad,  pad,  pad,  pad)
            val wordInput = EditText(this)
            wordInput.hint = getString(R.string.dict_word_hint)
            val speakInput = EditText(this)
            speakInput.hint = getString(R.string.dict_speak_hint)
            val csBox = CheckBox(this)
            csBox.text = getString(R.string.dict_case_sensitive)
            if (entry != null) {
                wordInput.setText(entry.word)
                speakInput.setText(entry.spoken)
                csBox.isChecked = entry.caseSensitive
            }
            wrapper.addView(wordInput)
            wrapper.addView(speakInput)
            wrapper.addView(csBox)
            val titleRes = if (entry == null) R.string.dict_add_title else R.string.dict_edit_title
            AlertDialog.Builder(this)
                .setTitle(getString(titleRes))
                .setView(wrapper)
                .setPositiveButton(getString(R.string.dict_add)) { _, _ ->
                    val w = wordInput.text.toString().trim()
                    val s = speakInput.text.toString().trim()
                    if (w.isNotEmpty() && s.isNotEmpty()) {
                        val saved = runCatching { voiceConfig!!.addDictEntries(listOf(DictEntry(w, s, csBox.isChecked)), entry?.word) }
                        val message = if (saved.getOrDefault(false)) getString(R.string.dict_added_fmt, w)
                            else getString(R.string.dict_import_failed, saved.exceptionOrNull()?.message ?: "Could not save dictionary")
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this,  getString(R.string.dict_both_required), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel),  null)
                .show()
        }
    private fun showDictListDialog() {
            val entries = voiceConfig!!.dictEntries()
            if (entries.isEmpty()) {
                Toast.makeText(this,  getString(R.string.dict_empty), Toast.LENGTH_SHORT).show()
                return
            }
            val labels = entries.map { e ->
                val suffix = if (e.caseSensitive) " " + getString(R.string.dict_case_sensitive) else ""
                e.word + " -> " + e.spoken + suffix
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dict_list_title,  entries.size))
                .setItems(labels) { _,which ->
                    val entry = entries[which]
                    AlertDialog.Builder(this)
                        .setTitle(entry.word)
                        .setItems(arrayOf(getString(R.string.dict_edit),  getString(R.string.dict_delete))) { _, action ->
                            when (action) {
                                0 -> showDictAddDialog(entry)
                                else -> showDictDeleteConfirm(entry)
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel),  null)
                        .show()
                }
                .setPositiveButton(getString(R.string.dict_done),  null)
                .setNegativeButton(getString(R.string.dict_clear_all)) { _, _ ->
                    showDictClearConfirm()
                }
                .show()
        }

        private fun showDictDeleteConfirm(entry: DictEntry) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.dict_delete_msg,  entry.word))
                .setPositiveButton(getString(R.string.dict_delete)) { _, _ ->
                    voiceConfig!!.removeDictEntry(entry.word)
                    showDictListDialog()
                }
                .setNegativeButton(getString(R.string.cancel),  null)
                .show()
        }

                /** Confirm before wiping the whole dictionary (menu "Clear dictionary", list "Clear all"). */
                private fun showDictClearConfirm() {
                    val builder = AlertDialog.Builder(this)
                    builder.setTitle(getString(R.string.dict_clear))
                    builder.setMessage(getString(R.string.dict_clear_confirm))
                    builder.setPositiveButton(getString(R.string.dict_clear)) { _, _ ->
                        voiceConfig!!.clearDict()
                        Toast.makeText(this, getString(R.string.dict_cleared), Toast.LENGTH_SHORT).show()
                    }
                    builder.setNegativeButton(getString(R.string.cancel), null)
                    builder.show()
                }

    private fun importDictFromUri(uri: android.net.Uri) {
        val app = applicationContext
        val owner = java.lang.ref.WeakReference(this)
        Thread {
            val result = runCatching {
                val entries = ArrayList<DictEntry>()
                val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open dictionary")
                com.xw.vvtts.utils.DictionaryImport.read(input).also { entries.addAll(it) }
                check(VoiceConfig(app).addDictEntries(entries)) { "Could not save dictionary" }
                entries.size
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val activity = owner.get()
                if (activity != null && !activity.isDestroyed && !activity.isFinishing) {
                    val message = result.fold(
                        { activity.getString(R.string.dict_imported, it) },
                        { activity.getString(R.string.dict_import_failed, it.message ?: "Invalid dictionary") })
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true; name = "dictionary-import" }.start()
    }
    private fun exportDictToUri(uri: android.net.Uri) {
        val entries = voiceConfig?.dictEntries() ?: return
        Thread {
            val ok = runCatching {
                val sb = StringBuilder()
                sb.append('\uFEFF')
                for (e in entries) {
                    sb.append(e.word).append('|').append(e.spoken)
                    if (e.caseSensitive) sb.append("|cs")
                    sb.append('\n')
                }
                val out = contentResolver.openOutputStream(uri) ?: error("Cannot open output")
                out.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            }.onFailure { Log.e("SettingsActivity", "dictionary export failed", it) }.isSuccess
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    Toast.makeText(this,
                        if (ok) getString(R.string.dict_exported) else getString(R.string.synth_failed),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    private fun confirmResetDefaults() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_title))
            .setMessage(getString(R.string.reset_msg))
            .setPositiveButton(getString(R.string.reset_confirm)) { _, _ -> doResetDefaults() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun doResetDefaults() {
        for (name in arrayOf("vvtts_prefs", "vvtts_voice_profile", "vvtts_lang_settings")) {
            com.xw.vvtts.utils.MirroredPreferences(this, name).edit { it.clear() }
        }
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        restoreLanguageSettings()
        refreshLanguageUi()
        voiceBtn?.let { refreshVoiceButton(it) }
        presetBtn?.let { refreshPresetButton(it) }
        punctBtn?.let { refreshPunctButton(it) }
        numberBtn?.let { refreshNumberButton(it) }
        pitchVal?.text = getString(R.string.pitch_fmt, voiceConfig!!.pitch)
        volumeVal?.text = getString(R.string.volume_fmt, voiceConfig!!.volume)
        pitchBar?.progress = voiceConfig!!.pitch
        volumeBar?.progress = voiceConfig!!.volume
        refreshVoiceCharSliders()
        Toast.makeText(this, getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PROFILE -> {
                presetBtn?.let { refreshPresetButton(it) }
                refreshVoiceCharSliders()
            }
            REQ_DICT_OPEN -> if (data?.data != null) importDictFromUri(data.data!!)
            REQ_DICT_CREATE -> if (data?.data != null) exportDictToUri(data.data!!)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_POST_NOTIFICATIONS) return
        val sw = pendingNotifyToggle
        pendingNotifyToggle = null
        if (sw == null) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            finalizeUpdateNotify(true, sw)
        } else {
            // Denied: revert the switch and stay disabled (no alarm, no persisted flag).
            sw?.isChecked = false
            finalizeUpdateNotify(false, sw)
        }
    }

    /**
     * Applies a change to the "notify when updates available" toggle. On API 33+ an
     * unchecked->checked transition first requests POST_NOTIFICATIONS and defers here
     * until the result arrives. Persists the flag, then schedules or cancels the alarm.
     */
    private fun onUpdateNotifyToggleChanged(on: Boolean, sw: Switch?) {
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(UpdateNotificationScheduler.PERMISSION_POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotifyToggle = sw
            requestPermissions(arrayOf(UpdateNotificationScheduler.PERMISSION_POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
            return
        }
        finalizeUpdateNotify(on, sw)
    }

    private fun finalizeUpdateNotify(on: Boolean, sw: Switch?) {
        try {
            // FCM mirror is the master flag driving topic subscriptions.
            UpdateNotifPrefs.get(this).edit { e ->
                e.putBoolean(UpdateNotifPrefs.NOTIFICATIONS_ENABLED, on)
            }
            if (on) {
                maybeRequestNotifPermission()
                if (hasGooglePlayServices()) {
                    // Preferred transport: FCM push. No alarm needed (avoid double-notify).
                    UpdateNotificationScheduler.setEnabled(this, false)
                    UpdateNotificationScheduler.cancel(this)
                    syncFcmTopics(this)
                } else {
                    // No Google Play services: local daily check is the fallback.
                    UpdateNotificationScheduler.setEnabled(this, true)
                    UpdateNotificationScheduler.schedule(this)
                }
            } else {
                // Disabled: drop both transports.
                UpdateNotificationScheduler.setEnabled(this, false)
                UpdateNotificationScheduler.cancel(this)
                syncFcmTopics(this) // unsubscribes from all topics
            }
        } catch (t: Throwable) {
            Log.e("SettingsActivity", "failed to update background notifications", t)
            sw?.isChecked = !on
        }
    }

    /**
     * True when Google Play services is present and fresh enough for FCM to deliver,
     * i.e. FCM can actually receive push on this device. Per Google's guidance this is
     * checked with [GoogleApiAvailability.isGooglePlayServicesAvailable], which verifies
     * GMS is installed, enabled, and no older than this client requires — not merely that
     * a package named "com.google.android.gms" exists. Never throws: returns false whenever
     * GMS is absent, disabled, or an older-than-required version, so the local AlarmManager
     * fallback is armed instead.
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun hasGooglePlayServices(): Boolean {
        return try {
            val code: Int = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
            code == ConnectionResult.SUCCESS
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Surfaces the update dialog automatically: (A) on every app launch when a newer
     * release exists (once per process, at most — suppresses re-nagging within a session),
     * and (B) when the app was opened by tapping the update notification
     * (extra [UpdateNotificationScheduler.EXTRA_OPEN_UPDATE_DIALOG]). showCheckDialog runs
     * the check on a background thread, shows nothing when up to date, and guards re-entry.
     * Independent of the background-notification toggle: launch dialog always on.
     */
    private fun maybeShowLaunchUpdateDialog() {
        val fromNotification = intent?.getBooleanExtra(
            UpdateNotificationScheduler.EXTRA_OPEN_UPDATE_DIALOG, false) ?: false
        if (fromNotification) {
            UpdateActions.showCheckDialog(this)
            return
        }
        if (launchUpdateDialogShown) return
        launchUpdateDialogShown = true
        UpdateActions.showCheckDialog(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateActions.dismissFor(this)
    }

    /** Language-detection settings dialog */
    private fun showDetectionSettingsDialog() {
        val items = arrayOf(
            getString(R.string.lang_default_item),
            getString(R.string.lang_detect_langs_item),
            getString(R.string.lang_accent_en_item),
            getString(R.string.lang_accent_es_item),
            getString(R.string.lang_accent_fr_item),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_detect_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDefaultLanguageDialog()
                    1 -> showDetectionLanguagesDialog()
                    2 -> showEnglishAccentDialog()
                    3 -> showSpanishDialectDialog()
                    4 -> showFrenchDialectDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Default language (single choice; closes immediately). Only the
     * language family can be picked here; accents (US/UK, Spain/Mexico, etc.) are set by the accent dialogs. */
    private fun showDefaultLanguageDialog() {
        val items = arrayOf(
            getString(R.string.lang_unspecified),
            getString(R.string.lang_english),
            getString(R.string.lang_german),
            getString(R.string.lang_french),
            getString(R.string.lang_spanish),
            getString(R.string.lang_italian),
            getString(R.string.lang_japanese),
            getString(R.string.lang_polish),
        )
        // values[i] is the dialect for items[i] (English/French/Spanish use the current accent)
        val values = intArrayOf(
            LanguageDetector.DEFAULT_UNSPECIFIED,
            LanguageDetector.getEnglishDialect(),     // English (accent setting picks US/UK)
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.getFrenchDialect(),      // French (accent setting picks France/Canada)
            LanguageDetector.getSpanishDialect(),     // Spanish (accent setting picks Spain/Mexico)
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_PL_PL,
        )

        val cur = LanguageDetector.getDefaultLanguage()
        var checked = 0
        for (i in values.indices) {
            if (cur == values[i]) {
                checked = i
                break
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_dlg_default_title))
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setDefaultLanguage(values[which])
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Detect languages (multi-select) */
    private fun showDetectionLanguagesDialog() {
        // The default whitelist contains every language supported by the detector.
        val enabled = LanguageDetector.getEnabledLanguages()
        val checked = BooleanArray(LanguageDetector.ALL_LANG_CODES.size)
        for (i in LanguageDetector.ALL_LANG_CODES.indices) {
            val code = LanguageDetector.ALL_LANG_CODES[i]
            checked[i] = enabled.contains(code)
        }
        val names = LanguageDetector.ALL_LANG_NAMES
        // Mirror checkbox state into a temp array as the user toggles
        val finalChecked = checked.clone()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_dlg_detect_title))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                finalChecked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newEnabled = HashSet<String>()
                for (i in finalChecked.indices) {
                    if (finalChecked[i]) {
                        newEnabled.add(LanguageDetector.ALL_LANG_CODES[i])
                    }
                }
                // Apply immediately (setEnabledLanguages rebuilds Lingua internally)
                LanguageDetector.setEnabledLanguages(newEnabled)
                saveLanguageSettings()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** English accent */
    private fun showEnglishAccentDialog() {
        val items = arrayOf(getString(R.string.accent_en_us), getString(R.string.accent_en_gb))
        val cur = LanguageDetector.getEnglishDialect()
        val checked = if (cur == LanguageDetector.DIALECT_EN_GB) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_accent_en_item))
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setEnglishDialect(
                    if (which == 1) LanguageDetector.DIALECT_EN_GB else LanguageDetector.DIALECT_EN_US)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Spanish accent */
        private fun showSpanishDialectDialog() {
        val items = arrayOf(getString(R.string.accent_es_es), getString(R.string.accent_es_mx))
            val cur = LanguageDetector.getSpanishDialect()
            val checked = when (cur) {
                LanguageDetector.DIALECT_ES_MX -> 1
                else -> 0
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.lang_accent_es_item))
                .setSingleChoiceItems(items, checked) { d, which ->
                    LanguageDetector.setSpanishDialect(
                        when (which) {
                            1 -> LanguageDetector.DIALECT_ES_MX
                            else -> LanguageDetector.DIALECT_ES_ES
                        })
                    saveLanguageSettings()
                    d.dismiss()
                }
            .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

    /** French accent */
        private fun showFrenchDialectDialog() {
        val items = arrayOf(getString(R.string.accent_fr_fr), getString(R.string.accent_fr_ca))
            val cur = LanguageDetector.getFrenchDialect()
            val checked = if (cur == LanguageDetector.DIALECT_FR_CA) 1 else 0
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.lang_accent_fr_item))
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setFrenchDialect(
                    if (which == 1) LanguageDetector.DIALECT_FR_CA else LanguageDetector.DIALECT_FR_FR)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }


    // SharedPreferences persistence
    fun saveLanguageSettings() {
        com.xw.vvtts.utils.MirroredPreferences(this, PREFS_NAME).edit { e ->
        e.putBoolean("detection_enabled", LanguageDetector.isDetectionEnabled())
        e.putInt("fixed_dialect", LanguageDetector.getFixedDialect())
        e.putInt("english_dialect", LanguageDetector.getEnglishDialect())
        e.putInt("spanish_dialect", LanguageDetector.getSpanishDialect())
        e.putInt("french_dialect", LanguageDetector.getFrenchDialect())
        e.putInt("default_language", LanguageDetector.getDefaultLanguage())
        e.putStringSet("enabled_langs", LanguageDetector.getEnabledLanguages())
        }
    }

    fun restoreLanguageSettings() {
        val prefs = com.xw.vvtts.utils.MirroredPreferences(this, PREFS_NAME)
        LanguageDetector.setDetectionEnabled(prefs.getBoolean("detection_enabled", true))
        LanguageDetector.setFixedDialect(prefs.getInt("fixed_dialect", LanguageDetector.DIALECT_EN_US))
        LanguageDetector.setEnglishDialect(prefs.getInt("english_dialect", LanguageDetector.DIALECT_EN_US))
        LanguageDetector.setSpanishDialect(prefs.getInt("spanish_dialect", LanguageDetector.DIALECT_ES_ES))
        LanguageDetector.setFrenchDialect(prefs.getInt("french_dialect", LanguageDetector.DIALECT_FR_FR))
        LanguageDetector.setDefaultLanguage(prefs.getInt("default_language", LanguageDetector.DEFAULT_UNSPECIFIED))
        // Detection whitelist: enable all detector languages on a fresh install.
        val enabled: Set<String>
        if (prefs.contains("enabled_langs")) {
            enabled = prefs.getStringSet("enabled_langs", null) ?: emptySet<String>()
        } else {
            enabled = LanguageDetector.ALL_LANG_CODES.toSet()
        }
        LanguageDetector.setEnabledLanguages(enabled)
    }


    /** M3 section header (small caps, primary color) */
    private fun addSectionHeader(root: LinearLayout, labelRes: Int) {
        val tv = TextView(this)
        tv.text = getString(labelRes)
        tv.setAccessibilityHeading(true)
        tv.setAllCaps(true)
        tv.textSize = 12f
        tv.setLetterSpacing(0.08f)
        tv.setTextColor(getColor(R.color.m3_primary))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(8), dp(20), 0, dp(8))
        tv.layoutParams = lp
        root.addView(tv)
    }

    /** M3 list row: rounded surface, 48dp+ touch target. */
    private fun addRow(owner: Activity, root: LinearLayout): Button {
        val btn = Button(owner)
        btn.background = owner.getDrawable(R.drawable.bg_row)
        btn.setTextColor(owner.getColor(R.color.m3_on_surface))
        btn.textSize = 16f
        btn.minHeight = dp(48)
        btn.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        btn.setPadding(dp(16), 0, dp(16), 0)
        btn.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8))
        btn.layoutParams = lp
        root.addView(btn)
        return btn
    }

    /** M3 filled button (primary surface, 52dp target). */
    private fun addFilledButton(root: LinearLayout, labelRes: Int, topMargin: Int): Button {
        val btn = Button(this)
        btn.text = getString(labelRes)
        btn.background = getDrawable(R.drawable.bg_btn)
        btn.setTextColor(getColor(R.color.m3_on_primary))
        btn.textSize = 16f
        btn.minHeight = dp(52)
        btn.gravity = android.view.Gravity.CENTER
        btn.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, topMargin, 0, dp(8))
        btn.layoutParams = lp
        root.addView(btn)
        return btn
    }

    /** About dialog (ETI parity: version + credits). */
    private fun showAboutDialog() {
        var version = "?"
        try {
            version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (ignore: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_body_fmt, version))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
}